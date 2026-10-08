package byx.service.signer;

import com.fasterxml.jackson.databind.JsonNode;
import byx.service.wallet.WalletView;
import byx.service.wallet.WalletIpc;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import static byx.service.signer.WalletCatalog.*;

/** Service owns capability, ownership, projection and guards; Panel owns none of these. */
public final class WalletEndpoint implements WalletIpc.Port {
    public record Principal(WalletLifecycle.Session session, boolean admin, boolean elevated) { }
    interface SyntheticSign { String sign(Wallet wallet, String idempotencyKey); }
    private final WalletLifecycle lifecycle;
    private final BiFunction<Long, String, Principal> resolve;
    private final ThreadLocal<Principal> current;
    private final SyntheticSign syntheticSign;
    private record RequestAuth(long peer, String token, boolean mutation) { }
    private final ThreadLocal<RequestAuth> requestAuth = new ThreadLocal<>();
    WalletLifecycle.Session currentSession() {
        RequestAuth context = requestAuth.get();
        if (context == null) return null;
        Principal p = resolve.apply(context.peer(), context.token());
        if (p == null || context.mutation() && (!p.admin() || !p.elevated() || !p.session().recentMfa())) return null;
        return p.session();
    }
    private final String generation = WalletLifecycle.randomId();

    WalletEndpoint(WalletLifecycle lifecycle, BiFunction<Long, String, Principal> resolve,
            ThreadLocal<Principal> current, SyntheticSign syntheticSign) {
        this.lifecycle = lifecycle; this.resolve = resolve; this.current = current; this.syntheticSign = syntheticSign;
    }
    @Override public WalletView call(long peer, String token, String action, JsonNode request) {
        Principal principal = resolve.apply(peer, token);
        if (principal == null) throw new WalletLifecycle.Failure("UNAUTHORIZED");
        current.set(principal);
        requestAuth.set(new RequestAuth(peer, token, action.endsWith("Synthetic")));
        try {
            boolean mutation = action.endsWith("Synthetic");
            if (mutation && (!principal.admin() || !principal.elevated() || !principal.session().recentMfa()))
                throw new WalletLifecycle.Failure("FORBIDDEN");
            WalletLifecycle.Observation observation = lifecycle.publicationObservation();
            String walletSelection = action.equals("wallet.get") ? request.path("walletId").asText() : null;
            String operationSelection = action.equals("wallet.operation") ? request.path("operationId").asText() : null;
            WalletView before = project(principal, observation, walletSelection, operationSelection);
            Operation replay = mutation ? lifecycle.publicationCatalog().operations().stream()
                    .filter(o -> o.ownerAccountId().equals(principal.session().ownerAccountId())
                            && o.idempotencyKey().equals(request.path("idempotencyKey").asText())).findFirst().orElse(null) : null;
            switch (action) {
                case "wallet.createSynthetic" -> {
                    if (!before.allowedActions().canCreate() && !(replay != null && replay.action() == Action.CREATE && replay.status() == Status.COMPLETE)) throw new WalletLifecycle.Failure("ACTION_DENIED");
                    lifecycle.create(key(request), null, acknowledged(request, "lossAcknowledged"));
                }
                case "wallet.deleteSynthetic", "wallet.signSynthetic" -> {
                    String id = request.path("walletId").asText(); WalletCatalog.id(id);
                    Wallet wallet = lifecycle.query().stream().filter(w -> w.walletId().equals(id)).findFirst()
                            .orElseThrow(() -> new WalletLifecycle.Failure("WALLET_NOT_FOUND"));
                    boolean deleting = action.equals("wallet.deleteSynthetic");
                    if (!(deleting ? before.allowedActions().canDelete() || replay != null && replay.action() == Action.DELETE && replay.status() == Status.COMPLETE : before.allowedActions().canSyntheticSign()))
                        throw new WalletLifecycle.Failure("ACTION_DENIED");
                    String version = request.path("expectedVersion").asText();
                    if (!version.matches("[1-9][0-9]{0,15}") || Long.parseLong(version) != (replay != null && replay.action() == Action.DELETE ? replay.expectedWalletVersion() : wallet.version()))
                        throw new WalletLifecycle.Failure("CONFLICT");
                    if (deleting) lifecycle.delete(id, Long.parseLong(version), key(request), acknowledged(request, "lossAcknowledged"));
                    else {
                        if (!acknowledged(request, "confirmed")) throw new WalletLifecycle.Failure("CONFIRMATION_REQUIRED");
                        syntheticSign.sign(wallet, key(request));
                    }
                }
                case "wallet.get" -> {
                    String id = request.path("walletId").asText();
                    if (before.wallets().stream().noneMatch(w -> w.walletId().equals(id))) throw new WalletLifecycle.Failure("WALLET_NOT_FOUND");
                }
                case "wallet.operation" -> {
                    String id = request.path("operationId").asText();
                    if (before.operations().stream().noneMatch(o -> o.operationId().equals(id))) throw new WalletLifecycle.Failure("OPERATION_NOT_FOUND");
                }
                default -> { }
            }
            Principal after = resolve.apply(peer, token);
            if (after == null || !after.equals(principal)) throw new WalletLifecycle.Failure("UNAUTHORIZED");
            return mutation ? project(principal, observation, null, null) : before;
        } finally { current.remove(); requestAuth.remove(); }
    }
    private static String key(JsonNode request) {
        String value = request.path("idempotencyKey").asText(); WalletCatalog.id(value); return value;
    }
    private static boolean acknowledged(JsonNode request, String field) {
        String value = request.path(field).asText();
        if (!Set.of("true", "false").contains(value)) throw new WalletLifecycle.Failure("BAD_REQUEST");
        return value.equals("true");
    }
    private WalletView project(Principal principal, WalletLifecycle.Observation observation, String walletSelection, String operationSelection) {
        WalletCatalog catalog = lifecycle.publicationCatalog();
        List<Wallet> records = catalog.wallets().stream().filter(w -> w.ownerAccountId().equals(principal.session().ownerAccountId())).toList();
        Wallet latestDeleted = records.stream().filter(w -> w.durableState() == State.DELETED)
                .max(java.util.Comparator.comparingLong(Wallet::updatedAtMs)).orElse(null);
        // V1 current slot + latest tombstone; wallet.get addresses older public history explicitly.
        List<WalletView.Wallet> wallets = records.stream()
                .filter(w -> walletSelection != null ? w.walletId().equals(walletSelection) : w.durableState() != State.DELETED || w.equals(latestDeleted)).map(w -> new WalletView.Wallet(w.walletId(),
                w.address(), w.publicKey(), w.version(), w.durableState().name(), observation.availability().equals("INVENTORY_INCOMPLETE") ? "INVENTORY_INCOMPLETE" : w.durableState() == State.ACTIVE ? observation.health().getOrDefault(w.walletId(), lifecycle.health(w.walletId())) : lifecycle.health(w.walletId()),
                w.recoveryPolicy(), w.createdAtMs(), w.updatedAtMs())).toList();
        List<Operation> ownedOperations = catalog.operations().stream().filter(o -> o.ownerAccountId().equals(principal.session().ownerAccountId())).toList();
        // Bounded recent public diagnostics; operation lookup uses the full owner-filtered journal.
        List<WalletView.Operation> operations = ownedOperations.stream()
                .filter(o -> operationSelection == null || o.operationId().equals(operationSelection))
                .sorted(java.util.Comparator.comparingLong(Operation::updatedAtMs).reversed()).limit(8)
                .map(o -> new WalletView.Operation(o.operationId(), o.idempotencyKey(), o.walletId(), o.action().name(), o.status().name(),
                        o.outcomeCode(), o.action() == Action.SIGN && o.status() == Status.COMPLETE ? o.publicResult() : null,
                        o.updatedAtMs())).toList();
        Wallet live = records.stream().filter(w -> w.durableState() != State.DELETED).findFirst().orElse(null);
        String state = live == null ? records.isEmpty() ? "NO_WALLET" : "DELETED" : switch (live.durableState()) {
            case CREATING -> "CREATING"; case DELETING -> "DELETING"; case ACTIVE -> "READY"; case DELETED -> "DELETED";
        };
        boolean blocked = !Set.of("AVAILABLE", "OPERATION_PENDING").contains(observation.availability()) || !lifecycle.publicationReconciled() || catalog.quarantines().stream().anyMatch(q -> q.resolvedAtMs() == null);
        if (blocked) state = "NEEDS_ATTENTION";
        if (live != null && !"HEALTHY".equals(observation.health().getOrDefault(live.walletId(), lifecycle.health(live.walletId())))) {
            String health = observation.health().getOrDefault(live.walletId(), lifecycle.health(live.walletId()));
            state = switch (health) {
                case "CREATE_IN_PROGRESS" -> "CREATING";
                case "LOCKED", "KEYCHAIN_LOCKED" -> "LOCKED";
                case "SIGNER_UNAVAILABLE", "KEYCHAIN_UNAVAILABLE" -> "SIGNER_UNAVAILABLE";
                case "DEGRADED" -> "DEGRADED";
                default -> "NEEDS_ATTENTION";
            };
        }
        if (observation.availability().equals("OPERATION_PENDING") && !Set.of("CREATING", "DELETING").contains(state)) state = "OPERATION_PENDING";
        if (Set.of("SIGNER_UNAVAILABLE", "DEGRADED").contains(observation.availability())) state = observation.availability();
        if (ownedOperations.stream().anyMatch(o -> o.status() == Status.UNKNOWN)) state = "UNKNOWN_RESULT";
        else if (state.equals("READY") && ownedOperations.stream().anyMatch(o -> o.status() == Status.PENDING)) state = "OPERATION_PENDING";
        boolean qa = principal.admin() && principal.elevated() && principal.session().recentMfa();
        boolean healthy = !blocked && live != null && state.equals("READY");
        boolean settled = ownedOperations.stream().noneMatch(o -> o.status() == Status.PENDING || o.status() == Status.UNKNOWN);
        return new WalletView(1, generation, catalog.revision(), "SYNTHETIC_QA", state,
                observation.availability(),
                wallets, operations, new WalletView.AllowedActions(qa && !blocked && !observation.availability().equals("OPERATION_PENDING") && live == null && settled,
                        qa && healthy && settled, qa && healthy && settled && syntheticSign != null));
    }
}
