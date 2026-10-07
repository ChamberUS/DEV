package byx.service.chain;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigInteger;
import java.time.Instant;
import java.util.regex.Pattern;

/** Validação ESTRITA das respostas do nó local: o nó não é confiável só por estar em localhost. Toda falha vira {@link ChainException} com razão fixa; nada do corpo é copiado. */
final class ChainParser {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(10).maxStringLength(512).maxNumberLength(24).build()).build())
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Pattern CHAIN_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern HEIGHT = Pattern.compile("[0-9]{1,15}");
    private static final Pattern HASH = Pattern.compile("[0-9A-Fa-f]{64}");
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,40}");
    static final long MAX_HEIGHT = 999_999_999_999_999L;

    private ChainParser() { }

    record NodeStatus(String chainId, long height, long blockTimeMs, boolean catchingUp, String blockHash) { }

    record Metadata(String base, String display, int displayExponent) { }

    private static JsonNode parse(byte[] body) throws ChainException {
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

    private static String text(JsonNode parent, String field) throws ChainException {
        JsonNode v = parent.get(field);
        if (v == null) {
            throw new ChainException(ChainReason.MISSING_FIELD);
        }
        if (!v.isTextual()) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        return v.asText();
    }

    /** RPC /status: result.node_info.network, result.sync_info.{latest_block_height, latest_block_time, catching_up}. */
    static NodeStatus nodeStatus(byte[] body) throws ChainException {
        JsonNode result = object(parse(body), "result");
        String chain = text(object(result, "node_info"), "network");
        if (!CHAIN_ID.matcher(chain).matches()) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        JsonNode sync = object(result, "sync_info");
        String h = text(sync, "latest_block_height");
        if (!HEIGHT.matcher(h).matches()) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        long height = Long.parseLong(h);
        if (height <= 0 || height > MAX_HEIGHT) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        long timeMs;
        try {
            timeMs = Instant.parse(text(sync, "latest_block_time")).toEpochMilli();
        } catch (java.time.format.DateTimeParseException e) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        if (timeMs <= 0) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        JsonNode catching = sync.get("catching_up");
        if (catching == null) {
            throw new ChainException(ChainReason.MISSING_FIELD);
        }
        if (!catching.isBoolean()) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        // hash do último bloco: OPCIONAL (informativo); se presente deve ser hex de 64 dígitos, senão o contrato está quebrado
        String hash = null;
        JsonNode h2 = sync.get("latest_block_hash");
        if (h2 != null) {
            if (!h2.isTextual()) {
                throw new ChainException(ChainReason.TYPE_MISMATCH);
            }
            if (!h2.asText().isEmpty()) {
                if (!HASH.matcher(h2.asText()).matches()) {
                    throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
                }
                hash = h2.asText();
            }
        }
        return new NodeStatus(chain, height, timeMs, catching.asBoolean(), hash);
    }

    /** REST bank denoms_metadata: metadata.{base, display, denom_units[{denom, exponent}]}; devolve o expoente da unidade de exibição. */
    static Metadata denomMetadata(byte[] body) throws ChainException {
        JsonNode m = object(parse(body), "metadata");
        String base = text(m, "base");
        String display = text(m, "display");
        JsonNode units = m.get("denom_units");
        if (units == null) {
            throw new ChainException(ChainReason.MISSING_FIELD);
        }
        if (!units.isArray() || units.size() == 0 || units.size() > 8) {
            throw new ChainException(ChainReason.TYPE_MISMATCH);
        }
        Integer baseExp = null;
        Integer displayExp = null;
        for (JsonNode u : units) {
            String denom = text(u, "denom");
            JsonNode e = u.get("exponent");
            if (e == null) {
                throw new ChainException(ChainReason.MISSING_FIELD);
            }
            if (!e.isIntegralNumber() || !e.canConvertToInt() || e.asInt() < 0 || e.asInt() > 18) {
                throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
            }
            if (denom.equals(base)) {
                baseExp = e.asInt();
            }
            if (denom.equals(display)) {
                displayExp = e.asInt();
            }
        }
        if (baseExp == null || baseExp != 0 || displayExp == null) {
            throw new ChainException(ChainReason.DENOM_MISMATCH);
        }
        return new Metadata(base, display, displayExp);
    }

    /** REST bank supply by_denom: amount.{denom, amount}. */
    static BigInteger supply(byte[] body, String expectedDenom) throws ChainException {
        JsonNode a = object(parse(body), "amount");
        if (!expectedDenom.equals(text(a, "denom"))) {
            throw new ChainException(ChainReason.DENOM_MISMATCH);
        }
        String amount = text(a, "amount");
        if (!AMOUNT.matcher(amount).matches()) {
            throw new ChainException(ChainReason.VALUE_OUT_OF_RANGE);
        }
        return new BigInteger(amount);
    }
}
