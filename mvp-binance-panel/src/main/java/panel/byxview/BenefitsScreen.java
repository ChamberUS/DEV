package panel.byxview;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.design.ByxBadge;
import panel.model.BenefitsSnapshot;
import panel.model.Entitlement;
import panel.model.Snapshot;
import panel.model.VerifiedWallet;
import panel.repository.GasGrantRepository;
import panel.service.EntitlementService;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.ui.View;
import panel.util.Fmt;
import panel.v2.Kit;

/**
 * BYX Benefits V2, somente leitura. Mostra o tier real e os recursos com a classificação correta: HOLD_TO_UNLOCK onde há
 * política TEST real, REFERENCE ONLY onde é só pré-visualização de interface. Pagamento e gás aparecem como estado
 * lido do repositório; nenhuma intenção de pagamento, concessão ou revogação é criada daqui, e nada tem valor financeiro.
 */
public final class BenefitsScreen implements View {
    private static final List<String> PAYMENT_STEPS = List.of("Create", "Amount", "Recipient", "Reference", "Expires", "Awaiting",
            "Confirming", "Confirmed", "Active");

    private final ByxData data;
    private final Clock clock;
    private final ScrollPane scroll;
    private final Label tierValue = Fx.label("Free", "byx-section-title");
    private final Label balanceValue = Fx.label(NetworkModel.NONE, "byx-big", "dim");
    private final List<Label> track = new ArrayList<>();
    private final Label progressText = Kit.muted("Verify a wallet to see how much BYX remains for the next tier.");
    private final VBox features = new VBox(0);
    private final VBox paymentBody = new VBox(10);
    private final VBox gasBody = new VBox(0);
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(5), e -> tick()));
    private final List<String> featuresKey = new ArrayList<>();
    private boolean inFlight;
    private long generation;
    private boolean shown;
    private int ticks;
    private String lastKey = "";

    public BenefitsScreen(Clock clock, ByxData data) {
        this.clock = clock;
        this.data = data;
        timer.setCycleCount(Timeline.INDEFINITE);

        HBox trackRow = new HBox(8);
        for (String t : BenefitsModel.TIERS) {
            Label l = Fx.label(t, "byx-tier");
            track.add(l);
            trackRow.getChildren().add(l);
        }
        VBox tier = Kit.panel(null, Kit.label("Current tier"),
                new HBox(12, tierValue, Fx.spacer(), ByxBadge.of("TEST POLICY · NOT FINAL TOKENOMICS", ByxBadge.Tone.WARNING)),
                Kit.label("BYX balance"), balanceValue, trackRow, progressText);
        tier.setId("benefits-tier");
        VBox featuresPanel = Kit.panel("Features", features);
        featuresPanel.setId("benefits-features");
        VBox never = Kit.panel(null, Fx.label("BYX never grants", "byx-section-title-sm"));
        for (String item : BenefitsModel.NEVER) {
            KvRow r = new KvRow(item);
            r.set("", false, null);
            r.getChildren().add(ByxBadge.of("NEVER", ByxBadge.Tone.NEGATIVE));
            never.getChildren().add(r);
        }
        never.setId("benefits-never");

        FlowPane payBadges = new FlowPane(8, 8, ByxBadge.of("LOCALNET", ByxBadge.Tone.WARNING), ByxBadge.of("TEST ASSET", ByxBadge.Tone.WARNING),
                ByxBadge.of("NO FINANCIAL VALUE", ByxBadge.Tone.NEUTRAL), ByxBadge.of("REFERENCE ONLY", ByxBadge.Tone.INFO));
        VBox pay = Kit.panel("Pay to unlock · payment intent", payBadges, paymentBody);
        pay.setId("benefits-payment");
        FlowPane gasBadges = new FlowPane(8, 8, ByxBadge.of("LOCALNET", ByxBadge.Tone.WARNING),
                ByxBadge.of("NO FINANCIAL VALUE", ByxBadge.Tone.NEUTRAL), ByxBadge.of("READ ONLY", ByxBadge.Tone.INFO));
        VBox gas = Kit.panel("Gas sponsorship", gasBadges, gasBody);
        gas.setId("benefits-gas");

        VBox left = new VBox(14, tier, featuresPanel, never);
        VBox right = new VBox(14, pay, gas);
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        ColumnConstraints main = new ColumnConstraints();
        main.setHgrow(Priority.ALWAYS);
        main.setMinWidth(0);
        ColumnConstraints side = new ColumnConstraints(440, 440, 440);
        grid.getColumnConstraints().addAll(main, side);
        grid.add(left, 0, 0);
        grid.add(right, 1, 0);
        VBox page = Kit.page(14);
        if (data.accountOperationsUnavailableReason() != null) page.getChildren().add(
                ByxBadge.of(data.accountOperationsUnavailableReason(), ByxBadge.Tone.NEGATIVE));
        page.getChildren().add(grid);
        scroll = Kit.scroll(page);
        render();
    }

    boolean timerRunning() {
        return timer.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    private String address() {
        if (data.accountOperationsUnavailableReason() != null) return null;
        if (!data.sessionActive()) {
            return null;
        }
        try {
            for (VerifiedWallet w : data.wallets()) {
                if (w.validAt(clock.instant())) {
                    return w.address();
                }
            }
        } catch (RuntimeException unavailable) {
            return null;
        }
        return null;
    }

    private void tick() {
        render();
        if (++ticks % 6 == 0) {
            refresh();
        }
    }

    private void refresh() {
        String addr = address();
        if (addr == null || inFlight) {
            return;
        }
        inFlight = true;
        long g = generation;
        try {
            data.refreshBenefits(addr).whenComplete((v, e) -> javafx.application.Platform.runLater(() -> {
                if (g != generation) {
                    return;
                }
                inFlight = false;
                render();
            }));
        } catch (RuntimeException e) {
            inFlight = false;
        }
    }

    private void render() {
        String addr = address();
        BenefitsSnapshot b = null;
        List<Entitlement> ents = List.of();
        EntitlementService.Progress progress = null;
        if (addr != null) {
            try {
                b = data.benefits(addr);
                ents = data.entitlements(addr);
                progress = data.progress(addr);
            } catch (RuntimeException ignored) {
                b = null;
            }
        }
        boolean verified = b != null && "VERIFIED".equals(b.walletStatus()) && b.balanceUbyx() != null;
        String tier = verified ? b.tier() : "FREE";
        Optional<GasGrantRepository.Entry> grant = addr == null ? Optional.empty() : data.gasGrant(addr);
        String key = tier + "|" + verified + "|" + (verified ? b.balanceUbyx() : "") + "|" + ents + "|" + grant + "|" + progressKey(progress);
        if (key.equals(lastKey)) {
            return;
        }
        lastKey = key;
        Fx.text(tierValue, tier.charAt(0) + tier.substring(1).toLowerCase(java.util.Locale.ROOT));
        Fx.text(balanceValue, verified ? b.formattedBalance() : NetworkModel.NONE);
        Fx.cls(balanceValue, "dim", !verified);
        for (Label l : track) {
            boolean current = l.getText().equals(tier);
            Fx.cls(l, "current", current);
            l.setAccessibleText(l.getText() + (current ? ", current tier" : ""));
        }
        Fx.text(progressText, !verified ? "Verify a wallet to see how much BYX remains for the next tier."
                : progress == null || progress.remainingUbyx() == null ? "No higher tier in this test policy."
                        : progress.remainingUbyx().signum() == 0 ? "Next tier: " + progress.nextTier()
                        : amount(progress.remainingUbyx()) + " remaining for " + progress.nextTier() + ".");
        renderFeatures(ents, verified);
        renderPayment(ents);
        renderGas(grant);
    }

    private static String progressKey(EntitlementService.Progress p) {
        return p == null ? "" : p.nextTier() + p.remainingUbyx();
    }

    private static String amount(BigInteger ubyx) {
        return new BigDecimal(ubyx, 6).toPlainString() + " BYX";
    }

    private void renderFeatures(List<Entitlement> ents, boolean verified) {
        features.getChildren().clear();
        if (ents.isEmpty()) {
            for (BenefitsModel.Feature f : BenefitsModel.FEATURES) {
                features.getChildren().add(featureRow(f.name(), BenefitsModel.backing(f.id()), verified ? "LOCKED · " + f.tier() : "NO WALLET",
                        ByxBadge.Tone.NEUTRAL));
            }
            return;
        }
        for (Entitlement e : ents) {
            features.getChildren().add(featureRow(BenefitsModel.name(e), BenefitsModel.backing(e), BenefitsModel.status(e), BenefitsModel.tone(e)));
        }
    }

    private static Node featureRow(String name, BenefitsModel.Backing backing, String status, ByxBadge.Tone tone) {
        KvRow r = new KvRow(name);
        r.set("", false, null);
        r.getChildren().addAll(ByxBadge.of(backing.text, backing == BenefitsModel.Backing.REFERENCE_ONLY ? ByxBadge.Tone.INFO : ByxBadge.Tone.NEUTRAL),
                ByxBadge.of(status, tone));
        ((HBox) r).setSpacing(8);
        return r;
    }

    private void renderPayment(List<Entitlement> ents) {
        boolean active = ents.stream().anyMatch(e -> e.id().equals("advanced_analytics") && "BYX_PAYMENT".equals(e.source()) && e.enabled());
        paymentBody.getChildren().clear();
        FlowPane steps = new FlowPane(6, 6);
        for (int i = 0; i < PAYMENT_STEPS.size(); i++) {
            boolean on = active && i == PAYMENT_STEPS.size() - 1;
            Label s = Fx.label(PAYMENT_STEPS.get(i), "byx-tier");
            Fx.cls(s, "current", on);
            steps.getChildren().add(s);
        }
        Optional<Entitlement> paid = ents.stream().filter(e -> "BYX_PAYMENT".equals(e.source()) && e.enabled()).findFirst();
        paymentBody.getChildren().addAll(steps, Kit.muted(paid.isPresent()
                ? "Advanced Analytics is unlocked by a TEST payment until " + Fmt.dateTime(paid.get().expiresAt()) + "."
                : "No intent open. Creating one reserves a reference and a deadline; nothing is charged."),
                Kit.dim("Payment intents are not created from this read-only view."));
    }

    private void renderGas(Optional<GasGrantRepository.Entry> grant) {
        gasBody.getChildren().clear();
        String eligibility = grant.map(g -> g.state()).orElse("No allowance recorded");
        gasBody.getChildren().add(row("Eligibility", eligibility, false));
        gasBody.getChildren().add(row("Quota", grant.map(g -> amount(g.limit())).orElse(NetworkModel.NONE), true));
        gasBody.getChildren().add(row("Remaining (last observed)", grant.map(g -> amount(g.remaining())).orElse(NetworkModel.NONE), true));
        gasBody.getChildren().add(row("Expires", grant.map(g -> Fmt.dateTime(g.expiration())).orElse(NetworkModel.NONE), true));
        HBox states = new HBox(8, ByxBadge.of("ELIGIBLE", ByxBadge.Tone.NEUTRAL), ByxBadge.of("ACTIVE", ByxBadge.Tone.POSITIVE),
                ByxBadge.of("REVOKED", ByxBadge.Tone.NEGATIVE));
        states.setAlignment(Pos.CENTER_LEFT);
        gasBody.getChildren().add(states);
    }

    private static Node row(String key, String value, boolean mono) {
        return Kit.row(key, value, mono);
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
        generation++;
        inFlight = false;
        lastKey = "";
        render();
        refresh();
        timer.play();
    }

    @Override
    public void onHide() {
        shown = false;
        generation++;
        inFlight = false;
        timer.stop();
    }

    public void dispose() {
        onHide();
    }
}
