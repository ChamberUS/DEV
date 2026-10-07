package panel.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.*;
import java.math.BigInteger;
import java.util.*;
import panel.model.ByxConfig;
import panel.model.ByxSnapshot;

public final class CosmosByxChainGateway implements ByxChainGateway {
    /** Criado no primeiro uso: montar o HttpClient (TLS) custa ~100 ms e estes adaptadores não são usados antes do login. */
    private volatile HttpClient client;
    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
                }
            }
        }
        return c;
    }

    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;
    public CosmosByxChainGateway(Clock clock) { this.clock = clock; }
    public String source() { return "LIVE_NODE"; }
    private JsonNode get(URI origin, String path) throws Exception {
        var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(3)).GET().build();
        var response = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new java.io.IOException("Read endpoint unavailable");
        return json.readTree(response.body());
    }
    public static String fingerprint(JsonNode genesis) throws Exception {
        byte[] bytes = new ObjectMapper().writeValueAsBytes(canonical(genesis));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = new ObjectMapper().createObjectNode();
            var names = new TreeSet<String>(); node.fieldNames().forEachRemaining(names::add);
            names.forEach(n -> sorted.set(n, canonical(node.get(n)))); return sorted;
        }
        if (node.isArray()) {
            var array = new ObjectMapper().createArrayNode(); node.forEach(n -> array.add(canonical(n))); return array;
        }
        return node;
    }
    private static String required(JsonNode node, String name) {
        var value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value.asText();
    }
    public ByxSnapshot read(ByxConfig c) throws Exception {
        JsonNode nodeInfo = get(c.endpoint(), "/cosmos/base/tendermint/v1beta1/node_info").path("default_node_info");
        String chain = required(nodeInfo, "network");
        if (!c.expectedChainId().equals(chain)) return unverified(c, "Chain ID mismatch");
        JsonNode rpcInfo = get(c.rpcEndpoint(), "/status").path("result").path("node_info");
        String nodeId = nodeInfo.path("default_node_id").asText();
        if (nodeId.isBlank() || !nodeId.equals(rpcInfo.path("id").asText())
                || !chain.equals(rpcInfo.path("network").asText())) return unverified(c, "REST/RPC node identity mismatch or absent");
        JsonNode genesis = get(c.rpcEndpoint(), "/genesis").path("result").path("genesis");
        if (!genesis.isObject() || !chain.equals(genesis.path("chain_id").asText())
                || !c.genesisFingerprint().equals(fingerprint(genesis))) return unverified(c, "Genesis mismatch or absent");
        JsonNode metadata = get(c.endpoint(), "/cosmos/bank/v1beta1/denoms_metadata/ubyx").path("metadata");
        boolean base = false, display = false;
        for (JsonNode unit : metadata.path("denom_units")) {
            if (unit.path("denom").asText().equals(c.baseDenom()) && unit.path("exponent").asInt(-1) == 0) base = true;
            if (unit.path("denom").asText().equals(c.displayDenom()) && unit.path("exponent").asInt(-1) == c.decimals()) display = true;
        }
        if (!base || !display || !c.baseDenom().equals(metadata.path("base").asText())
                || !c.displayDenom().equals(metadata.path("display").asText())) return unverified(c, "Asset metadata mismatch or absent");
        JsonNode latest = get(c.endpoint(), "/cosmos/base/tendermint/v1beta1/blocks/latest");
        JsonNode header = latest.has("sdk_block") ? latest.path("sdk_block").path("header") : latest.path("block").path("header");
        if (!chain.equals(header.path("chain_id").asText())) return unverified(c, "Block chain ID mismatch or absent");
        String height = required(header, "height");
        if (!height.matches("[0-9]+") || new BigInteger(height).signum() <= 0) throw new IllegalArgumentException("Invalid height");
        Instant blockTime = Instant.parse(required(header, "time"));
        JsonNode sync = get(c.endpoint(), "/cosmos/base/tendermint/v1beta1/syncing").path("syncing");
        Boolean syncing = sync.isBoolean() ? sync.booleanValue() : null;
        BigInteger balance = null;
        if (!c.observedAddress().isEmpty()) {
            JsonNode coin = get(c.endpoint(), "/cosmos/bank/v1beta1/balances/" + c.observedAddress() + "/by_denom?denom=ubyx").path("balance");
            if (!coin.isMissingNode() && !coin.isNull()) {
                if (!c.baseDenom().equals(coin.path("denom").asText())) return unverified(c, "Balance denom mismatch");
                String amount = required(coin, "amount");
                if (!amount.matches("[0-9]+")) throw new IllegalArgumentException("Invalid integer balance");
                balance = new BigInteger(amount);
            }
        }
        Instant now = clock.instant();
        String freshness = blockTime.isAfter(now.plusSeconds(30)) ? "UNKNOWN" :
                blockTime.isBefore(now.minusSeconds(60)) ? "STALE" : "FRESH";
        return new ByxSnapshot(source(), c.environment(), "ONLINE", "VERIFIED", freshness, syncing, chain,
                height, blockTime, c.observedAddress(), balance, c.displayDenom(), c.decimals(), now,
                "Read-only observation; no proof of ownership");
    }
    private ByxSnapshot unverified(ByxConfig c, String message) {
        return ByxSnapshot.unknown(source(), c.environment(), "ONLINE", message);
    }
}
