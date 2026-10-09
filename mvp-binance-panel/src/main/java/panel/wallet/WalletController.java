package panel.wallet;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Presentation fencing is independent of Service custody fencing. All gateway work runs off FX. */
public final class WalletController {
    public record State(String status, WalletView view, boolean busy, String message) { }
    private final WalletGateway gateway;
    private final Executor worker;
    private final Executor ui;
    private final Consumer<State> render;
    private State state = new State("UNAVAILABLE", null, false, "Unavailable / Not configured");
    private long requestGeneration;
    private boolean visible;
    private boolean mutation;
    private boolean unknown;
    private String pendingKey;
    private String pendingServiceGeneration;
    public WalletController(WalletGateway gateway, Executor worker, Executor ui, Consumer<State> render) {
        this.gateway = gateway; this.worker = worker; this.ui = ui; this.render = render;
    }
    public State state() { return state; }
    public void show() { visible = true; refresh(); }
    public void hide() { visible = false; requestGeneration++; }
    public void refresh() { dispatch(gateway::read, false, "LOADING"); }
    public void create() {
        if (mutation || unknown || state.view() == null || !state.status().equals(state.view().state()) || !state.view().allowedActions().canCreate()) return;
        String key = key(); pendingKey = key; pendingServiceGeneration = state.view().serviceGeneration(); dispatch(() -> gateway.create(key), true, "CREATING");
    }
    public void delete(boolean confirmed) {
        if (!confirmed || mutation || unknown || state.view() == null || !state.status().equals(state.view().state()) || !state.view().allowedActions().canDelete()) return;
        WalletView.Wallet wallet = live(); String key = key(); pendingKey = key; pendingServiceGeneration = state.view().serviceGeneration(); dispatch(() -> gateway.delete(wallet, key), true, "DELETING");
    }
    public void sign(boolean confirmed) {
        if (!confirmed || mutation || unknown || state.view() == null || !state.status().equals(state.view().state()) || !state.view().allowedActions().canSyntheticSign()) return;
        WalletView.Wallet wallet = live(); String key = key(); pendingKey = key; pendingServiceGeneration = state.view().serviceGeneration(); dispatch(() -> gateway.sign(wallet, key), true, "OPERATION_PENDING");
    }
    private WalletView.Wallet live() {
        return state.view().wallets().stream().filter(w -> !w.lifecycleState().equals("DELETED")).findFirst().orElseThrow();
    }
    private static String key() { return java.util.UUID.randomUUID().toString().replace("-", ""); }
    private void dispatch(Supplier<WalletView> action, boolean mutating, String pending) {
        if (!visible || !mutating && mutation) return;
        if (mutating) mutation = true;
        long mine = ++requestGeneration;
        state = new State(unknown ? "UNKNOWN_RESULT" : pending, state.view(), mutation,
                unknown ? "Result unknown / reconciliation required" : "Waiting for Service"); render.accept(state);
        CompletableFuture.supplyAsync(action, worker).whenComplete((view, error) -> ui.execute(() -> {
            if (mutating) {
                mutation = false;
                if (error != null) {
                    Throwable cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
                    if (!(cause instanceof WalletGateway.Unavailable u) || java.util.Set.of("SERVICE_UNAVAILABLE", "TIMEOUT_UNKNOWN_RESULT", "INTERNAL").contains(u.code)) unknown = true;
                }
            }
            if (!visible || mine != requestGeneration) { if (mutating && visible) refresh(); return; }
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException ? error.getCause() : error;
                String code = cause instanceof WalletGateway.Unavailable u ? u.code : "SERVICE_UNAVAILABLE";
                if (mutating && java.util.Set.of("SERVICE_UNAVAILABLE", "TIMEOUT_UNKNOWN_RESULT", "INTERNAL").contains(code)) unknown = true;
                String status = java.util.Set.of("ORPHAN_METADATA", "ORPHAN_KEY", "KEY_MISMATCH", "CORRUPT_METADATA", "CORRUPT_CATALOG", "INVENTORY_INCOMPLETE", "CUSTODY_QUIESCENCE_UNPROVEN", "RECONCILIATION_REQUIRED").contains(code) ? "NEEDS_ATTENTION" : code;
                state = new State(unknown ? "UNKNOWN_RESULT" : status, state.view(), false,
                        unknown ? "Result unknown / reconciliation required. Refresh only; do not repeat the operation." : code.replace('_', ' '));
            } else {
                WalletView prior = state.view();
                if (prior != null && prior.serviceGeneration().equals(view.serviceGeneration()) && view.revision() < prior.revision()) {
                    if (mutating) unknown = true;
                    state = new State(unknown ? "UNKNOWN_RESULT" : "STALE_RESPONSE", prior, false,
                            unknown ? "Result unknown / reconciliation required. Refresh only; do not repeat the operation."
                                    : "Service returned an older revision. Refresh to obtain current state.");
                    render.accept(state);
                    return;
                }
                if (unknown) {
                    boolean completed = pendingKey != null && view.operations().stream()
                            .anyMatch(o -> pendingKey.equals(o.requestId()) && o.state().equals("COMPLETE"));
                    boolean newService = pendingServiceGeneration != null && !pendingServiceGeneration.equals(view.serviceGeneration());
                    unknown = view.state().equals("UNKNOWN_RESULT") || !(completed || newService && !view.state().equals("NEEDS_ATTENTION"));
                } else unknown = view.state().equals("UNKNOWN_RESULT");
                if (!unknown && (mutating || pendingKey != null)) pendingKey = null;
                state = new State(unknown ? "UNKNOWN_RESULT" : view.state(), view, false,
                        unknown ? "Result unknown / reconciliation required" : view.state().equals("UNAVAILABLE") ? "Unavailable / Not configured" : "");
            }
            render.accept(state);
        }));
    }
}
