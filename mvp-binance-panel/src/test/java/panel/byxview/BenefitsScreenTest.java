package panel.byxview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import panel.FxBridge;
import panel.design.ByxTheme;
import panel.model.BenefitsSnapshot;
import panel.model.ByxConfig;
import panel.model.ByxSnapshot;
import panel.model.Entitlement;
import panel.model.TreasurySnapshot;
import panel.model.VerifiedWallet;
import panel.motion.MotionService;
import panel.repository.GasGrantRepository;
import panel.service.EntitlementService;
import panel.shell.avatar.Operations;

/** B04 in the JavaFX scene graph: honest wording per state, reads off the FX thread, Refresh is a real reported operation. */
class BenefitsScreenTest {
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Runnable_ SYNC = new Runnable_();

    private static final class Runnable_ implements java.util.concurrent.Executor {
        @Override
        public void execute(Runnable r) {
            r.run();
        }
    }

    static final class Data implements ByxData {
        volatile String reason;
        volatile boolean session = true;
        volatile List<VerifiedWallet> wallets = List.of();
        volatile RuntimeException walletFailure;
        volatile BigInteger balance;
        volatile String walletStatus = "VERIFIED";
        volatile ByxSnapshot network = new ByxSnapshot("LIVE_NODE", "LOCALNET", "ONLINE", "VERIFIED", "FRESH", false, "c", "10", NOW.minusSeconds(2), "a", null,
                "BYX", 6, NOW, "ok", "LIVE");
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<String> refreshes = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<Object> refreshFuture = CompletableFuture.completedFuture(null);

        @Override public String accountOperationsUnavailableReason() { return reason; }
        @Override public ByxSnapshot network() { return network; }
        @Override public boolean sessionActive() { return session; }
        @Override public boolean admin() { return false; }
        @Override public ByxConfig config() { return null; }
        @Override public void configure(ByxConfig config) { throw new AssertionError("Benefits never configures anything"); }
        @Override public void refreshNetwork() { }
        @Override public List<VerifiedWallet> wallets() {
            threads.add(Platform.isFxApplicationThread() ? "FX" : "worker");
            if (walletFailure != null) {
                throw walletFailure;
            }
            return wallets;
        }
        @Override public BenefitsSnapshot benefits(String address) {
            return new BenefitsSnapshot(walletStatus, address, balance, "HOLDER", true, NOW, "LIVE", List.of(), "PLUS");
        }
        @Override public List<Entitlement> entitlements(String address) {
            return List.of(new Entitlement("extended_history", "Extended History — x", "HOLDER", true, "BYX_BALANCE", null, Entitlement.Status.UNLOCKED));
        }
        @Override public EntitlementService.Progress progress(String address) { return null; }
        @Override public CompletableFuture<?> refreshBenefits(String address) {
            refreshes.add(address);
            return refreshFuture;
        }
        @Override public Optional<GasGrantRepository.Entry> gasGrant(String address) { return Optional.empty(); }
        @Override public TreasurySnapshot refreshTreasury() { throw new AssertionError("not used"); }
    }

    private static final class Ops implements Operations {
        final List<String> log = new CopyOnWriteArrayList<>();

        @Override
        public Token begin(String name) {
            log.add("begin:" + name);
            return new Token() { };
        }

        @Override
        public void end(Token token, boolean ok, String why) {
            log.add("end:" + ok + ":" + why);
        }
    }

    static VerifiedWallet wallet() {
        return new VerifiedWallet(1, "byx1abc", "pk", "byx-local-1", "gen", NOW.minusSeconds(3600), NOW.minusSeconds(600), null);
    }

    private static String texts(Node n) {
        List<String> out = new ArrayList<>();
        walk(n, out);
        return String.join("\n", out);
    }

    private static void walk(Node n, List<String> out) {
        if (n instanceof Labeled l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (n instanceof Button b && b.getGraphic() != null) {
            walk(b.getGraphic(), out);
        }
        if (n instanceof ScrollPane sp && sp.getContent() != null) {
            walk(sp.getContent(), out);
        }
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> walk(c, out));
        }
    }

    private static BenefitsScreen screen(Data d, Operations ops) {
        BenefitsScreen s = new BenefitsScreen(CLOCK, d, SYNC, SYNC, () -> ops, new MotionService());
        Stage st = new Stage();
        Scene sc = new Scene((Parent) s.node(), 1200, 900);
        ByxTheme.apply(sc);
        st.setScene(sc);
        st.show();
        s.onShow();
        ((Parent) s.node()).applyCss();
        ((Parent) s.node()).layout();
        return s;
    }

    @Test
    void productionStateSaysTheSessionIsNotAuthorizedAndInventsNothing() throws Exception {
        String t = FxBridge.fx(() -> {
            Data d = new Data();
            d.reason = "SERVER_AUTHORIZATION_REQUIRED";
            BenefitsScreen s = screen(d, Operations.NONE);
            assertEquals(BenefitsState.Overall.UNAUTHORIZED, s.state().overall());
            String text = texts(s.node());
            assertTrue(d.threads.isEmpty(), "no wallet read is even attempted without authorization");
            s.dispose();
            return text;
        });
        assertTrue(t.contains("SESSION NOT AUTHORIZED"));
        assertTrue(t.contains("SERVER_AUTHORIZATION_REQUIRED"));
        assertTrue(t.contains("Unknown"), "tier and balance are unknown");
        assertTrue(t.contains("CAN’T BE CHECKED NOW"));
        assertFalse(t.toLowerCase().contains("upgrade"), "an authorization problem is not an upgrade prompt");
        assertFalse(t.contains("0.000000"), "no invented balance");
        assertTrue(t.contains("NOT IN THIS BETA"), "payments are not offered");
        assertTrue(t.contains("NEVER"), "what BYX never grants stays visible");
        assertTrue(t.contains("This page only reads"));
    }

    @Test
    void eachRealConditionHasItsOwnWording() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Data none = new Data();
            BenefitsScreen s1 = screen(none, Operations.NONE);
            String noWallet = texts(s1.node());
            s1.dispose();
            Data down = new Data();
            down.walletFailure = new IllegalStateException("localnet not configured");
            BenefitsScreen s2 = screen(down, Operations.NONE);
            String unavailable = texts(s2.node());
            s2.dispose();
            Data denied = new Data();
            denied.walletFailure = new panel.security.AccessDeniedException("SERVER_AUTHORIZATION_REQUIRED: wallet.identity");
            BenefitsScreen s3 = screen(denied, Operations.NONE);
            String den = texts(s3.node());
            s3.dispose();
            Data off = new Data();
            off.wallets = List.of(wallet());
            off.balance = BigInteger.valueOf(2_000_000);
            off.network = ByxSnapshot.unknown("LIVE_NODE", "LOCALNET", "OFFLINE", "down");
            BenefitsScreen s4 = screen(off, Operations.NONE);
            String offline = texts(s4.node());
            s4.dispose();
            Data nobal = new Data();
            nobal.wallets = List.of(wallet());
            nobal.balance = null;
            BenefitsScreen s5 = screen(nobal, Operations.NONE);
            String unknownBalance = texts(s5.node());
            s5.dispose();
            Data ok = new Data();
            ok.wallets = List.of(wallet());
            ok.balance = BigInteger.valueOf(1_250_000_000L);
            BenefitsScreen s6 = screen(ok, Operations.NONE);
            String known = texts(s6.node());
            s6.dispose();
            return new Object[] {noWallet, unavailable, den, offline, unknownBalance, known};
        });
        assertTrue(((String) r[0]).contains("NO WALLET LINKED") && ((String) r[0]).contains("NEEDS A VERIFIED WALLET"));
        assertTrue(((String) r[1]).contains("WALLET UNAVAILABLE") && ((String) r[1]).contains("CAN’T BE CHECKED NOW"));
        assertFalse(((String) r[1]).contains("NOT REACHED"), "an outage is never presented as a plan limit");
        assertTrue(((String) r[2]).contains("SESSION NOT AUTHORIZED"));
        assertTrue(((String) r[3]).contains("CAN’T REFRESH") && ((String) r[3]).contains("NETWORK_OFFLINE"));
        assertFalse(((String) r[3]).contains("2.000000"), "an unconfirmable value is not shown as current");
        assertTrue(((String) r[4]).contains("NOT RETURNED") && ((String) r[4]).contains("This is not zero."));
        assertFalse(((String) r[4]).contains("0.000000"));
        assertTrue(((String) r[5]).contains("1250.000000 BYX · TEST") && ((String) r[5]).contains("AVAILABLE · TEST"));
        assertTrue(((String) r[5]).contains("TEST POLICY · NOT FINAL TOKENOMICS"));
    }

    @Test
    void readsHappenOffTheFxThread() throws Exception {
        Data d = new Data();
        BenefitsScreen[] holder = new BenefitsScreen[1];
        FxBridge.fx(() -> {
            holder[0] = new BenefitsScreen(CLOCK, d, null, null, () -> Operations.NONE, new MotionService());
            Stage st = new Stage();
            st.setScene(new Scene((Parent) holder[0].node(), 900, 700));
            st.show();
            holder[0].onShow();
            return null;
        });
        long deadline = System.currentTimeMillis() + 5_000;
        while (d.threads.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(d.threads.isEmpty());
        assertTrue(d.threads.stream().allMatch("worker"::equals), "no blocking read on the FX thread: " + d.threads);
        FxBridge.fx(() -> {
            holder[0].dispose();
            return null;
        });
    }

    @Test
    void refreshIsARealOperationReportedToTheAvatarAndEndsWithItsRealResult() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Data d = new Data();
            d.wallets = List.of(wallet());
            d.balance = BigInteger.valueOf(1_000_000);
            CompletableFuture<Object> pending = new CompletableFuture<>();
            d.refreshFuture = pending;
            Ops ops = new Ops();
            BenefitsScreen s = screen(d, ops);
            Button refresh = (Button) s.node().lookup("#benefits-refresh");
            refresh.fire();
            List<String> during = List.copyOf(ops.log);
            refresh.fire(); // while pending: ignored, no second operation
            List<String> duringTwice = List.copyOf(ops.log);
            pending.complete(null);
            Object[] out = {during, duringTwice, List.copyOf(ops.log), d.refreshes};
            s.dispose();
            return out;
        });
        assertEquals(List.of("begin:benefits.refresh"), r[0]);
        assertEquals(List.of("begin:benefits.refresh"), r[1], "no second operation while one is pending");
        assertEquals(List.of("begin:benefits.refresh", "end:true:null"), r[2], "ends only with the real completion");
        assertEquals(1, ((List<?>) r[3]).size());
    }

    @Test
    void refreshWithoutAWalletStillReadsHonestlyAndReportsNoFailure() throws Exception {
        List<String> log = FxBridge.fx(() -> {
            Data d = new Data();
            d.reason = "SERVER_AUTHORIZATION_REQUIRED";
            Ops ops = new Ops();
            BenefitsScreen s = screen(d, ops);
            ((Button) s.node().lookup("#benefits-refresh")).fire();
            List<String> l = List.copyOf(ops.log);
            s.dispose();
            return l;
        });
        assertEquals(List.of("begin:benefits.refresh", "end:true:null"), log, "a definite 'not authorized' answer is a result, not a failure");
    }

    @Test
    void aResultThatArrivesAfterHideNeverPaints() throws Exception {
        Object[] r = FxBridge.fx(() -> {
            Data d = new Data();
            d.wallets = List.of(wallet());
            d.balance = BigInteger.valueOf(7_000_000);
            List<Runnable> io = new ArrayList<>();
            List<Runnable> ui = new ArrayList<>();
            BenefitsScreen s = new BenefitsScreen(CLOCK, d, io::add, ui::add, () -> Operations.NONE, new MotionService());
            s.onShow();
            while (!io.isEmpty()) {
                io.remove(0).run(); // the read finished on the worker...
            }
            s.onHide(); // ...but the user left the page before its result reached the UI
            while (!ui.isEmpty()) {
                ui.remove(0).run();
            }
            Object[] out = {s.state()};
            s.dispose();
            return out;
        });
        assertEquals(null, r[0], "a late result from a previous visit never paints");
    }
}
