package panel.wallet;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import panel.v2.Kit;

/** Public lifecycle card. Confirmation is explicit and Service still rechecks every request. */
public final class WalletPane extends VBox {
    private static final java.util.concurrent.Executor WORKER = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wallet-service-client"); t.setDaemon(true); return t;
    });
    private final Label status = panel.design.ByxBadge.of("Unavailable / Not configured", panel.design.ByxBadge.Tone.WARNING);
    private final Label address = panel.tradeview.Fx.label("", "byx-data");
    private final Label identity = Kit.muted("");
    private final Label message = Kit.muted("");
    private final Label operation = Kit.muted("");
    private final Label warning = new Label("LOCAL_ONLY_NO_RECOVERY · This wallet is local. Recovery is not supported in this version. Loss of the device or key may be permanent.");
    private final Button refresh = new Button("Refresh");
    private final Button create = new Button("Create synthetic QA");
    private final Button delete = new Button("Delete synthetic QA");
    private final Button sign = new Button("Synthetic sign QA");
    private final HBox actions = new HBox(10, create, sign, delete);
    private final BooleanSupplier admin;
    private final WalletController controller;
    public WalletPane(WalletGateway gateway, BooleanSupplier admin) {
        this.admin = admin;
        controller = new WalletController(gateway, WORKER, Platform::runLater, this::render);
        setSpacing(12); setId("wallet-lifecycle-card");
        for (Label label : java.util.List.of(status, address, identity, message, warning, operation)) { label.setWrapText(true); label.setMaxWidth(Double.MAX_VALUE); }
        warning.getStyleClass().add("byx-body"); warning.setStyle("-fx-text-fill: -byx-warning;");
        for (Button button : java.util.List.of(refresh, create, delete, sign)) button.getStyleClass().add("byx-btn");
        refresh.getStyleClass().add("secondary"); sign.getStyleClass().add("secondary"); delete.getStyleClass().add("danger-outline");
        getChildren().add(Kit.panel("Local wallet", status, identity, address, warning, message, operation, refresh, actions));
        actions.setVisible(false); actions.setManaged(false); warning.setVisible(false); warning.setManaged(false);
        refresh.setOnAction(e -> controller.refresh());
        create.setOnAction(e -> { if (confirm("Create synthetic QA wallet", "Local QA wallet only. Recovery is unsupported; loss may be permanent.")) controller.create(); });
        delete.setOnAction(e -> {
            WalletView view = controller.state().view();
            if (view == null) return;
            WalletView.Wallet wallet = view.wallets().stream().filter(w -> !w.lifecycleState().equals("DELETED")).findFirst().orElse(null);
            if (wallet != null && confirm("Delete synthetic QA wallet", wallet.walletId() + "\n" + wallet.address()
                    + "\nNo recovery. Deletion is irreversible in QA.")) controller.delete(true);
        });
        sign.setOnAction(e -> { if (confirm("Synthetic signature QA", "Sign the Service's synthetic diagnostic request. Broadcast is prohibited.")) controller.sign(true); });
    }
    static boolean qaBuild() {
        try (var input = WalletPane.class.getResourceAsStream("/panel/wallet-capability.txt")) {
            return input != null && new String(input.readNBytes(64), java.nio.charset.StandardCharsets.UTF_8).trim().equals("SYNTHETIC_QA");
        } catch (java.io.IOException e) { return false; }
    }
    private boolean confirm(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, content, ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle(title); alert.setHeaderText(title);
        if (getScene() != null) alert.initOwner(getScene().getWindow());
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
    private void render(WalletController.State state) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX_THREAD_REQUIRED");
        panel.tradeview.Fx.tone(status, state.status().equals("READY") ? "tone-pos" :
                java.util.Set.of("CREATING", "DELETING", "LOADING", "OPERATION_PENDING").contains(state.status()) ? "tone-inf" : "tone-wrn",
                "tone-pos", "tone-inf", "tone-wrn", "tone-neg");
        status.setText(state.status().replace('_', ' ')); message.setText(state.message());
        WalletView view = state.view();
        boolean qa = view != null && view.capability().equals("SYNTHETIC_QA");
        boolean diagnostic = qa && qaBuild() && admin.getAsBoolean();
        actions.setVisible(diagnostic); actions.setManaged(diagnostic);
        operation.setVisible(diagnostic && !view.operations().isEmpty()); operation.setManaged(operation.isVisible());
        if (operation.isVisible() && (state.busy() || state.status().equals("UNKNOWN_RESULT"))) {
            operation.setText(state.status().equals("UNKNOWN_RESULT") ? "Result unknown / reconciliation required" : "Operation pending · " + state.status());
        } else if (operation.isVisible()) {
            WalletView.Operation last = view.operations().getFirst();
            String result = last.action().equals("SIGN") && last.state().equals("COMPLETE") ? "Synthetic signature: SUCCESS" : last.action() + " · " + last.state();
            operation.setText(result + "\nOperation: " + last.operationId() + (last.publicHash() == null ? "" : "\nPublic hash: " + last.publicHash()));
        } else operation.setText("");
        warning.setVisible(qa && !view.wallets().isEmpty()); warning.setManaged(warning.isVisible());
        WalletView.Wallet wallet = view == null ? null : view.wallets().stream().filter(w -> !w.lifecycleState().equals("DELETED")).findFirst().orElse(null);
        if (state.status().equals("NEEDS_ATTENTION") && state.message().isEmpty()) {
            message.setText(wallet != null && !wallet.healthState().equals("HEALTHY") ? "Needs attention · " + wallet.healthState().replace('_', ' ')
                    : "Needs attention. Wallet operations are disabled.");
        }
        identity.setText(wallet == null ? "" : "WalletId: " + wallet.walletId());
        address.setText(wallet == null || wallet.address() == null ? "" : wallet.address());
        boolean fresh = view != null && state.status().equals(view.state());
        create.setDisable(!fresh || state.busy() || !diagnostic || !view.allowedActions().canCreate() || state.status().equals("UNKNOWN_RESULT"));
        delete.setDisable(!fresh || state.busy() || !diagnostic || !view.allowedActions().canDelete() || state.status().equals("UNKNOWN_RESULT"));
        sign.setDisable(!fresh || state.busy() || !diagnostic || !view.allowedActions().canSyntheticSign() || state.status().equals("UNKNOWN_RESULT"));
        refresh.setDisable(state.busy());
    }
    public void refresh() { controller.refresh(); }
    public void onShow() { controller.show(); }
    public void onHide() { controller.hide(); }
}
