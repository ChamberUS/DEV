package panel.byxview;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.design.ByxBadge;
import panel.design.ByxButton;
import panel.model.BenefitsSnapshot;
import panel.model.ByxSnapshot;
import panel.model.Snapshot;
import panel.model.VerifiedWallet;
import panel.motion.MotionService;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.ui.View;
import panel.util.Fmt;
import panel.v2.Kit;

/**
 * BYX Wallet V2, somente leitura. Mostra a carteira verificada (ou o endereço observado), saldo TEST, tier e histórico
 * de verificações do repositório local. Nenhuma carteira, endereço, saldo ou transação é inventado, e a chave privada
 * nunca passa pelo app. Verificar ou desvincular a posse continua no fluxo existente (rota {@code t-wallet-verify}).
 */
public final class WalletScreen implements View {
    public static final String VERIFY_ROUTE = "t-wallet-verify";

    private final ByxData data;
    private final panel.wallet.WalletPane lifecycle;
    private final Clock clock;
    private final Consumer<String> navigate;
    private final ScrollPane scroll;
    private final Label stateBadge = ByxBadge.of("", ByxBadge.Tone.NEUTRAL);
    private final Label watchBadge = ByxBadge.of("OFF", ByxBadge.Tone.NEUTRAL);
    private final Label ownershipBadge = ByxBadge.of("REQUIRED", ByxBadge.Tone.NEUTRAL);
    private final Label address = Fx.label("", "byx-mono");
    private final KvRow network = new KvRow("Network");
    private final KvRow balance = new KvRow("BYX balance · TEST");
    private final KvRow tier = new KvRow("Current tier");
    private final KvRow lastVerification = new KvRow("Last verification");
    private final Label note = Kit.muted("");
    private final VBox history = new VBox(0);
    private final ByxButton manage;
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(5), e -> tick()));
    private WalletModel.State state;
    private List<String> historyKey = List.of();
    private boolean inFlight;
    private boolean everLoaded;
    private boolean failed;
    private int ticks;
    private long generation;
    private boolean shown;

    public WalletScreen(MotionService motion, Clock clock, ByxData data, Consumer<String> navigate) {
        this.clock = clock;
        this.data = data;
        lifecycle = data.walletLifecycleGateway() == null ? null : new panel.wallet.WalletPane(data.walletLifecycleGateway(), data::admin);
        this.navigate = navigate;
        timer.setCycleCount(Timeline.INDEFINITE);

        VBox steps = new VBox(14,
                step("1", true, "Watch address", "Read-only view of an address. Anyone can add one.", watchBadge, false),
                step("2", false, "Verified ownership", "You sign a challenge with the wallet. Proves control, moves nothing.", ownershipBadge, false),
                step("3", false, "External signer / private key", "For ownership proofs, the key stays in your external wallet.",
                        ByxBadge.of("OUTSIDE APP", ByxBadge.Tone.NEUTRAL), true));
        manage = new ByxButton("Manage verification", ByxButton.Variant.SECONDARY, motion);
        manage.setOnAction(e -> navigate.accept(VERIFY_ROUTE));
        VBox card = Kit.panel(null, Kit.titled("External ownership", stateBadge), address, network, balance, tier, lastVerification, note,
                new HBox(10, manage));
        card.setId("wallet-card");
        VBox histPanel = Kit.panel("Verification history", history);
        histPanel.setId("wallet-history");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        ColumnConstraints main = new ColumnConstraints();
        main.setHgrow(Priority.ALWAYS);
        main.setMinWidth(0);
        ColumnConstraints side = new ColumnConstraints(440, 440, 440);
        grid.getColumnConstraints().addAll(main, side);
        grid.add(steps, 0, 0);
        grid.add(card, 1, 0);
        grid.add(histPanel, 0, 1, 2, 1);
        VBox page = Kit.page(14);
        if (lifecycle != null) page.getChildren().add(lifecycle);
        if (data.accountOperationsUnavailableReason() != null) page.getChildren().add(
                ByxBadge.of(lifecycle == null ? data.accountOperationsUnavailableReason() : "External ownership verification unavailable", ByxBadge.Tone.NEGATIVE));
        page.getChildren().add(grid);
        scroll = Kit.scroll(page);
        render();
    }

    private static Node step(String n, boolean on, String title, String text, Label badge, boolean dashed) {
        Label num = Fx.label(n, "byx-step-num-text");
        StackPane circle = new StackPane(num);
        circle.getStyleClass().add("byx-step-num");
        if (on) {
            circle.getStyleClass().add("on");
        }
        VBox copy = new VBox(2, Fx.label(title, "byx-section-title-sm"), Kit.muted(text));
        copy.setMinWidth(0);
        badge.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox row = new HBox(16, circle, copy, Fx.spacer(), badge);
        HBox.setHgrow(copy, Priority.ALWAYS);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("byx-step");
        if (dashed) {
            row.getStyleClass().add("dashed");
        }
        return row;
    }

    WalletModel.State state() {
        return state;
    }

    boolean timerRunning() {
        return timer.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    private void tick() {
        render();
        if (++ticks % 6 == 0) {
            if (lifecycle != null) lifecycle.refresh();
            refresh();
        }
    }

    private void refresh() {
        if (data.accountOperationsUnavailableReason() != null) return;
        String addr = selected();
        if (addr == null || inFlight || !data.sessionActive()) {
            return;
        }
        inFlight = true;
        long g = generation;
        try {
            data.refreshBenefits(addr).whenComplete((v, error) -> javafx.application.Platform.runLater(() -> {
                if (g != generation) {
                    return; // tela escondida/descartada ou outra sessão: resultado tardio ignorado
                }
                inFlight = false;
                everLoaded = true;
                failed = error != null;
                render();
            }));
        } catch (RuntimeException e) {
            inFlight = false;
            failed = true;
        }
    }

    private List<VerifiedWallet> wallets() {
        try {
            return data.sessionActive() ? data.wallets() : null;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private String selected() {
        List<VerifiedWallet> ws = wallets();
        if (ws != null) {
            for (VerifiedWallet w : ws) {
                if (w.revokedAt() == null) {
                    return w.address();
                }
            }
        }
        return data.network().address();
    }

    private void render() {
        List<VerifiedWallet> ws = wallets();
        ByxSnapshot net = data.network();
        boolean readable = ws != null;
        boolean linked = readable && ws.stream().anyMatch(w -> w.validAt(clock.instant()));
        boolean answering = !"UNKNOWN".equals(net.connection()) || net.height() != null;
        WalletModel.State st = WalletModel.resolve(readable, linked, inFlight, everLoaded, failed, answering);
        if (st != state) {
            state = st;
            Fx.text(stateBadge, st.text);
            stateBadge.getStyleClass().removeAll("tone-pos", "tone-wrn", "tone-neg", "tone-inf");
            String tone = switch (st.tone) {
                case POSITIVE -> "tone-pos";
                case WARNING -> "tone-wrn";
                case NEGATIVE -> "tone-neg";
                case INFO -> "tone-inf";
                default -> null;
            };
            if (tone != null) {
                stateBadge.getStyleClass().add(tone);
            }
        }
        String addr = selected();
        boolean watching = addr != null;
        Fx.text(address, watching ? addr : "No wallet linked");
        Fx.cls(address, "dim", !watching);
        Fx.text(watchBadge, watching ? "ON" : "OFF");
        Fx.text(ownershipBadge, linked ? "VERIFIED" : "REQUIRED");
        network.set(NetworkModel.environment(net), false, null);
        BenefitsSnapshot b = null;
        if (readable && addr != null) {
            try {
                b = data.benefits(addr);
            } catch (RuntimeException ignored) {
                b = null;
            }
        }
        boolean chainOk = b != null && "VERIFIED".equals(b.walletStatus()) && b.balanceUbyx() != null;
        balance.set(chainOk ? b.formattedBalance() : NetworkModel.NONE, true, chainOk ? null : "dim");
        tier.set(chainOk ? b.tier() : NetworkModel.NONE, false, chainOk ? null : "dim");
        VerifiedWallet latest = readable ? ws.stream().filter(w -> w.address().equals(addr)).findFirst().orElse(null) : null;
        lastVerification.set(latest == null ? "Never" : Fmt.dateTime(latest.lastVerifiedAt()), false, latest == null ? "dim" : null);
        String message = switch (st) {
            case UNAVAILABLE -> "Unavailable · LOCALNET is not configured or there is no session.";
            case ERROR -> "The balance could not be refreshed. The last verified state is shown.";
            case CONNECTING -> "Waiting for the LOCALNET node to answer.";
            case LOADING -> "Reading the wallet from the node.";
            case LINKED -> chainOk ? "" : "Chain offline or stale: balance and tier are not shown.";
            case NOT_LINKED -> "No wallet is linked. A watch address shows a balance only after ownership is verified.";
        };
        Fx.text(note, message);
        Fx.shown(note, !message.isEmpty());
        manage.setDisable(st == WalletModel.State.UNAVAILABLE);
        renderHistory(readable ? ws : List.of());
    }

    static String shorten(String a) {
        return a == null ? NetworkModel.NONE : a.length() <= 20 ? a : a.substring(0, 12) + "…" + a.substring(a.length() - 6);
    }

    private void renderHistory(List<VerifiedWallet> ws) {
        List<String> key = new ArrayList<>();
        for (VerifiedWallet w : ws) {
            key.add(w.address() + "|" + w.lastVerifiedAt() + "|" + w.revokedAt() + "|" + w.validAt(clock.instant()));
        }
        if (key.equals(historyKey)) {
            return;
        }
        historyKey = key;
        history.getChildren().clear();
        if (ws.isEmpty()) {
            history.getChildren().add(Kit.muted("No verification yet. Completed ownership checks appear here with date and network. "
                    + "BYX-MVP stores the address and the signature result, never the key."));
            return;
        }
        for (VerifiedWallet w : ws) {
            String status = w.revokedAt() != null ? "REVOKED" : w.validAt(clock.instant()) ? "VERIFIED" : "EXPIRED";
            Label badge = ByxBadge.of(status, "VERIFIED".equals(status) ? ByxBadge.Tone.POSITIVE : ByxBadge.Tone.WARNING);
            KvRow r = new KvRow(Fmt.dateTime(w.lastVerifiedAt()) + " · LOCALNET");
            r.set(shorten(w.address()), true, null);
            r.getChildren().add(badge);
            history.getChildren().add(r);
        }
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        if (shown) {
            render();
        }
    }

    @Override
    public void onShow() {
        shown = true;
        if (lifecycle != null) lifecycle.onShow();
        generation++;
        inFlight = false;
        render();
        refresh();
        timer.play();
    }

    @Override
    public void onHide() {
        shown = false;
        if (lifecycle != null) lifecycle.onHide();
        generation++; // respostas em voo não tocam mais a tela
        inFlight = false;
        timer.stop();
    }

    public void dispose() {
        onHide();
    }
}
