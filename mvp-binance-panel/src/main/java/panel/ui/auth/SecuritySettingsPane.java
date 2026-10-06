package panel.ui.auth;

import javafx.scene.control.Button;
import javafx.scene.layout.*;
import panel.app.AppContext;
import panel.auth.*;
import panel.ui.Ui;

public final class SecuritySettingsPane extends VBox {
    public SecuritySettingsPane(AppContext ctx){
        super(14);ctx.adminAccess.requireAdmin();
        VBox providers=Ui.card("TWO-FACTOR PROVIDERS",Ui.label("Checking providers…","muted"));
        VBox devices=Ui.card("TRUSTED DEVICES");
        var entries=ctx.trustedDevices.list();
        long active=entries.stream().filter(d->"ACTIVE".equals(d.status(java.time.Instant.now()))).count();
        devices.getChildren().add(Ui.kv("Trusted Devices",active+" active"));
        for(var device:entries){
            Button revoke=Ui.button("Revoke","danger");revoke.setDisable(!"ACTIVE".equals(device.status(java.time.Instant.now())));
            revoke.setOnAction(e->{ctx.trustedDevices.revoke(device.id());ctx.navigate.accept("t-profile");});
            devices.getChildren().add(Ui.card(device.displayName(),Ui.kv("Status",device.status(java.time.Instant.now())),
                    Ui.kv("Trusted since",device.createdAt().toString()),Ui.kv("Last used",device.lastUsedAt().toString()),Ui.kv("Expires",device.expiresAt().toString()),revoke));
        }
        if(entries.isEmpty())devices.getChildren().add(Ui.label("No trusted devices","muted"));
        getChildren().addAll(providers,devices);
        providers.getChildren().setAll(Ui.label("TWO-FACTOR PROVIDERS","card-title"),
                Ui.kv("Delivery","Local service (provider secrets never reach this app)"),
                Ui.kv("Status",ctx.adminAccess.twoFactorConfigured()?"CONFIGURED":"NOT_CONFIGURED"),
                Ui.kv("AdminSession timeout","Set by the service (sliding, with activity)"));
    }
}
