package panel.byxview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import org.junit.jupiter.api.Test;
import panel.design.ByxTheme;
import panel.model.BenefitsSnapshot;
import panel.model.ByxConfig;
import panel.model.ByxSnapshot;
import panel.model.Entitlement;
import panel.model.TreasuryAsset;
import panel.model.TreasurySnapshot;
import panel.model.VerifiedWallet;
import panel.motion.MotionPreference;
import panel.motion.MotionService;
import panel.repository.GasGrantRepository;
import panel.service.EntitlementService;
import panel.tradeview.DeskHarness;

/** Passo 9: estados reais da rede/carteira/tesouraria, classificação de benefícios, ciclo de vida e guarda somente-leitura. */
class ByxScreensTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static ByxSnapshot snap(String connection, String identity, String freshness, Boolean syncing, String height) {
        return new ByxSnapshot("LIVE_NODE", "LOCALNET", connection, identity, freshness, syncing, height == null ? null : "byx-local-1", height,
                height == null ? null : NOW.minusSeconds(3), "byx1abc", BigInteger.valueOf(1_500_000), "BYX", 6, NOW, "ok");
    }

    static final class Stub implements ByxData {
        volatile ByxSnapshot network = ByxSnapshot.unknown("LIVE_NODE", "UNKNOWN", "UNKNOWN", "Not configured");
        volatile boolean admin;
        volatile boolean session = true;
        volatile List<VerifiedWallet> wallets = List.of();
        final AtomicInteger treasuryReads = new AtomicInteger();
        final AtomicInteger benefitReads = new AtomicInteger();
        volatile TreasurySnapshot treasury;
        volatile boolean treasuryFails;

        @Override public ByxSnapshot network() { return network; }
        @Override public boolean sessionActive() { return session; }
        @Override public boolean admin() { return admin; }
        @Override public ByxConfig config() { return null; }
        @Override public void configure(ByxConfig config) { throw new AssertionError("read-only screens never configure on their own"); }
        @Override public void refreshNetwork() { }
        @Override public List<VerifiedWallet> wallets() { return wallets; }
        @Override public BenefitsSnapshot benefits(String address) {
            return new BenefitsSnapshot("NO WALLET", address, null, "FREE", false, null, "UNKNOWN", List.of(), "HOLDER");
        }
        @Override public List<Entitlement> entitlements(String address) { return List.of(); }
        @Override public EntitlementService.Progress progress(String address) { return null; }
        @Override public CompletableFuture<?> refreshBenefits(String address) { benefitReads.incrementAndGet(); return CompletableFuture.completedFuture(null); }
        @Override public Optional<GasGrantRepository.Entry> gasGrant(String address) { return Optional.empty(); }
        @Override public TreasurySnapshot refreshTreasury() throws Exception {
            treasuryReads.incrementAndGet();
            if (treasuryFails) {
                throw new java.io.IOException("offline");
            }
            return treasury;
        }
    }

    private static TreasuryAsset asset(TreasuryAsset.Category c, TreasuryAsset.Source s, boolean test, TreasuryAsset.Verification v) {
        return new TreasuryAsset(c, "BYX", "byx-local-1/granter", "ubyx", BigInteger.valueOf(2_000_000), 6, s, NOW, v, test);
    }

    @Test
    void networkStatesFollowOnlyTheSnapshot() {
        assertEquals(NetworkModel.State.AWAITING_NODE, NetworkModel.state(ByxSnapshot.unknown("LIVE_NODE", "UNKNOWN", "UNKNOWN", "Not configured")));
        assertEquals(NetworkModel.State.HEALTHY, NetworkModel.state(snap("ONLINE", "VERIFIED", "FRESH", false, "10")));
        assertEquals(NetworkModel.State.SYNCING, NetworkModel.state(snap("ONLINE", "VERIFIED", "FRESH", true, "10")));
        assertEquals(NetworkModel.State.STALE, NetworkModel.state(snap("ONLINE", "VERIFIED", "STALE", false, "10")));
        assertEquals(NetworkModel.State.DEGRADED, NetworkModel.state(snap("ONLINE", "VERIFIED", "FRESH", null, "10")));
        assertEquals(NetworkModel.State.OFFLINE, NetworkModel.state(snap("OFFLINE", "VERIFIED", "STALE", false, "10")));
        assertEquals(NetworkModel.State.IDENTITY_MISMATCH, NetworkModel.state(ByxSnapshot.unknown("LIVE_NODE", "LOCALNET", "ONLINE", "bad genesis")));
        assertEquals("—", NetworkModel.value(null));
        assertTrue(NetworkModel.State.SYNCING.active() && NetworkModel.State.AWAITING_NODE.active());
        assertFalse(NetworkModel.State.HEALTHY.active() || NetworkModel.State.OFFLINE.active() || NetworkModel.State.STALE.active());
    }

    @Test
    void walletStatesNeverInventALink() {
        assertEquals(WalletModel.State.UNAVAILABLE, WalletModel.resolve(false, false, false, false, false, false));
        assertEquals(WalletModel.State.NOT_LINKED, WalletModel.resolve(true, false, false, true, false, true));
        assertEquals(WalletModel.State.CONNECTING, WalletModel.resolve(true, false, false, false, false, false));
        assertEquals(WalletModel.State.LOADING, WalletModel.resolve(true, true, true, false, false, true));
        assertEquals(WalletModel.State.LINKED, WalletModel.resolve(true, true, false, true, false, true));
        assertEquals(WalletModel.State.ERROR, WalletModel.resolve(true, true, false, true, true, true));
    }

    @Test
    void treasuryKeepsTheFourClassesApartAndComputesNoTotal() {
        var none = TreasuryModel.lanes(null, false);
        assertEquals(4, none.size());
        assertEquals("NOT CONFIGURED", none.get(0).badge());
        assertTrue(none.stream().allMatch(l -> l.lines().isEmpty()), "nothing is invented: no zero, no total");
        TreasurySnapshot s = new TreasurySnapshot(List.of(
                asset(TreasuryAsset.Category.GAS_SPONSORSHIP_BUDGET, TreasuryAsset.Source.ON_CHAIN, true, TreasuryAsset.Verification.VERIFIED),
                asset(TreasuryAsset.Category.BOT_CAPITAL, TreasuryAsset.Source.PAPER, false, TreasuryAsset.Verification.UNVERIFIED),
                asset(TreasuryAsset.Category.OPERATING_CASH, TreasuryAsset.Source.MANUAL_UNVERIFIED, false, TreasuryAsset.Verification.UNVERIFIED)),
                "NONE / NOT CONFIGURED", "ONLINE/FRESH", BigInteger.ZERO, 0, BigInteger.ZERO, "ok", NOW);
        var lanes = TreasuryModel.lanes(s, false);
        assertEquals("NOT CONFIGURED", lanes.get(0).badge(), "a TEST asset never becomes REAL VERIFIED");
        assertEquals(1, lanes.get(1).lines().size());
        assertEquals("TEST ASSET", lanes.get(1).badge());
        assertEquals("PAPER", lanes.get(2).badge());
        assertEquals("MANUAL_UNVERIFIED", lanes.get(3).badge());
        assertEquals(TreasuryModel.Lane.TEST, TreasuryModel.laneOf(s.assets().get(0)));
        assertEquals("UNAVAILABLE", TreasuryModel.lanes(null, true).get(1).badge());
        assertEquals(TreasuryModel.Availability.UNAVAILABLE, TreasuryModel.availability(null, true));
        assertEquals(TreasuryModel.Availability.NOT_CONFIGURED, TreasuryModel.availability(null, false));
    }

    @Test
    void benefitsClassifyDemonstrativeFeaturesAsReferenceOnly() {
        assertEquals(BenefitsModel.Backing.TEST_POLICY, BenefitsModel.backing("extended_history"));
        assertEquals(BenefitsModel.Backing.TEST_POLICY, BenefitsModel.backing("advanced_analytics"));
        assertEquals(BenefitsModel.Backing.REFERENCE_ONLY, BenefitsModel.backing("advanced_bot_controls"));
        assertEquals(BenefitsModel.Backing.REFERENCE_ONLY, BenefitsModel.backing("premium_research_tools"));
        assertEquals(List.of("Admin access", "Validation", "Final holdout", "Unapproved live trading"), BenefitsModel.NEVER);
        assertEquals("Advanced Bot Controls", BenefitsModel.name(new Entitlement("advanced_bot_controls",
                "Advanced Bot Controls — interface preview", "PLUS", false, "x", null, Entitlement.Status.LOCKED)));
    }

    private static void show(Node node) {
        Scene scene = new Scene((Parent) node, 1440, 900);
        ByxTheme.apply(scene);
        node.applyCss();
        ((Parent) node).layout();
    }

    private static String texts(Node n) {
        StringBuilder b = new StringBuilder();
        collect(n, b);
        return b.toString();
    }

    private static void collect(Node n, StringBuilder b) {
        if (n instanceof Labeled l && l.getText() != null) {
            b.append(l.getText()).append('\n');
        }
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            collect(sp.getContent(), b);
        }
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collect(c, b));
        }
    }

    @Test
    void networkLoopOnlyExistsWithRealActivityAndVisibility() throws Exception {
        DeskHarness.fx(() -> {
            MotionService motion = new MotionService();
            motion.preference.set(MotionPreference.FULL);
            Stub data = new Stub();
            NetworkScreen screen = new NetworkScreen(motion, CLOCK, data);
            show(screen.node());
            assertEquals(NetworkModel.State.AWAITING_NODE, screen.state());
            assertEquals(0, motion.runningLoops(), "not shown: no loop, no timer");
            assertFalse(screen.timerRunning());
            screen.onShow();
            assertTrue(screen.timerRunning());
            assertEquals(1, motion.runningLoops(), "awaiting node: exactly one loop");
            data.network = snap("ONLINE", "VERIFIED", "FRESH", true, "10");
            screen.onSnapshot(null);
            assertEquals(NetworkModel.State.SYNCING, screen.state());
            assertEquals(1, motion.runningLoops(), "syncing keeps one loop");
            data.network = snap("ONLINE", "VERIFIED", "FRESH", false, "11");
            screen.onSnapshot(null);
            assertEquals(NetworkModel.State.HEALTHY, screen.state());
            assertEquals(0, motion.runningLoops(), "healthy is stable");
            data.network = snap("OFFLINE", "VERIFIED", "STALE", false, "11");
            screen.onSnapshot(null);
            assertEquals(0, motion.runningLoops(), "offline shows no false activity");
            data.network = ByxSnapshot.unknown("LIVE_NODE", "UNKNOWN", "UNKNOWN", "Session ended");
            screen.onSnapshot(null);
            assertEquals(1, motion.runningLoops());
            screen.onHide();
            assertEquals(0, motion.runningLoops(), "hidden: zero loops");
            assertFalse(screen.timerRunning());
            for (int i = 0; i < 5; i++) {
                screen.onShow();
                screen.onHide();
            }
            screen.onShow();
            screen.dispose();
            assertEquals(0, motion.runningLoops(), "dispose: zero");
            assertFalse(screen.timerRunning());
            String t = texts(screen.node());
            assertFalse(t.contains("USD"), "BYX is never valued in USD");
        });
    }

    @Test
    void networkShowsRealValuesAndNothingElse() throws Exception {
        DeskHarness.fx(() -> {
            Stub data = new Stub();
            NetworkScreen screen = new NetworkScreen(new MotionService(), CLOCK, data);
            show(screen.node());
            String empty = texts(screen.node());
            assertTrue(empty.contains("AWAITING NODE") || empty.contains("Awaiting node") || empty.toUpperCase().contains("AWAITING NODE"));
            assertTrue(empty.contains("NO FEED"));
            assertTrue(empty.contains("READ-ONLY") && !empty.contains("PERMISSION REQUIRED"), "the endpoint is owned by the service: no admin form, same read-only note for everyone");
            data.network = snap("ONLINE", "VERIFIED", "FRESH", false, "42");
            screen.onShow();
            String live = texts(screen.node());
            assertTrue(live.contains("byx-local-1") && live.contains("42") && live.contains("LIVE"));
            assertTrue(live.contains("Not reported"), "hash and tx count are not read from the node");
            screen.dispose();
        });
    }

    @Test
    void treasuryAndBenefitsPollOnlyWhileVisibleAndNeverComputeATotal() throws Exception {
        DeskHarness.fx(() -> {
            Stub data = new Stub();
            TreasuryScreen treasury = new TreasuryScreen(data);
            show(treasury.node());
            assertFalse(treasury.timerRunning());
            String initial = texts(treasury.node());
            assertTrue(initial.contains("NOT CONFIGURED") && initial.contains("VERIFIED TOTAL · —"));
            assertFalse(initial.replace("BYX is never valued in USD here", "").contains("USD"), "no BYX/USD conversion");
            treasury.onShow();
            assertTrue(treasury.timerRunning());
            treasury.onHide();
            assertFalse(treasury.timerRunning());
            treasury.dispose();
            // B04: the screen reads off the FX thread; this test injects synchronous executors so the first paint is deterministic
            BenefitsScreen benefits = new BenefitsScreen(CLOCK, data, Runnable::run, Runnable::run, () -> panel.shell.avatar.Operations.NONE, new MotionService());
            show(benefits.node());
            assertFalse(benefits.timerRunning());
            String b = texts(benefits.node());
            assertTrue(b.contains("REFERENCE ONLY") && b.contains("NEVER") && b.contains("Free"));
            assertTrue(b.contains("TEST POLICY · NOT FINAL TOKENOMICS"));
            benefits.onShow();
            assertTrue(benefits.timerRunning());
            benefits.dispose();
            assertFalse(benefits.timerRunning());
            WalletScreen wallet = new WalletScreen(new MotionService(), CLOCK, data, id -> { });
            show(wallet.node());
            assertFalse(wallet.timerRunning());
            assertEquals(WalletModel.State.CONNECTING, wallet.state(), "configured nowhere: waiting, never a linked wallet");
            wallet.onShow();
            assertTrue(wallet.timerRunning());
            wallet.dispose();
            assertFalse(wallet.timerRunning());
            assertTrue(texts(wallet.node()).contains("OUTSIDE APP"));
        });
    }

    @Test
    void treasuryLateResultAfterHideIsDiscarded() throws Exception {
        Stub data = new Stub();
        data.treasuryFails = true;
        TreasuryScreen[] holder = new TreasuryScreen[1];
        DeskHarness.fx(() -> {
            holder[0] = new TreasuryScreen(data);
            show(holder[0].node());
            holder[0].onShow();
            holder[0].onHide(); // o resultado em voo chega depois
        });
        Thread.sleep(400);
        DeskHarness.fx(() -> assertFalse(holder[0].failed(), "a result that arrives after hide never touches the screen"));
        DeskHarness.fx(() -> {
            holder[0].onShow();
        });
        Thread.sleep(400);
        DeskHarness.fx(() -> {
            assertTrue(holder[0].failed());
            assertTrue(texts(holder[0].node()).contains("UNAVAILABLE"));
            holder[0].dispose();
        });
    }

    /** O pacote BYX V2 não assina, transmite, usa chave nem move fundos: varredura da fonte. */
    @Test
    void byxPackageIsReadOnly() throws Exception {
        Path dir = Path.of("src/main/java/panel/byxview");
        try (var files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                for (String banned : new String[] {"signTx", "sign(", "broadcast", "privateKey", "mnemonic", "seed", "byxGas.request", "byxGas.revoke",
                        "byxPayments.create", "byxPayments.confirm", "MsgSend", "ProcessBuilder", "transfer("}) {
                    assertFalse(src.contains(banned), f.getFileName() + " must not contain " + banned);
                }
            }
        }
        String adapter = Files.readString(Path.of("src/main/java/panel/app/ByxDataAdapter.java"));
        assertFalse(adapter.contains("request(") || adapter.contains("revoke(") || adapter.contains("create("));
    }

    @Test
    void networkScreenShowsTheReadOnlyServiceStatesWithoutAnyEndpointFormOrTransactionControl() throws Exception {
        DeskHarness.fx(() -> {
            long block = CLOCK.instant().minusSeconds(3).toEpochMilli();
            String[][] cases = {{"NOT_CONFIGURED", "NOT CONFIGURED"}, {"CONNECTING", "CONNECTING"}, {"OFFLINE", "OFFLINE"}, {"SYNCING", "SYNCING"}, {"LIVE", "LIVE"},
                    {"NETWORK_MISMATCH", "NETWORK MISMATCH"}, {"ERROR", "ERROR"}};
            for (String[] c : cases) {
                boolean block3 = c[0].equals("LIVE") || c[0].equals("SYNCING");
                Stub data = new Stub();
                data.network = panel.adapter.ServiceChainGateway.toSnapshot(new panel.localservice.ChainStatusClient.View(c[0], !c[0].equals("NOT_CONFIGURED"), block3 || c[0].equals("NETWORK_MISMATCH"),
                        block3 || c[0].equals("NETWORK_MISMATCH") ? "byx" : null, block3 ? 100L : null, block3 ? c[0].equals("SYNCING") : null, block3 ? block : null, block3, "NONE"), CLOCK.instant());
                NetworkScreen screen = new NetworkScreen(new MotionService(), CLOCK, data);
                show(screen.node());
                screen.onShow();
                String t = texts(screen.node());
                assertTrue(t.contains(c[1]), c[0] + " → " + c[1] + "\n" + t);
                assertEquals(c[0].equals("LIVE"), t.contains("LIVE") && screen.state() == NetworkModel.State.HEALTHY, "LIVE only for a LIVE node: " + c[0]);
                assertFalse(t.toLowerCase().matches("(?s).*\\b(send|transfer|sign|broadcast|connect wallet|swap|mainnet)\\b.*"), "no transaction wording: " + c[0]);
                assertEquals(0, countControls(screen.node()), "no input field and no button: the panel does not send hosts, ports or transactions");
                assertTrue(t.contains("owned by the local service") || t.contains("Read-only public node data"));
                screen.dispose();
            }
        });
    }

    private static int countControls(Node n) {
        int count = n instanceof javafx.scene.control.TextInputControl || n instanceof javafx.scene.control.ButtonBase ? 1 : 0;
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            count += countControls(sp.getContent());
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                count += countControls(c);
            }
        }
        return count;
    }

    @Test
    void networkScreenShowsDenomExponentAndSupplyFromTheVerifiedServiceFactsAndDashesWithoutThem() throws Exception {
        DeskHarness.fx(() -> {
            long block = CLOCK.instant().minusSeconds(3).toEpochMilli();
            var live = new panel.localservice.ChainStatusClient.View("LIVE", true, true, "byx", 321L, false, block, true, "NONE", 2);
            Stub data = new Stub();
            data.network = panel.adapter.ServiceChainGateway.toSnapshot(live, new panel.model.ChainFacts("ubyx", "BYX", 6, new java.math.BigInteger("1000239758")), CLOCK.instant());
            NetworkScreen screen = new NetworkScreen(new MotionService(), CLOCK, data);
            show(screen.node());
            screen.onShow();
            String t = texts(screen.node());
            for (String expected : new String[] {"LIVE", "byx", "321", "Base denom", "ubyx", "Display denom", "Exponent", "6", "Total supply", "1000.239758 BYX", "Caught up"}) {
                assertTrue(t.contains(expected), expected + "\n" + t);
            }
            assertFalse(t.toUpperCase().contains("USD"));
            data.network = panel.adapter.ServiceChainGateway.toSnapshot(live, null, CLOCK.instant());
            screen.onSnapshot(null);
            String bare = texts(screen.node());
            assertTrue(bare.contains("Total supply") && !bare.contains("1000.239758"), "no verified facts: nothing invented");
            screen.dispose();
        });
    }
}
