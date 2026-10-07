package byx.service.chain;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Parsing ESTRITO das respostas dos módulos → DTOs mínimos (nada do JSON do nó é repassado: cada campo é lido por nome, tipado, limitado e re-emitido). O nó local NÃO é confiável: campo ausente,
 * tipo errado, enum desconhecido, valor negativo/estourado, texto com caracteres de controle, endereço bech32 inválido, denom diferente ou paginação malformada falham FECHADO (nenhum dado parcial).
 * Campos extras desconhecidos são ignorados (nunca repassados). Duplicatas de chave, aninhamento e strings gigantes são recusados pelo leitor.
 */
final class ModuleParser {
    private static final Pattern UINT64 = Pattern.compile("0|[1-9][0-9]{0,19}");
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern AMOUNT = Pattern.compile("0|[1-9][0-9]{0,39}");
    private static final Pattern NEXT_KEY = Pattern.compile("[A-Za-z0-9+/]{1,88}={0,2}");
    private static final BigInteger UINT64_MAX = new BigInteger("18446744073709551615");
    private static final long MAX_UNIX = 4_102_444_800L;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6).maxStringLength(512).maxNumberLength(24).maxDocumentLength(32 * 1024).build()).build())
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ModuleParser() { }

    record Parsed(ObjectNode data, byte[] nextKey) { }

    static Parsed parse(ReadRequest rq, byte[] body, DenomModel denom) throws ChainException {
        JsonNode root = tree(body);
        return switch (rq.op()) {
            case LOJAS_GET_MERCHANT -> one(merchant(object(root, "merchant")), rq.op(), rq, "id");
            case LOJAS_LIST_MERCHANTS -> list(root, "merchant", rq, null, ModuleParser::merchant, denom);
            case PAYMENTS_GET_PAYMENT -> one(payment(object(root, "payment_request"), denom), rq.op(), rq, "id");
            case PAYMENTS_LIST_BY_STORE -> list(root, "payment_requests", rq, "storeId", (n, d) -> payment(n, d), denom);
            case PAYMENTS_PARAMS -> new Parsed(paymentParams(object(root, "params")), null);
            case CERTIFICADOS_GET_CERTIFICATE -> one(certificate(object(root, "certificate")), rq.op(), rq, "id");
            case CERTIFICADOS_LIST_BY_MERCHANT -> list(root, "certificates", rq, "merchantId", (n, d) -> certificate(n), denom);
            case BANK_BALANCE -> balance(object(root, "balance"), rq, denom);
        };
    }

    // ---- listas e paginação ----------------------------------------------------------------------------------------------------------

    private interface ItemParser { ObjectNode apply(JsonNode n, DenomModel d) throws ChainException; }

    private static Parsed list(JsonNode root, String field, ReadRequest rq, String ownerField, ItemParser item, DenomModel denom) throws ChainException {
        JsonNode arr = root.get(field);
        if (arr == null) {
            throw new ChainException(ChainReason.MISSING_FIELD);
        }
        if (!arr.isArray() || arr.size() > rq.limit()) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        ArrayNode items = F.arrayNode();
        for (JsonNode n : arr) {
            ObjectNode dto = item.apply(n, denom);
            if (ownerField != null && !dto.get(ownerField).asText().equals(rq.idForCheck())) {
                throw new ChainException(ChainReason.SCHEMA_INVALID); // item de outra loja/merchant: contrato violado
            }
            items.add(dto);
        }
        JsonNode page = root.get("pagination");
        if (page == null || !page.isObject()) {
            throw new ChainException(page == null ? ChainReason.MISSING_FIELD : ChainReason.TYPE_MISMATCH);
        }
        JsonNode nk = page.get("next_key");
        byte[] next = null;
        if (nk == null) {
            throw new ChainException(ChainReason.MISSING_FIELD);
        }
        if (!nk.isNull()) {
            if (!nk.isTextual() || !NEXT_KEY.matcher(nk.asText()).matches()) {
                throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
            }
            try {
                next = Base64.getDecoder().decode(nk.asText());
            } catch (IllegalArgumentException e) {
                throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
            }
            if (next.length == 0 || next.length > 64) {
                throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
            }
        }
        ObjectNode data = F.objectNode();
        data.set("items", items);
        return new Parsed(data, next);
    }

    private static Parsed one(ObjectNode dto, ReadOp op, ReadRequest rq, String idField) throws ChainException {
        if (!dto.get(idField).asText().equals(rq.idForCheck())) {
            throw new ChainException(ChainReason.SCHEMA_INVALID); // o nó devolveu OUTRO registro
        }
        return new Parsed(dto, null);
    }

    // ---- DTOs --------------------------------------------------------------------------------------------------------------------------

    private static ObjectNode merchant(JsonNode n) throws ChainException {
        return merchant(n, null);
    }

    private static ObjectNode merchant(JsonNode n, DenomModel ignored) throws ChainException {
        ObjectNode o = F.objectNode();
        o.put("id", id(n, "id"));
        o.put("name", text(n, "nome", 1, 128));
        o.put("address", text(n, "endereco", 0, 256));
        o.put("creator", address(n, "creator", false));
        o.put("operator", address(n, "operator_address", true));
        o.put("kycStatus", text(n, "kyc_status", 0, 32));
        // kyc_ref, document_hash e saldo existem no nó mas NÃO são necessários à tela: não são lidos nem repassados
        return o;
    }

    private static ObjectNode payment(JsonNode n, DenomModel denom) throws ChainException {
        BigInteger amount = uint64(n, "amount_ubyx");
        if (denom == null) {
            throw new ChainException(ChainReason.DENOM_MISMATCH);
        }
        ObjectNode o = F.objectNode();
        o.put("id", id(n, "id"));
        o.put("storeId", id(n, "loja_id"));
        o.put("amountUbyx", amount.toString());
        o.put("amountDisplay", denom.format(amount) + " " + denom.display());
        o.put("memo", text(n, "memo", 0, 128));
        o.put("status", paymentStatus(text(n, "status", 1, 40)));
        o.put("createdAtUnix", unix(n, "created_at_unix", false));
        o.put("expiresAtUnix", unix(n, "expires_at_unix", false));
        long paid = unix(n, "paid_at_unix", true);
        if (paid > 0) {
            o.put("paidAtUnix", paid);
        }
        String payer = address(n, "payer", true);
        if (!payer.isEmpty()) {
            o.put("payer", payer);
        }
        return o;
    }

    /** Status público OFICIAL da Query (já derivado pelo nó: PENDING vencido aparece EXPIRED). Enum desconhecido = contrato quebrado. */
    private static String paymentStatus(String s) throws ChainException {
        return switch (s) {
            case "PAYMENT_STATUS_PENDING" -> "PENDING";
            case "PAYMENT_STATUS_PAID" -> "PAID";
            case "PAYMENT_STATUS_EXPIRED" -> "EXPIRED";
            case "PAYMENT_STATUS_CANCELED" -> "CANCELED";
            default -> throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        };
    }

    private static ObjectNode paymentParams(JsonNode n) throws ChainException {
        ObjectNode o = F.objectNode();
        o.put("defaultExpiresInSeconds", bounded(n, "default_expires_in_seconds"));
        o.put("minExpiresInSeconds", bounded(n, "min_expires_in_seconds"));
        o.put("maxExpiresInSeconds", bounded(n, "max_expires_in_seconds"));
        return o;
    }

    private static long bounded(JsonNode n, String f) throws ChainException {
        BigInteger v = uint64(n, f);
        if (v.compareTo(BigInteger.valueOf(1_000_000_000L)) > 0) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        return v.longValue();
    }

    private static ObjectNode certificate(JsonNode n) throws ChainException {
        ObjectNode o = F.objectNode();
        o.put("id", id(n, "id"));
        o.put("merchantId", id(n, "merchant_id"));
        o.put("issuer", address(n, "issuer", false));
        o.put("owner", address(n, "owner", false));
        o.put("category", text(n, "category", 1, 32));
        o.put("brand", text(n, "brand", 0, 64));
        o.put("model", text(n, "model", 0, 64));
        String serial = text(n, "serial_hash", 0, 64);
        if (!HEX64.matcher(serial).matches()) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE); // serial vazio/inválido nunca é aceito nem inventado
        }
        o.put("serialHash", serial);
        o.put("condition", text(n, "condition", 0, 16));
        o.put("notes", text(n, "notes", 0, 256));
        String sha = text(n, "image_sha256", 0, 64);
        if (!sha.isEmpty() && !HEX64.matcher(sha).matches()) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        o.put("imageSha256", sha);
        JsonNode revoked = n.get("revoked");
        if (revoked == null) {
            throw new ChainException(ChainReason.MISSING_FIELD);
        }
        if (!revoked.isBoolean()) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        o.put("revoked", revoked.asBoolean());
        o.put("revokedReason", text(n, "revoked_reason", 0, 128));
        try {
            o.put("createdAtMs", Instant.parse(text(n, "created_at", 20, 40)).toEpochMilli());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        return o;
    }

    private static Parsed balance(JsonNode n, ReadRequest rq, DenomModel denom) throws ChainException {
        if (denom == null || !denom.base().equals(text(n, "denom", 1, 32))) {
            throw new ChainException(ChainReason.DENOM_MISMATCH);
        }
        String a = text(n, "amount", 1, 40);
        if (!AMOUNT.matcher(a).matches()) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        BigInteger amount = new BigInteger(a);
        ObjectNode o = F.objectNode();
        o.put("address", rq.addressForCheck());
        o.put("denom", denom.base());
        o.put("amountUbyx", amount.toString());
        o.put("amountDisplay", denom.format(amount) + " " + denom.display());
        return new Parsed(o, null);
    }

    // ---- primitivos ------------------------------------------------------------------------------------------------------------------

    private static JsonNode tree(byte[] body) throws ChainException {
        try {
            JsonNode n = JSON.readTree(body);
            if (n == null || !n.isObject()) {
                throw new ChainException(ChainReason.SCHEMA_INVALID);
            }
            return n;
        } catch (java.io.IOException e) {
            if (e instanceof ChainException c) {
                throw c;
            }
            throw new ChainException(ChainReason.JSON_INVALID);
        }
    }

    private static JsonNode object(JsonNode parent, String field) throws ChainException {
        JsonNode v = parent.get(field);
        if (v == null) {
            throw new ChainException(ChainReason.MISSING_FIELD);
        }
        if (!v.isObject()) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        return v;
    }

    private static String raw(JsonNode parent, String field) throws ChainException {
        if (parent == null || !parent.isObject()) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        JsonNode v = parent.get(field);
        if (v == null) {
            throw new ChainException(ChainReason.MISSING_FIELD);
        }
        if (!v.isTextual()) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        return v.asText();
    }

    /** Texto exibível: tamanho limitado e sem caracteres de controle nem de reordenação bidirecional. */
    private static String text(JsonNode parent, String field, int min, int max) throws ChainException {
        String s = raw(parent, field);
        if (s.length() < min || s.length() > max) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || c >= 0x80 && c < 0xa0 || c >= 0x202a && c <= 0x202e || c >= 0x2066 && c <= 0x2069 || c == 0x200e || c == 0x200f) {
                throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
            }
        }
        return s;
    }

    private static String id(JsonNode parent, String field) throws ChainException {
        String s = raw(parent, field);
        if (!ID.matcher(s).matches()) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        return s;
    }

    private static BigInteger uint64(JsonNode parent, String field) throws ChainException {
        String s = raw(parent, field);
        if (!UINT64.matcher(s).matches()) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE); // negativo, decimal, notação científica, lixo
        }
        BigInteger v = new BigInteger(s);
        if (v.compareTo(UINT64_MAX) > 0) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        return v;
    }

    private static long unix(JsonNode parent, String field, boolean zeroAllowed) throws ChainException {
        BigInteger v = uint64(parent, field);
        if (v.compareTo(BigInteger.valueOf(MAX_UNIX)) > 0 || !zeroAllowed && v.signum() == 0) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        return v.longValue();
    }

    private static String address(JsonNode parent, String field, boolean emptyAllowed) throws ChainException {
        String s = raw(parent, field);
        if (s.isEmpty() && emptyAllowed) {
            return s;
        }
        if (!Bech32.isValidAddress(s)) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        return s;
    }
}
