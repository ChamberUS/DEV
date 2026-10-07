package panel.localservice;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import panel.model.ChainModules;
import panel.model.ChainModules.Failure;
import panel.model.ChainModules.Reply;
import panel.util.ByxAddress;
import panel.util.DenomFormat;

/**
 * Cliente tipado das leituras públicas de MÓDULO. O painel envia só {@code id} decimal, {@code address}, {@code limit} e {@code cursor} opaco (validados aqui antes de qualquer chamada: inválido =
 * INVALID_REQUEST local, sem rede) e recebe DTOs que são REVALIDADOS (defesa em profundidade): resposta fora do contrato = CONTRACT_VIOLATION, sem dado parcial. Nunca monta URL, host ou rota.
 */
public final class ModuleReadClient implements ChainModules.Reader {
    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 10;
    /** Contrato da chain (V2.1M): base ubyx, exibição BYX, expoente 6. */
    static final int BYX_EXPONENT = 6;
    static final String BYX_DISPLAY = "BYX";
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");
    private static final Pattern CURSOR = Pattern.compile("v1\\.[a-h]\\.[A-Za-z0-9_-]{1,88}");
    private static final Pattern CHAIN_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern UINT = Pattern.compile("0|[1-9][0-9]{0,19}");
    private static final Pattern DISPLAY = Pattern.compile("([0-9]{1,40})(?:\\.([0-9]{1,18}))? ([A-Za-z][A-Za-z0-9]{1,31})");
    private static final BigInteger UINT64_MAX = new BigInteger("18446744073709551615");

    /** Uma chamada ao serviço (operação da lista fechada + args já validados). Em produção é {@code LocalServiceClient#moduleCall}. */
    @FunctionalInterface
    interface Caller {
        JsonNode call(String op, String args) throws Exception;
    }

    private final Caller client;

    public ModuleReadClient(LocalServiceClient client) {
        this.client = client::moduleCall;
    }

    ModuleReadClient(Caller caller) {
        this.client = caller;
    }

    // ---- pedidos tipados --------------------------------------------------------------------------------------------------------------

    @Override
    public Reply<ChainModules.Merchant> merchant(String id) {
        return one("byx.lojas.getMerchant", idArgs(id), ModuleReadClient::merchantOf);
    }

    @Override
    public Reply<ChainModules.Page<ChainModules.Merchant>> merchants(int limit, String cursor) {
        return list("byx.lojas.listMerchants", pageArgs(null, limit, cursor), ModuleReadClient::merchantOf);
    }

    @Override
    public Reply<ChainModules.Payment> payment(String id) {
        return one("byx.payments.getPayment", idArgs(id), ModuleReadClient::paymentOf);
    }

    @Override
    public Reply<ChainModules.Page<ChainModules.Payment>> paymentsByStore(String storeId, int limit, String cursor) {
        return list("byx.payments.listByStore", storeId == null ? null : pageArgs(storeId, limit, cursor), ModuleReadClient::paymentOf);
    }

    @Override
    public Reply<ChainModules.PaymentParams> paymentParams() {
        return one("byx.payments.params", null, d -> new ChainModules.PaymentParams(seconds(d, "defaultExpiresInSeconds"), seconds(d, "minExpiresInSeconds"), seconds(d, "maxExpiresInSeconds")));
    }

    @Override
    public Reply<ChainModules.Certificate> certificate(String id) {
        return one("byx.certificados.getCertificate", idArgs(id), ModuleReadClient::certificateOf);
    }

    @Override
    public Reply<ChainModules.Page<ChainModules.Certificate>> certificatesByMerchant(String merchantId, int limit, String cursor) {
        return list("byx.certificados.listByMerchant", merchantId == null ? null : pageArgs(merchantId, limit, cursor), ModuleReadClient::certificateOf);
    }

    @Override
    public Reply<ChainModules.Balance> balance(String address) {
        if (!ByxAddress.isValid(address)) {
            return Reply.failed(Failure.INVALID_REQUEST); // falha LOCAL: nenhuma chamada, nenhuma correção silenciosa
        }
        return one("byx.bank.balance", "{\"address\":\"" + address + "\"}", d -> {
            BigInteger amt = uint(d, "amountUbyx", 40);
            String display = display(d, amt);
            if (!address.equals(str(d, "address", 90)) || !"ubyx".equals(str(d, "denom", 32))) {
                throw new Violation();
            }
            return new ChainModules.Balance(address, amt, display);
        });
    }

    @Override
    public Reply<ChainModules.Feesplit> feesplit() {
        try {
            JsonNode r = client.call("byx.feesplit.params", null);
            if (!"NOT_EXPOSED".equals(r.path("status").asText()) || r.path("exposed").asBoolean(true)) {
                return Reply.failed(Failure.CONTRACT_VIOLATION);
            }
            JsonNode a = r.path("allocationBps");
            int d = bps(a, "distribution");
            int t = bps(a, "treasury");
            int b = bps(a, "burn");
            if (d + t + b != 10_000 || !"DOCUMENTED_DEFAULT_NOT_QUERIED".equals(r.path("source").asText())) {
                return Reply.failed(Failure.CONTRACT_VIOLATION);
            }
            return new Reply<>(true, null, ChainModules.Freshness.LIVE, 0, new ChainModules.Feesplit("DOCUMENTED_DEFAULT_NOT_QUERIED", d, t, b), null);
        } catch (RuntimeException e) {
            return Reply.failed(Failure.CONTRACT_VIOLATION);
        } catch (Exception e) {
            return Reply.failed(Failure.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public Reply<ChainModules.Health> health() {
        try {
            JsonNode r = client.call("byx.moduleHealth", null);
            String node = r.path("node").asText("");
            if (!ChainStatusClient.STATES.contains(node)) {
                return Reply.failed(Failure.CONTRACT_VIOLATION);
            }
            List<ChainModules.ModuleStatus> rows = new ArrayList<>();
            if (!r.path("modules").isArray() || r.path("modules").size() > 8) {
                return Reply.failed(Failure.CONTRACT_VIOLATION);
            }
            for (JsonNode m : r.path("modules")) {
                String module = m.path("module").asText("");
                if (!module.matches("[A-Z_]{1,24}")) {
                    return Reply.failed(Failure.CONTRACT_VIOLATION);
                }
                rows.add(new ChainModules.ModuleStatus(module, ChainModules.ModuleState.valueOf(m.path("state").asText("")), m.has("lastFailure") ? m.path("lastFailure").asText() : null));
            }
            JsonNode c = r.path("reads");
            return new Reply<>(true, null, ChainModules.Freshness.LIVE, 0,
                    new ChainModules.Health(node, List.copyOf(rows), c.path("fetches").asLong(), c.path("cacheHits").asLong(), c.path("coalesced").asLong(), c.path("rateLimited").asLong()), null);
        } catch (IllegalArgumentException | NullPointerException e) {
            return Reply.failed(Failure.CONTRACT_VIOLATION);
        } catch (Exception e) {
            return Reply.failed(Failure.SERVICE_UNAVAILABLE);
        }
    }

    // ---- envelope ---------------------------------------------------------------------------------------------------------------------

    private static final class Violation extends RuntimeException {
        Violation() {
            super(null, null, false, false);
        }
    }

    private static String idArgs(String id) {
        return id == null || !ID.matcher(id).matches() ? null : "{\"id\":\"" + id + "\"}";
    }

    private static String pageArgs(String id, int limit, String cursor) {
        if (limit < 1 || limit > MAX_LIMIT || id != null && !ID.matcher(id).matches() || cursor != null && !CURSOR.matcher(cursor).matches()) {
            return null;
        }
        StringBuilder b = new StringBuilder("{");
        if (id != null) {
            b.append("\"id\":\"").append(id).append("\",");
        }
        b.append("\"limit\":").append(limit);
        if (cursor != null) {
            b.append(",\"cursor\":\"").append(cursor).append('"');
        }
        return b.append('}').toString();
    }

    private <T> Reply<T> one(String op, String args, Function<JsonNode, T> parse) {
        if (args == null && !op.equals("byx.payments.params")) {
            return Reply.failed(Failure.INVALID_REQUEST);
        }
        return call(op, args, r -> parse.apply(r.path("data")), false);
    }

    private <T> Reply<ChainModules.Page<T>> list(String op, String args, Function<JsonNode, T> item) {
        if (args == null) {
            return Reply.failed(Failure.INVALID_REQUEST);
        }
        return call(op, args, r -> {
            JsonNode items = r.path("data").path("items");
            if (!items.isArray() || items.size() > MAX_LIMIT) {
                throw new Violation();
            }
            List<T> out = new ArrayList<>();
            for (JsonNode n : items) {
                out.add(item.apply(n));
            }
            return new ChainModules.Page<>(List.copyOf(out));
        }, true);
    }

    private <T> Reply<T> call(String op, String args, Function<JsonNode, T> parse, boolean paged) {
        try {
            JsonNode r = client.call(op, args);
            String status = r.path("status").asText("");
            if ("FAILED".equals(status)) {
                try {
                    return Reply.failed(Failure.valueOf(r.path("failure").asText("")));
                } catch (IllegalArgumentException e) {
                    return Reply.failed(Failure.CONTRACT_VIOLATION);
                }
            }
            if (!"OK".equals(status)) {
                return Reply.failed(Failure.CONTRACT_VIOLATION);
            }
            ChainModules.Freshness f = ChainModules.Freshness.valueOf(r.path("freshness").asText(""));
            long age = r.path("ageMs").isIntegralNumber() ? r.path("ageMs").asLong(-1) : -1;
            if (age < 0 || !r.path("chainId").isTextual() || !CHAIN_ID.matcher(r.path("chainId").asText()).matches()) {
                throw new Violation();
            }
            String cursor = null;
            if (r.has("nextCursor")) {
                cursor = r.path("nextCursor").asText("");
                if (!paged || !CURSOR.matcher(cursor).matches()) {
                    throw new Violation();
                }
            }
            return new Reply<>(true, null, f, age, parse.apply(r), cursor);
        } catch (Violation | IllegalArgumentException | NullPointerException | ArithmeticException e) {
            return Reply.failed(Failure.CONTRACT_VIOLATION);
        } catch (Exception e) {
            return Reply.failed(Failure.SERVICE_UNAVAILABLE);
        }
    }

    // ---- DTOs revalidados -------------------------------------------------------------------------------------------------------------

    private static ChainModules.Merchant merchantOf(JsonNode d) {
        return new ChainModules.Merchant(id(d, "id"), str(d, "name", 128), str(d, "address", 256), addr(d, "creator", false), addr(d, "operator", true), str(d, "kycStatus", 32));
    }

    private static ChainModules.Payment paymentOf(JsonNode d) {
        BigInteger amount = uint(d, "amountUbyx", 20);
        String status = str(d, "status", 16);
        return new ChainModules.Payment(id(d, "id"), id(d, "storeId"), amount, display(d, amount), str(d, "memo", 128), ChainModules.PaymentStatus.valueOf(status),
                unix(d, "createdAtUnix", false), unix(d, "expiresAtUnix", false), d.has("paidAtUnix") ? unix(d, "paidAtUnix", false) : null, d.has("payer") ? addr(d, "payer", false) : null);
    }

    private static ChainModules.Certificate certificateOf(JsonNode d) {
        String serial = str(d, "serialHash", 64);
        String sha = str(d, "imageSha256", 64);
        if (!HEX64.matcher(serial).matches() || !sha.isEmpty() && !HEX64.matcher(sha).matches() || !d.path("revoked").isBoolean() || !d.path("createdAtMs").isIntegralNumber()
                || d.path("createdAtMs").asLong() <= 0) {
            throw new Violation();
        }
        return new ChainModules.Certificate(id(d, "id"), id(d, "merchantId"), addr(d, "issuer", false), addr(d, "owner", false), str(d, "category", 32), str(d, "brand", 64), str(d, "model", 64),
                serial, str(d, "condition", 16), str(d, "notes", 256), sha, d.path("revoked").asBoolean(), str(d, "revokedReason", 128), Instant.ofEpochMilli(d.path("createdAtMs").asLong()));
    }

    private static String str(JsonNode d, String f, int max) {
        JsonNode v = d.path(f);
        if (!v.isTextual() || v.asText().length() > max) {
            throw new Violation();
        }
        String s = v.asText();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c >= 0x202a && c <= 0x202e || c >= 0x2066 && c <= 0x2069) {
                throw new Violation();
            }
        }
        return s;
    }

    private static String id(JsonNode d, String f) {
        String s = str(d, f, 18);
        if (!ID.matcher(s).matches()) {
            throw new Violation();
        }
        return s;
    }

    private static String addr(JsonNode d, String f, boolean emptyOk) {
        String s = str(d, f, 90);
        if (s.isEmpty() && emptyOk) {
            return s;
        }
        if (!ByxAddress.isValid(s)) {
            throw new Violation();
        }
        return s;
    }

    private static BigInteger uint(JsonNode d, String f, int maxDigits) {
        String s = str(d, f, maxDigits);
        if (!UINT.matcher(s).matches()) {
            throw new Violation();
        }
        BigInteger v = new BigInteger(s);
        if (maxDigits <= 20 && v.compareTo(UINT64_MAX) > 0) {
            throw new Violation();
        }
        return v;
    }

    /** O texto de exibição do serviço deve ser EXATAMENTE o valor-base formatado (mesmo número de casas): senão o contrato está quebrado. */
    private static String display(JsonNode d, BigInteger amount) {
        String s = str(d, "amountDisplay", 80);
        var m = DISPLAY.matcher(s);
        if (!m.matches()) {
            throw new Violation();
        }
        int decimals = m.group(2) == null ? 0 : m.group(2).length();
        if (decimals != BYX_EXPONENT || !BYX_DISPLAY.equals(m.group(3))) {
            throw new Violation(); // contrato da chain: ubyx -> BYX com 6 casas
        }
        String number = m.group(2) == null ? m.group(1) : m.group(1) + "." + m.group(2);
        if (!number.equals(DenomFormat.format(amount, decimals))) {
            throw new Violation();
        }
        return s;
    }

    private static Instant unix(JsonNode d, String f, boolean zero) {
        if (!d.path(f).isIntegralNumber()) {
            throw new Violation();
        }
        long v = d.path(f).asLong(-1);
        if (v < (zero ? 0 : 1) || v > 4_102_444_800L) {
            throw new Violation();
        }
        return Instant.ofEpochSecond(v);
    }

    private static long seconds(JsonNode d, String f) {
        if (!d.path(f).isIntegralNumber() || d.path(f).asLong(-1) < 0 || d.path(f).asLong() > 1_000_000_000L) {
            throw new Violation();
        }
        return d.path(f).asLong();
    }

    private static int bps(JsonNode a, String f) {
        if (!a.path(f).isIntegralNumber() || a.path(f).asInt(-1) < 0 || a.path(f).asInt() > 10_000) {
            throw new Violation();
        }
        return a.path(f).asInt();
    }
}
