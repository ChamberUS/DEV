package panel.byxview;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import panel.design.ByxBadge;
import panel.design.ByxBanner;
import panel.design.ByxButton;
import panel.i18n.Strings;
import panel.model.BenefitsSnapshot;
import panel.model.Entitlement;
import panel.model.Snapshot;
import panel.model.VerifiedWallet;
import panel.motion.MotionService;
import panel.repository.GasGrantRepository;
import panel.service.EntitlementService;
import panel.shell.avatar.Operations;
import panel.tradeview.Fx;
import panel.tradeview.KvRow;
import panel.ui.View;
import panel.util.Fmt;
import panel.v2.Kit;

/**
 * BYX Benefits (B04), read-only. It answers: what exists, what this account can use, what is missing, what is planned and which condition
 * prevents verification. The page only READS and maps real conditions to words ({@link BenefitsState}); it never grants, charges, links a
 * wallet or changes a role. All reads run off the FX thread; stale results are dropped; Refresh reports a real operation to the avatar.
 */
public final class BenefitsScreen implements View {
    private static final Executor SYNC = Runnable::run;

    private record Loaded(BenefitsState.Model model, EntitlementService.Progress progress, Optional<GasGrantRepository.Entry> grant, boolean failed) {
    }

    private final ByxData data;
    private final Clock clock;
    private final Executor io;
    private final Executor ui;
    private final ExecutorService ownedIo;
    private final Supplier<Operations> operations;
    private final ScrollPane scroll;
    private final GridPane grid = new GridPane();
    private final VBox left = new VBox(14);
    private final VBox right = new VBox(14);
    private final VBox bannerBox = new VBox(8);
    private final VBox account = new VBox(10);
    private final VBox capabilities = new VBox(0);
    private final VBox technical = new VBox(8);
    private final ByxButton refresh;
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(5), e -> tick()));
    private BenefitsState.Model model;
    private boolean shown;
    private boolean twoColumns = true;
    private boolean inFlight;
    private boolean refreshing;
    private long generation;
    private int ticks;
    private String lastKey = "";

    public BenefitsScreen(Clock clock, ByxData data) {
        this(clock, data, null, null, () -> Operations.NONE, new MotionService());
    }

    /** io/ui null = the real executors (a private worker and the FX thread); tests pass synchronous ones. */
    public BenefitsScreen(Clock clock, ByxData data, Executor io, Executor ui, Supplier<Operations> operations, MotionService motion) {
        this.clock = clock;
        this.data = data;
        this.operations = operations;
        if (io == null) {
            this.ownedIo = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "benefits-read");
                t.setDaemon(true);
                return t;
            });
            this.io = ownedIo;
        } else {
            this.ownedIo = null;
            this.io = io;
        }
        this.ui = ui != null ? ui : Platform::runLater;
        timer.setCycleCount(Timeline.INDEFINITE);

        refresh = new ByxButton(Strings.get("ben.refresh"), ByxButton.Variant.SECONDARY, motion);
        refresh.setId("benefits-refresh");
        refresh.setOnAction(e -> refreshNow());
        HBox header = Kit.header(Strings.get("page.benefits"), Strings.get("ben.sub"), refresh);
        header.setId("benefits-header");

        VBox what = Kit.panel(Strings.get("ben.what"), Kit.muted(Strings.get("ben.what.b")),
                badges(ByxBadge.of("LOCALNET", ByxBadge.Tone.WARNING), ByxBadge.of("TEST ASSET", ByxBadge.Tone.WARNING),
                        ByxBadge.of(Strings.get("ben.nofin").toUpperCase(java.util.Locale.ROOT), ByxBadge.Tone.NEUTRAL)));
        what.setId("benefits-what");
        VBox today = Kit.panel(Strings.get("ben.today"), account);
        today.setId("benefits-tier");
        VBox featuresPanel = Kit.panel(Strings.get("ben.inbeta"), Kit.dim(Strings.get("ben.inbeta.s")), capabilities);
        featuresPanel.setId("benefits-features");
        VBox planned = Kit.panel(Strings.get("ben.planned"), Kit.dim(Strings.get("ben.planned.b")),
                plannedRow(Strings.get("ben.pl.tiers"), Strings.get("ben.pl.tiers.d")), plannedRow(Strings.get("ben.pl.bot"), Strings.get("ben.pl.bot.d")),
                plannedRow(Strings.get("ben.pl.research"), Strings.get("ben.pl.research.d")));
        planned.setId("benefits-planned");
        VBox never = Kit.panel(Strings.get("ben.never"), Kit.dim(Strings.get("ben.never.b")));
        for (String item : BenefitsModel.NEVER) {
            KvRow r = new KvRow(item);
            r.set("", false, null);
            r.getChildren().add(ByxBadge.of(Strings.get("never.tag").toUpperCase(java.util.Locale.ROOT), ByxBadge.Tone.NEGATIVE));
            never.getChildren().add(r);
        }
        never.setId("benefits-never");
        VBox tech = Kit.panel(Strings.get("ben.tech"), technical);
        tech.setId("benefits-technical");

        left.getChildren().addAll(what, today, featuresPanel);
        right.getChildren().addAll(planned, never, tech);
        grid.setHgap(14);
        grid.setVgap(14);
        grid.add(left, 0, 0);
        grid.add(right, 1, 0);
        applyColumns(true);

        VBox page = Kit.page(14);
        page.getChildren().addAll(header, bannerBox, grid, Kit.dim(Strings.get("ben.foot")));
        page.widthProperty().addListener((o, a, w) -> {
            boolean two = w.doubleValue() >= 1000;
            if (two != twoColumns) {
                applyColumns(two);
            }
        });
        scroll = Kit.scroll(page);
        scroll.setId("benefits");
        // First paint is immediate and honest: nothing has been read yet, so nothing is claimed.
        showLoading();
        reload();
    }

    private static HBox badges(Node... n) {
        HBox h = new HBox(8, n);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Node plannedRow(String name, String detail) {
        Label title = Fx.label(name, "byx-desk-row-value");
        title.setWrapText(true); // long names wrap instead of truncating
        title.setMinHeight(Region.USE_PREF_SIZE);
        VBox v = new VBox(2, title, Kit.dim(detail));
        v.setMinWidth(0);
        HBox.setHgrow(v, Priority.ALWAYS);
        Label pill = ByxBadge.of(Strings.get("st.planned").toUpperCase(java.util.Locale.ROOT), ByxBadge.Tone.NEUTRAL);
        pill.setMinWidth(Region.USE_PREF_SIZE); // never ellipsized
        HBox h = new HBox(8, v, Fx.spacer(), pill);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private void applyColumns(boolean two) {
        twoColumns = two;
        grid.getColumnConstraints().clear();
        if (two) {
            ColumnConstraints a = new ColumnConstraints();
            a.setHgrow(Priority.ALWAYS);
            a.setPercentWidth(58);
            a.setMinWidth(0);
            ColumnConstraints b = new ColumnConstraints();
            b.setHgrow(Priority.ALWAYS);
            b.setPercentWidth(42);
            b.setMinWidth(0);
            grid.getColumnConstraints().addAll(a, b);
            GridPane.setConstraints(left, 0, 0);
            GridPane.setConstraints(right, 1, 0);
        } else {
            ColumnConstraints a = new ColumnConstraints();
            a.setHgrow(Priority.ALWAYS);
            a.setPercentWidth(100);
            a.setMinWidth(0);
            grid.getColumnConstraints().add(a);
            GridPane.setConstraints(left, 0, 0);
            GridPane.setConstraints(right, 0, 1);
        }
    }

    // ------------------------------------------------------------------ reading (off the FX thread)

    boolean timerRunning() {
        return timer.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    private void tick() {
        reload();
        if (++ticks % 6 == 0) {
            backgroundRefresh();
        }
    }

    private void reload() {
        if (inFlight || ownedIo != null && ownedIo.isShutdown()) {
            return;
        }
        inFlight = true;
        long g = generation;
        io.execute(() -> {
            Loaded l = read();
            ui.execute(() -> {
                if (g != generation) {
                    return; // the screen was hidden/disposed meanwhile: a late result never paints
                }
                inFlight = false;
                apply(l);
            });
        });
    }

    /** Blocking reads of the existing read-only sources. Runs on the io executor, never on the FX thread. */
    private Loaded read() {
        try {
            String reason = data.accountOperationsUnavailableReason();
            boolean session = data.sessionActive();
            BenefitsState.WalletRead wallet;
            if (!session || reason != null) {
                wallet = new BenefitsState.WalletRead.Denied();
            } else {
                try {
                    String address = null;
                    for (VerifiedWallet w : data.wallets()) {
                        if (w.validAt(clock.instant())) {
                            address = w.address();
                            break;
                        }
                    }
                    wallet = address == null ? new BenefitsState.WalletRead.None() : new BenefitsState.WalletRead.Linked(address);
                } catch (panel.security.AccessDeniedException denied) {
                    wallet = new BenefitsState.WalletRead.Denied();
                } catch (RuntimeException unavailable) {
                    wallet = new BenefitsState.WalletRead.Unavailable();
                }
            }
            BenefitsSnapshot b = null;
            List<Entitlement> ents = List.of();
            EntitlementService.Progress progress = null;
            Optional<GasGrantRepository.Entry> grant = Optional.empty();
            if (wallet instanceof BenefitsState.WalletRead.Linked l) {
                try {
                    b = data.benefits(l.address());
                    ents = data.entitlements(l.address());
                    progress = data.progress(l.address());
                    grant = data.gasGrant(l.address());
                } catch (RuntimeException unreadable) {
                    b = null;
                }
            }
            boolean verified = b != null && "VERIFIED".equals(b.walletStatus());
            BenefitsState.Inputs in = new BenefitsState.Inputs(session, reason, wallet, nodeReachable(), verified, b == null ? null : b.balanceUbyx(),
                    b == null ? null : b.tier(), ents, reason == null);
            return new Loaded(BenefitsState.resolve(in), progress, grant, false);
        } catch (RuntimeException e) {
            return new Loaded(null, null, Optional.empty(), true);
        }
    }

    private boolean nodeReachable() {
        var net = data.network();
        if (net == null) {
            return false;
        }
        return switch (NetworkModel.state(net)) {
            case HEALTHY, SYNCING, STALE, DEGRADED -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ refresh = a REAL operation (shown by the avatar)

    private void refreshNow() {
        if (refreshing || !shown && ownedIo != null && ownedIo.isShutdown()) {
            return;
        }
        refreshing = true;
        refresh.setLoading(true);
        Operations ops = operations.get();
        Operations.Token token = ops.begin("benefits.refresh");
        long g = generation;
        io.execute(() -> {
            java.util.concurrent.CompletableFuture<?> pending = null;
            boolean ok = true;
            try {
                String address = currentAddress();
                if (address != null) {
                    pending = data.refreshBenefits(address);
                }
            } catch (RuntimeException e) {
                ok = false;
            }
            boolean launched = ok;
            java.util.concurrent.CompletableFuture<?> f = pending;
            Runnable finish = () -> ui.execute(() -> {
                refreshing = false;
                refresh.setLoading(false);
                Loaded l = read();
                boolean good = launched && !l.failed();
                ops.end(token, good, good ? null : "BENEFITS_REFRESH_FAILED");
                if (g == generation) {
                    inFlight = false;
                    apply(l);
                    if (!good) {
                        showReadFailure();
                    }
                }
            });
            if (f == null) {
                finish.run();
            } else {
                f.whenComplete((v, err) -> finish.run());
            }
        });
    }

    private void backgroundRefresh() {
        io.execute(() -> {
            try {
                String address = currentAddress();
                if (address != null) {
                    data.refreshBenefits(address);
                }
            } catch (RuntimeException ignored) {
                // the next read reports the state; background polling is silent by design (no avatar activity for housekeeping)
            }
        });
    }

    private String currentAddress() {
        if (data.accountOperationsUnavailableReason() != null || !data.sessionActive()) {
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

    // ------------------------------------------------------------------ rendering (FX thread, no I/O)

    private void showLoading() {
        account.getChildren().setAll(Kit.muted(Strings.get("ben.loading")));
        capabilities.getChildren().clear();
        technical.getChildren().setAll(Kit.dim(Strings.get("ben.loading")));
    }

    private void showReadFailure() {
        bannerBox.getChildren().setAll(new ByxBanner(ByxBanner.Kind.WARNING, Strings.get("ben.refreshFail"), Strings.get("ben.readFailed")));
    }

    private void apply(Loaded l) {
        if (l.failed() || l.model() == null) {
            showReadFailure();
            return;
        }
        BenefitsState.Model m = l.model();
        String key = m.overall() + "|" + m.wallet() + "|" + m.balance() + "|" + m.tier() + "|" + m.balanceText() + "|" + m.capabilities() + "|" + l.grant()
                + "|" + progressKey(l.progress());
        if (key.equals(lastKey)) {
            return;
        }
        lastKey = key;
        model = m;
        bannerBox.getChildren().clear();
        if (m.overall() == BenefitsState.Overall.OFFLINE) {
            bannerBox.getChildren().add(new ByxBanner(ByxBanner.Kind.WARNING, Strings.get("ben.b.off"), Strings.get("ben.offline")));
        } // UNAUTHORIZED is stated once, in the Authorization row (no duplicate banner)
        renderAccount(m, l.progress());
        renderCapabilities(m);
        renderTechnical(m, l.grant());
    }

    private void renderAccount(BenefitsState.Model m, EntitlementService.Progress progress) {
        account.getChildren().clear();
        // Wallet
        String wTitle = switch (m.wallet()) {
            case NOT_LINKED -> Strings.get("ben.w.none");
            case SERVICE_UNAVAILABLE -> Strings.get("ben.w.un");
            case VERIFIED -> Strings.get("ben.w.ok");
            case UNKNOWN -> Strings.get("ben.b.unk");
        };
        String wNote = switch (m.wallet()) {
            case NOT_LINKED -> Strings.get("ben.w.noneN");
            case SERVICE_UNAVAILABLE -> Strings.get("ben.w.unN");
            case VERIFIED -> Strings.get("ben.w.okN");
            case UNKNOWN -> Strings.get("ben.b.unkN2");
        };
        ByxBadge.Tone wTone = switch (m.wallet()) {
            case VERIFIED -> ByxBadge.Tone.POSITIVE;
            case SERVICE_UNAVAILABLE -> ByxBadge.Tone.NEGATIVE;
            case NOT_LINKED -> ByxBadge.Tone.NEUTRAL;
            case UNKNOWN -> ByxBadge.Tone.WARNING;
        };
        account.getChildren().add(statusRow("benefits-row-wallet", Strings.get("ben.row.wallet"), wTitle, wTone, wNote));
        // Balance: a missing balance is never shown as zero
        String bTitle;
        String bNote;
        ByxBadge.Tone bTone;
        switch (m.balance()) {
            case KNOWN -> {
                bTitle = Strings.fmt("ben.b.ok", "v", m.balanceText());
                bNote = Strings.get("ben.b.okN");
                bTone = ByxBadge.Tone.POSITIVE;
            }
            case CANNOT_REFRESH -> {
                bTitle = Strings.get("ben.b.off");
                bNote = Strings.get("ben.b.offN");
                bTone = ByxBadge.Tone.WARNING;
            }
            case NOT_RETURNED -> {
                bTitle = Strings.get("ben.b.unk2");
                bNote = Strings.get("ben.b.unk2N");
                bTone = ByxBadge.Tone.WARNING;
            }
            case NEEDS_AUTHORIZATION -> {
                bTitle = Strings.get("ben.b.unk");
                bNote = Strings.get("ben.b.unkN2");
                bTone = ByxBadge.Tone.NEUTRAL;
            }
            default -> {
                bTitle = Strings.get("ben.b.unk");
                bNote = Strings.get("ben.b.unkN");
                bTone = ByxBadge.Tone.NEUTRAL;
            }
        }
        account.getChildren().add(statusRow("benefits-row-balance", Strings.get("ben.row.bal"), bTitle, bTone, bNote));
        // Authorization
        boolean ok = m.authorization() == BenefitsState.Authorization.ACCEPTED;
        account.getChildren().add(statusRow("benefits-row-auth", Strings.get("ben.row.auth"), ok ? Strings.get("ben.a.ok") : Strings.get("ben.a.no"),
                ok ? ByxBadge.Tone.POSITIVE : ByxBadge.Tone.NEGATIVE, ok ? Strings.get("ben.a.okN") : Strings.get("ben.a.noN")));
        // Tier: only when a policy value may be shown; thresholds/progress only from a real balance and real thresholds
        if (m.tier() != null) {
            Label tier = Fx.label(m.tier().charAt(0) + m.tier().substring(1).toLowerCase(java.util.Locale.ROOT), "byx-section-title");
            tier.setId("benefits-tier-value");
            HBox row = new HBox(10, Fx.label(Strings.get("ben.tier"), "byx-label"), tier, Fx.spacer(),
                    ByxBadge.of("TEST POLICY · NOT FINAL TOKENOMICS", ByxBadge.Tone.WARNING));
            row.setAlignment(Pos.CENTER_LEFT);
            account.getChildren().add(row);
            account.getChildren().add(Kit.dim(m.balanceText() != null && progress != null && progress.remainingUbyx() != null
                    && progress.remainingUbyx().signum() > 0
                    ? Strings.fmt("ben.next", "t", progress.nextTier(), "v", new BigDecimal(progress.remainingUbyx(), 6).toPlainString())
                    : Strings.get("ben.noProg")));
        } else {
            Label unknown = Fx.label(Strings.get("ben.tier.unk"), "byx-desk-row-value", "dim");
            HBox row = new HBox(10, Fx.label(Strings.get("ben.tier"), "byx-label"), unknown);
            row.setAlignment(Pos.CENTER_LEFT);
            account.getChildren().add(row);
        }
    }

    private static Node statusRow(String id, String label, String title, ByxBadge.Tone tone, String note) {
        Label l = Fx.label(label, "byx-label");
        l.setMinWidth(100);
        Label pill = ByxBadge.of(title.toUpperCase(java.util.Locale.ROOT), tone);
        pill.setMinWidth(Region.USE_PREF_SIZE);
        HBox head = new HBox(10, l, pill);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox v = new VBox(4, head, Kit.muted(note));
        v.setId(id);
        v.setAccessibleText(label + ": " + title + ". " + note);
        return v;
    }

    private void renderCapabilities(BenefitsState.Model m) {
        capabilities.getChildren().clear();
        for (BenefitsState.Capability c : m.capabilities()) {
            String status = switch (c.status()) {
                case AVAILABLE -> Strings.get("ben.state.available");
                case NOT_REACHED -> Strings.get("ben.state.notReached") + " · " + c.requiredTier();
                case NEEDS_WALLET -> Strings.get("ben.state.needsWallet");
                case CANNOT_CHECK -> Strings.get("ben.state.cannotCheck");
            };
            ByxBadge.Tone tone = switch (c.status()) {
                case AVAILABLE -> ByxBadge.Tone.POSITIVE;
                case CANNOT_CHECK -> ByxBadge.Tone.WARNING;
                default -> ByxBadge.Tone.NEUTRAL;
            };
            KvRow r = new KvRow(c.name());
            r.set("", false, null);
            r.getChildren().addAll(ByxBadge.of(c.backing().text, c.backing() == BenefitsModel.Backing.REFERENCE_ONLY ? ByxBadge.Tone.INFO : ByxBadge.Tone.NEUTRAL),
                    ByxBadge.of(status, tone));
            ((HBox) r).setSpacing(8);
            r.setAccessibleText(c.name() + ", " + c.backing().text + ", " + status);
            capabilities.getChildren().add(r);
        }
    }

    private void renderTechnical(BenefitsState.Model m, Optional<GasGrantRepository.Entry> grant) {
        technical.getChildren().clear();
        technical.getChildren().add(Kit.row(Strings.get("ben.pay.row"), Strings.get("ben.paydisabled"), false));
        technical.getChildren().add(Kit.dim(Strings.get("ben.paydis")));
        technical.getChildren().add(Kit.row(Strings.get("tech.gas"), Strings.get("tech.gasv"), false));
        technical.getChildren().add(Kit.row(Strings.get("tech.elig"), grant.map(g -> g.state()).orElse(Strings.get("ben.gas.none")), false));
        technical.getChildren().add(Kit.label(Strings.get("tech.codes")));
        Label code = Fx.label(m.code(), "byx-mono");
        code.setId("benefits-code");
        technical.getChildren().add(code);
        technical.getChildren().add(Kit.dim(Strings.get("ben.code." + m.code())));
    }

    private static String progressKey(EntitlementService.Progress p) {
        return p == null ? "" : p.nextTier() + p.remainingUbyx();
    }

    BenefitsState.Model state() {
        return model;
    }

    @Override
    public Node node() {
        return scroll;
    }

    @Override
    public void onSnapshot(Snapshot ignored) {
        if (shown) {
            reload();
        }
    }

    @Override
    public void onShow() {
        shown = true;
        generation++;
        inFlight = false;
        lastKey = "";
        reload();
        timer.play();
    }

    @Override
    public void onHide() {
        shown = false;
        generation++;
        inFlight = false;
        timer.stop();
    }

    @Override
    public void dispose() {
        onHide();
        if (ownedIo != null) {
            ownedIo.shutdownNow();
        }
    }
}
