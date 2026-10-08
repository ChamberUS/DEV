package panel.wallet;

import java.util.List;

/** Closed public projection. Custody identifiers and receipts never cross this boundary. */
public record WalletView(int schemaVersion, String serviceGeneration, long revision,
        String capability, String state, String signerAvailability,
        List<Wallet> wallets, List<Operation> operations, AllowedActions allowedActions) {
    public WalletView {
        java.util.Objects.requireNonNull(serviceGeneration); java.util.Objects.requireNonNull(capability);
        java.util.Objects.requireNonNull(state); java.util.Objects.requireNonNull(signerAvailability); java.util.Objects.requireNonNull(allowedActions);
        if (schemaVersion != 1 || revision < 0 || !java.util.Set.of("DISABLED", "SYNTHETIC_QA").contains(capability)
                || capability.equals("SYNTHETIC_QA") && !serviceGeneration.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("PUBLIC_CONTRACT");
        wallets = List.copyOf(wallets); operations = List.copyOf(operations);
        if (capability.equals("DISABLED") && (allowedActions.canCreate() || allowedActions.canDelete() || allowedActions.canSyntheticSign()))
            throw new IllegalArgumentException("PUBLIC_CONTRACT");
    }
    public record Wallet(String walletId, String address, String publicKey, long version,
            String lifecycleState, String healthState, String recoveryPolicy,
            long createdAt, long updatedAt) { }
    public record Operation(String operationId, String requestId, String walletId, String action, String state,
            String outcome, String publicHash, long updatedAt) { }
    public record AllowedActions(boolean canCreate, boolean canDelete, boolean canSyntheticSign) { }
    public static WalletView disabled() {
        return new WalletView(1, "", 0, "DISABLED", "UNAVAILABLE", "UNAVAILABLE",
                List.of(), List.of(), new AllowedActions(false, false, false));
    }
}
