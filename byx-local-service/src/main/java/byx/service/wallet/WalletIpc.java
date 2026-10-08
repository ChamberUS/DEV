package byx.service.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import byx.service.signer.WalletLifecycle;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Set;

/** Authenticated local API. The production instance is disabled by construction. */
public final class WalletIpc {
    public interface Port { WalletView call(long peer, String token, String action, JsonNode request); }
    private final Port port;
    public WalletIpc(Port port) { this.port = java.util.Objects.requireNonNull(port); }
    public static WalletIpc disabled() {
        return new WalletIpc((peer, token, action, request) -> {
            if (!Set.of("wallet.list", "wallet.health", "wallet.signerHealth").contains(action))
                throw new WalletLifecycle.Failure("FEATURE_DISABLED");
            return WalletView.disabled();
        });
    }
    public static boolean handles(String op) { return op != null && op.startsWith("wallet."); }
    public static final Set<String> OPERATIONS = Set.of("wallet.list", "wallet.get", "wallet.health",
            "wallet.signerHealth", "wallet.operation", "wallet.createSynthetic", "wallet.deleteSynthetic", "wallet.signSynthetic");
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "wallet.list", Set.of(), "wallet.health", Set.of(), "wallet.signerHealth", Set.of(),
            "wallet.get", Set.of("walletId"), "wallet.operation", Set.of("operationId"),
            "wallet.createSynthetic", Set.of("idempotencyKey", "lossAcknowledged"),
            "wallet.deleteSynthetic", Set.of("walletId", "expectedVersion", "idempotencyKey", "lossAcknowledged"),
            "wallet.signSynthetic", Set.of("walletId", "expectedVersion", "idempotencyKey", "confirmed"));
    public void handle(long peer, String action, JsonNode request, ObjectNode response) {
        try {
            if (!request.path("v").isIntegralNumber() || !request.path("v").canConvertToInt() || request.path("v").intValue() != 1
                    || !request.path("id").isTextual() || !request.path("op").isTextual()) throw new WalletLifecycle.Failure("BAD_REQUEST");
            Set<String> fields = FIELDS.get(action);
            if (fields == null) throw new WalletLifecycle.Failure("QA_BARRIER");
            Set<String> expected = new java.util.HashSet<>(fields);
            expected.addAll(Set.of("v", "id", "op", "session", "walletSchema"));
            var names = request.fieldNames();
            while (names.hasNext()) if (!expected.remove(names.next())) throw new WalletLifecycle.Failure("BAD_REQUEST");
            if (!expected.isEmpty()) throw new WalletLifecycle.Failure("BAD_REQUEST");
            if (!request.path("walletSchema").isIntegralNumber() || !request.path("walletSchema").canConvertToInt() || request.path("walletSchema").intValue() != 1)
                throw new WalletLifecycle.Failure("SERVICE_VERSION_INCOMPATIBLE");
            for (String f : fields) if (!request.path(f).isTextual()) throw new WalletLifecycle.Failure("BAD_REQUEST");
            if (!request.path("session").isTextual() || !request.path("session").asText().matches("[A-Za-z0-9_-]{43}"))
                throw new WalletLifecycle.Failure("UNAUTHORIZED");
            if (peer == 0) throw new WalletLifecycle.Failure("UNAUTHORIZED");
            WalletView result = port.call(peer, request.path("session").asText(), action, request);
            response.put("ok", true);
            response.set("result", byx.service.Protocol.mapper().valueToTree(result));
        } catch (WalletLifecycle.Failure e) {
            response.put("ok", false); response.putObject("error").put("code", e.code);
        } catch (RuntimeException e) {
            response.put("ok", false); response.putObject("error").put("code", "INTERNAL");
        }
    }
}
