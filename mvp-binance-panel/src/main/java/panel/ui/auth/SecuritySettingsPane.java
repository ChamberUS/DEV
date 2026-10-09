package panel.ui.auth;

import javafx.scene.layout.VBox;
import panel.app.AppContext;
import panel.ui.Ui;

/** Read-only summary. Sensitive actions use the existing confirmed Account screens. */
public final class SecuritySettingsPane extends VBox {
    public SecuritySettingsPane(AppContext ctx) {
        super(14);
        var security = Ui.button("Open Security", "ghost");
        security.setOnAction(e -> ctx.navigate.accept("t-security"));
        var devices = Ui.button("Review sessions and devices", "ghost");
        devices.setOnAction(e -> ctx.navigate.accept("t-sessions"));
        getChildren().addAll(
                Ui.card("TWO-FACTOR PROVIDERS",
                        Ui.kv("Delivery", "Local service (provider secrets never reach this app)"),
                        Ui.kv("Status", ctx.adminAccess.twoFactorConfigured() ? "CONFIGURED" : "NOT_CONFIGURED"),
                        Ui.kv("AdminSession timeout", "Set by the service"), security),
                Ui.card("TRUSTED DEVICES", Ui.label("Review and confirm device revocation in Sessions and devices.", "muted"), devices));
    }
}
