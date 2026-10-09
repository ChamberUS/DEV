package panel.accountview;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import panel.auth.TrustedDeviceService;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.design.ByxOverlayHost;
import panel.design.ByxRegion;
import panel.design.RegionState;
import panel.model.Snapshot;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.ui.View;
import panel.util.Fmt;
import panel.v2.Kit;

/**
 * Sessions and devices V2. O app não tem gestão de sessões remotas: a lista de outras sessões é UNAVAILABLE e nada é
 * inventado. A sessão local atual aparece com dados reais (início, método). Dispositivos confiáveis são reais (só admin);
 * revogar sempre pergunta e a linha só muda depois que o serviço confirma.
 */
public final class SessionsScreen implements View {
    private final AccountRead read = new AccountRead();
    private boolean shown;
    private boolean revoking;
    private long generation;
    private final AccountData data;
    private final MotionService motion;
    private final Clock clock;
    private final Supplier<ByxOverlayHost> overlay;
    private final ScrollPane scroll;
    private final VBox body = new VBox(14);
    private final Kit.Segmented tabs;
    private ByxRegion region;

    public SessionsScreen(MotionService motion, Clock clock, AccountData data, Supplier<ByxOverlayHost> overlay) {
        this.motion = motion;
        this.clock = clock;
        this.data = data;
        this.overlay = overlay;
        tabs = new Kit.Segmented(List.of("Sessions", "Trusted devices"), "Sessions", t -> render());
        VBox page = Kit.page(14);
        page.getChildren().addAll(Kit.header("Sessions and devices", "Where you are signed in.", tabs), body);
        scroll = Kit.scroll(page);
        render();
    }

    String tab() {
        return tabs.selected();
    }

    Kit.Segmented tabs() {
        return tabs;
    }

    private void render() {
        read.cancel();
        generation++;
        if (region != null) {
            region.dispose();
            region = null;
        }
        body.getChildren().clear();
        if ("Sessions".equals(tabs.selected())) {
            renderSessions();
        } else {
            renderDevices();
        }
    }

    private void renderSessions() {
        var user = data.user();
        VBox current = Kit.panel("This device");
        if (user.isEmpty()) {
            current.getChildren().add(Kit.muted("No active session."));
        } else {
            current.getChildren().addAll(Kit.row("Status", "CURRENT", false), Kit.row("Signed in", data.signedInAt() == null ? AccountModel.NOT_PROVIDED
                    : Fmt.dateTime(data.signedInAt()), false), Kit.row("Method", "Password", false),
                    Kit.row("Account", "@" + user.get().username(), false));
        }
        region = new ByxRegion("Other sessions", EnumSet.of(RegionState.UNAVAILABLE), motion);
        region.setState(RegionState.UNAVAILABLE, ByxRegion.Detail.of("Other sessions unavailable",
                "This build has no session management service, so sessions on other devices can not be listed or ended."));
        region.setMinHeight(160);
        VBox other = Kit.panel(null, Kit.titled("Other sessions", ByxBadge.availability(ByxBadge.Availability.UNAVAILABLE)), region);
        other.setId("sessions-other");
        body.getChildren().addAll(current, other);
    }

    private void renderDevices() {
        VBox panel = Kit.panel("Trusted devices");
        panel.setId("sessions-devices");
        Label loading = Kit.muted("Loading trusted devices…");
        loading.setId("trusted-devices-loading");
        panel.getChildren().add(loading);
        body.getChildren().add(panel);
        if (!shown) return;
        read.load(data::trustedDevices, (devices, failure) -> {
            panel.getChildren().removeIf(n -> n instanceof Label l && "trusted-devices-loading".equals(l.getId()));
            if (failure != null) {
                region = new ByxRegion("Trusted devices", EnumSet.of(RegionState.UNAVAILABLE), motion);
                region.setState(RegionState.UNAVAILABLE, ByxRegion.Detail.of("Trusted devices unavailable",
                        "Trusted devices require an admin session and an available local service."));
                region.setMinHeight(160);
                panel.getChildren().add(region);
                return;
            }
            displayDevices(panel, devices);
        });
    }

    private void displayDevices(VBox panel, List<TrustedDeviceService.Device> devices) {
        if (devices.isEmpty()) {
            panel.getChildren().add(Kit.muted("No trusted devices. Trusting a Mac during admin verification adds it here."));
        }
        for (TrustedDeviceService.Device d : devices) {
            String status = d.status(clock.instant());
            VBox copy = new VBox(2, Fx.label(d.displayName(), "byx-section-title-sm"), Kit.dim("Trusted since " + Fmt.dateTime(d.createdAt())
                    + " · last used " + Fmt.dateTime(d.lastUsedAt()) + " · expires " + Fmt.dateTime(d.expiresAt())));
            HBox.setHgrow(copy, Priority.ALWAYS);
            ByxButton revoke = new ByxButton("Revoke", ByxButton.Variant.DANGER_OUTLINE, motion);
            revoke.small();
            revoke.setDisable(!"ACTIVE".equals(status));
            revoke.setOnAction(e -> confirmRevoke(d));
            HBox row = new HBox(12, copy, ByxBadge.of(status, "ACTIVE".equals(status) ? ByxBadge.Tone.POSITIVE : ByxBadge.Tone.NEUTRAL), revoke);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("byx-desk-row");
            panel.getChildren().add(row);
        }
    }

    private void confirmRevoke(TrustedDeviceService.Device d) {
        ByxOverlayHost host = overlay.get();
        if (host == null || revoking || host.openDialogs() > 0) {
            return;
        }
        host.confirm("Revoke trusted device", "This Mac will ask for a verification code again and your admin session ends.", "Revoke", true, () -> {
            if (revoking) return;
            revoking = true;
            long ticket = generation;
            body.setDisable(true);
            Thread worker = new Thread(() -> {
                boolean failed = false;
                try { data.revokeDevice(d.id()); } catch (RuntimeException ex) { failed = true; }
                boolean failure = failed;
                javafx.application.Platform.runLater(() -> {
                    revoking = false;
                    body.setDisable(false);
                    if (!shown || ticket != generation) return;
                    if (failure) host.toast(ByxOverlayHost.ToastKind.ERROR, "Could not revoke the device. Refresh to check its status.");
                    render(); // read-back only after the service replied; never optimistic removal
                });
            }, "trusted-device-revoke");
            worker.setDaemon(true);
            worker.start();
        });
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
    }

    @Override
    public void onShow() {
        shown = true;
        render();
    }

    @Override
    public void onHide() {
        shown = false;
        generation++;
        read.cancel();
        if (region != null) {
            region.dispose();
            region = null;
        }
    }

    public void dispose() {
        onHide();
        read.close();
    }
}
