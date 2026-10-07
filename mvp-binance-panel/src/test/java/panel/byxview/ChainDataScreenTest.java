package panel.byxview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import org.junit.jupiter.api.Test;
import panel.model.ChainModules;
import panel.model.ChainModules.Failure;
import panel.model.ChainModules.Freshness;
import panel.model.ChainModules.Reply;
import panel.motion.MotionService;
import panel.tradeview.DeskHarness;

/** V2.1N: a tela de dados públicos: estados distintos, somente leitura, I/O fora da thread FX, sem timers e sem trabalho depois de sair. */
class ChainDataScreenTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
    static final String ADDR = "byx14uzu3ja88cpf5fktzp9rwt5zqhppv0j59qev7z";

    static final class FakeReader implements ChainModules.Reader {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<Boolean> onFxThread = new CopyOnWriteArrayList<>();
        volatile Reply<ChainModules.Merchant> merchant = Reply.failed(Failure.NOT_FOUND);
        volatile Reply<ChainModules.Payment> payment = Reply.failed(Failure.NOT_FOUND);
        final Queue<Reply<ChainModules.Page<ChainModules.Merchant>>> merchantPages = new ArrayDeque<>();
        volatile Reply<ChainModules.Balance> balance = Reply.failed(Failure.UNREACHABLE);

        private void note(String c) {
            calls.add(c);
            onFxThread.add(Platform.isFxApplicationThread());
        }

        @Override public Reply<ChainModules.Merchant> merchant(String id) { note("merchant " + id); return merchant; }
        @Override public Reply<ChainModules.Page<ChainModules.Merchant>> merchants(int limit, String cursor) { note("merchants " + cursor); return merchantPages.poll(); }
        @Override public Reply<ChainModules.Payment> payment(String id) { note("payment " + id); return payment; }
        @Override public Reply<ChainModules.Page<ChainModules.Payment>> paymentsByStore(String s, int l, String c) { note("byStore " + s); return Reply.failed(Failure.UNREACHABLE); }
        @Override public Reply<ChainModules.PaymentParams> paymentParams() { note("params"); return new Reply<>(true, null, Freshness.LIVE, 0, new ChainModules.PaymentParams(600, 60, 86400), null); }
        @Override public Reply<ChainModules.Certificate> certificate(String id) { note("cert " + id); return Reply.failed(Failure.MODULE_UNAVAILABLE); }
        @Override public Reply<ChainModules.Page<ChainModules.Certificate>> certificatesByMerchant(String m, int l, String c) { note("certs " + m); return Reply.failed(Failure.NETWORK_MISMATCH); }
        @Override public Reply<ChainModules.Balance> balance(String a) { note("balance " + a); return balance; }
        @Override public Reply<ChainModules.Feesplit> feesplit() { note("feesplit"); return new Reply<>(true, null, Freshness.LIVE, 0, new ChainModules.Feesplit("DOCUMENTED_DEFAULT_NOT_QUERIED", 6000, 3000, 1000), null); }
        @Override public Reply<ChainModules.Health> health() {
            note("health");
            return new Reply<>(true, null, Freshness.LIVE, 0, new ChainModules.Health("OFFLINE", List.of(new ChainModules.ModuleStatus("LOJAS", ChainModules.ModuleState.UNAVAILABLE, "MODULE_UNAVAILABLE"),
                    new ChainModules.ModuleStatus("PAYMENTS", ChainModules.ModuleState.AVAILABLE, null), new ChainModules.ModuleStatus("FEESPLIT", ChainModules.ModuleState.NOT_EXPOSED, null)), 4, 2, 1, 0), null);
        }
    }

    private static ChainModules.Merchant m(String id) {
        return new ChainModules.Merchant(id, "Loja " + id, "Rua 1", ADDR, ADDR, "approved");
    }

    private static Reply<ChainModules.Merchant> okMerchant(Freshness f, long age) {
        return new Reply<>(true, null, f, age, m("1"), null);
    }

    private static final Executor SYNC = Runnable::run;

    private static void collect(Node n, List<Node> out) {
        out.add(n);
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collect(c, out));
        }
    }

    private static Node root(ChainDataScreen s) {
        return ((javafx.scene.control.ScrollPane) s.node()).getContent();
    }

    private static List<Node> all(ChainDataScreen s) {
        List<Node> out = new ArrayList<>();
        collect(root(s), out);
        return out;
    }

    private static String texts(Node root) {
        List<Node> out = new ArrayList<>();
        collect(root, out);
        StringBuilder b = new StringBuilder();
        for (Node n : out) {
            if (n instanceof Labeled l && !(n instanceof Button)) {
                b.append(l.getText()).append('\n');
            }
        }
        return b.toString();
    }

    private static Button button(ChainDataScreen s, String id) {
        return (Button) root(s).lookup("#" + id);
    }

    private static javafx.scene.control.TextField input(ChainDataScreen s, String id) {
        return (javafx.scene.control.TextField) root(s).lookup("#" + id);
    }

    private static String badge(ChainDataScreen s, String sectionId) {
        List<Node> out = new ArrayList<>();
        collect(root(s).lookup("#" + sectionId), out);
        return out.stream().filter(n -> n instanceof Labeled l && l.getStyleClass().contains("byx-badge")).map(n -> ((Labeled) n).getText()).findFirst().orElse("");
    }

    @Test
    void everyStateIsDistinctAndStaleOrCachedNeverLooksLive() throws Exception {
        DeskHarness.fx(() -> {
            FakeReader r = new FakeReader();
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, r, null, SYNC, SYNC);
            s.onShow();
            input(s, "chain-merchant-input").setText("1");
            r.merchant = okMerchant(Freshness.LIVE, 0);
            button(s, "chain-merchant-go").fire();
            assertEquals("LIVE", badge(s, "chain-merchant"));
            assertTrue(texts(root(s).lookup("#chain-merchant")).contains("Loja 1"));
            r.merchant = okMerchant(Freshness.CACHED, 4000);
            button(s, "chain-merchant-go").fire();
            assertEquals("CACHED", badge(s, "chain-merchant"));
            r.merchant = okMerchant(Freshness.STALE, 90_000);
            button(s, "chain-merchant-go").fire();
            assertEquals("STALE", badge(s, "chain-merchant"));
            assertTrue(texts(root(s).lookup("#chain-merchant")).contains("not current"), "stale data is explicitly labeled");
            r.merchant = Reply.failed(Failure.NOT_FOUND);
            button(s, "chain-merchant-go").fire();
            assertEquals("NOT FOUND", badge(s, "chain-merchant"));
            assertFalse(texts(root(s).lookup("#chain-merchant")).contains("Loja 1"), "no data after a failure");
            for (Object[] c : new Object[][] {{Failure.UNREACHABLE, "OFFLINE"}, {Failure.MODULE_UNAVAILABLE, "MODULE UNAVAILABLE"}, {Failure.NETWORK_MISMATCH, "NETWORK MISMATCH"},
                    {Failure.NOT_CONFIGURED, "NOT CONFIGURED"}, {Failure.CONTRACT_VIOLATION, "ERROR"}, {Failure.RATE_LIMITED, "RATE LIMITED"}}) {
                r.merchant = Reply.failed((Failure) c[0]);
                button(s, "chain-merchant-go").fire();
                assertEquals(c[1], badge(s, "chain-merchant"), String.valueOf(c[0]));
            }
            s.dispose();
        });
    }

    @Test
    void economicsShowsTheSixtyThirtyTenAllocationAsDocumentedNotQueriedAndModuleStatusIsPerModule() throws Exception {
        DeskHarness.fx(() -> {
            FakeReader r = new FakeReader();
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, r, null, SYNC, SYNC);
            s.onShow();
            String eco = texts(root(s).lookup("#chain-economics"));
            assertTrue(eco.contains("60%") && eco.contains("30%") && eco.contains("10%"), eco);
            assertTrue(eco.contains("Distribution allocation") && eco.contains("Treasury allocation") && eco.contains("Burn allocation"), eco);
            assertTrue(eco.contains("not read from the chain") && eco.contains("not a net share paid to validators"), eco);
            assertFalse(eco.toLowerCase().contains("58.8") || eco.contains("net to validator") && !eco.contains("not a net"), "no hardcoded community tax and no 'net validator' claim");
            assertTrue(eco.contains("600 s") && eco.contains("86400 s"), "payment expiry params come from the query");
            String health = texts(root(s).lookup("#chain-health"));
            assertTrue(health.contains("NODE · OFFLINE") && health.contains("Lojas") && health.contains("UNAVAILABLE") && health.contains("Payments") && health.contains("AVAILABLE") && health.contains("Feesplit") && health.contains("NOT EXPOSED"), health);
            assertTrue(texts(root(s).lookup("#chain-card-feesplit")).contains("Documented policy · not read from chain"), "feesplit is never mixed with live state");
            assertTrue(texts(root(s).lookup("#chain-economics")).contains("DOCUMENTED POLICY · NOT READ FROM CHAIN"));
            s.dispose();
        });
    }

    @Test
    void thereIsNoControlThatCouldChangeAnythingOnTheChain() throws Exception {
        DeskHarness.fx(() -> {
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, new FakeReader(), null, SYNC, SYNC);
            Set<String> allowed = Set.of("Refresh", "Look up", "Load", "List", "Load more");
            for (Node n : all(s)) {
                if (n instanceof Button b) {
                    assertTrue(allowed.contains(b.getText()), "unexpected control: " + b.getText());
                }
                if (n instanceof TextInputControl t) {
                    assertFalse(t instanceof javafx.scene.control.PasswordField, "no secrets are ever entered here");
                }
            }
            String text = texts(root(s)).toLowerCase();
            for (String banned : new String[] {"sign transaction", "send ", "broadcast", "create merchant", "pay now", "mnemonic", "private key", "connect wallet"}) {
                assertFalse(text.contains(banned), banned);
            }
            s.dispose();
        });
    }

    @Test
    void invalidAddressFailsWithoutAnyServiceCallAndAValidOneShowsExactAmounts() throws Exception {
        DeskHarness.fx(() -> {
            FakeReader r = new FakeReader();
            r.balance = new Reply<>(true, null, Freshness.LIVE, 0, new ChainModules.Balance(ADDR, new BigInteger("1234567"), "1.234567 BYX"), null);
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, r, null, SYNC, SYNC);
            s.onShow();
            input(s, "chain-balance-input").setText("byx1notvalid");
            // o leitor real recusa localmente; o fake ecoa a política: aqui a tela só mostra o estado do Reply
            r.balance = Reply.failed(Failure.INVALID_REQUEST);
            button(s, "chain-balance-go").fire();
            assertEquals("INVALID INPUT", badge(s, "chain-balance"));
            r.balance = new Reply<>(true, null, Freshness.LIVE, 0, new ChainModules.Balance(ADDR, new BigInteger("1234567"), "1.234567 BYX"), null);
            input(s, "chain-balance-input").setText(ADDR);
            button(s, "chain-balance-go").fire();
            String t = texts(root(s).lookup("#chain-balance"));
            assertTrue(t.contains("1.234567 BYX") && t.contains("1234567 ubyx"), t);
            assertTrue(t.contains("byx14uzu3j…9qev7z"), "long identifiers are abbreviated only for display");
            s.dispose();
        });
    }

    @Test
    void listsPageIncrementallyAndNeverRenderMoreThanTheCap() throws Exception {
        DeskHarness.fx(() -> {
            FakeReader r = new FakeReader();
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, r, null, SYNC, SYNC);
            s.onShow();
            r.merchantPages.add(new Reply<>(true, null, Freshness.LIVE, 0, new ChainModules.Page<>(List.of(m("1"), m("2"))), "v1.b.AAAAAAAAAAM"));
            button(s, "chain-merchants-go").fire();
            assertEquals("LIVE", badge(s, "chain-merchants"));
            assertTrue(button(s, "chain-merchants-more").isVisible(), "a cursor shows Load more");
            assertEquals(1, r.calls.stream().filter(c -> c.startsWith("merchants")).count(), "the next page is not fetched automatically");
            r.merchantPages.add(new Reply<>(true, null, Freshness.LIVE, 0, new ChainModules.Page<>(List.of(m("3"))), null));
            button(s, "chain-merchants-more").fire();
            assertTrue(r.calls.contains("merchants v1.b.AAAAAAAAAAM"));
            assertFalse(button(s, "chain-merchants-more").isVisible(), "last page hides Load more");
            String t = texts(root(s).lookup("#chain-merchants"));
            assertTrue(t.contains("#1") && t.contains("#2") && t.contains("#3"));
            r.merchantPages.add(new Reply<>(true, null, Freshness.LIVE, 0, new ChainModules.Page<>(List.of()), null));
            button(s, "chain-merchants-go").fire();
            assertEquals("EMPTY", badge(s, "chain-merchants"), "an empty list is its own state, not an ambiguous empty table");
            s.dispose();
        });
    }

    @Test
    void chainReadsRunOffTheFxThreadAndOnlyTheViewUpdateRunsOnIt() throws Exception {
        FakeReader r = new FakeReader();
        r.merchant = okMerchant(Freshness.LIVE, 0);
        var io = Executors.newSingleThreadExecutor();
        CountDownLatch done = new CountDownLatch(1);
        DeskHarness.fx(() -> {
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, r, null, io, cmd -> Platform.runLater(() -> {
                cmd.run();
                done.countDown();
            }));
            s.onShow();
            input(s, "chain-merchant-input").setText("1");
            button(s, "chain-merchant-go").fire();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        io.shutdownNow();
        assertFalse(r.onFxThread.isEmpty());
        assertFalse(r.onFxThread.contains(true), "no chain/IPC call ever ran on the JavaFX Application Thread: " + r.calls);
    }

    @Test
    void leavingTheScreenDropsInFlightResultsAndStartsNothingElse() throws Exception {
        DeskHarness.fx(() -> {
            FakeReader r = new FakeReader();
            r.merchant = okMerchant(Freshness.LIVE, 0);
            List<Runnable> queued = new ArrayList<>();
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, r, null, queued::add, SYNC);
            s.onShow();
            queued.forEach(Runnable::run);
            queued.clear();
            input(s, "chain-merchant-input").setText("1");
            button(s, "chain-merchant-go").fire();
            assertEquals("LOADING", badge(s, "chain-merchant"));
            s.onHide();
            queued.forEach(Runnable::run); // o resultado chega depois de sair
            assertEquals("LOADING", badge(s, "chain-merchant"), "a result for a screen the user left is dropped, not rendered");
            assertTrue(s.idle());
            int before = r.calls.size();
            for (int i = 0; i < 5; i++) {
                s.onHide();
            }
            assertEquals(before, r.calls.size(), "hidden: no polling, no timers, no requests");
            s.dispose();
        });
    }

    @Test
    void manualRefreshIsDebouncedAndAskedOfTheService() throws Exception {
        DeskHarness.fx(() -> {
            FakeReader r = new FakeReader();
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, r, null, SYNC, SYNC);
            s.onShow();
            long healthCalls = r.calls.stream().filter("health"::equals).count();
            for (int i = 0; i < 10; i++) {
                button(s, "chain-refresh").fire();
            }
            assertEquals(healthCalls, r.calls.stream().filter("health"::equals).count(), "a refresh storm within the debounce window is coalesced");
            s.dispose();
        });
    }

    @Test
    void headerReusesTheNetworkSnapshotAndTheRailEntryAddsNoRequests() throws Exception {
        DeskHarness.fx(() -> {
            FakeReader r = new FakeReader();
            ByxScreensTest.Stub data = new ByxScreensTest.Stub();
            data.network = new panel.model.ByxSnapshot("LIVE_NODE", "LOCALNET", "ONLINE", "VERIFIED", "FRESH", false, "byx", "605", Instant.parse("2026-10-07T11:59:50Z"), null, null, null, 0,
                    Instant.parse("2026-10-07T11:59:55Z"), "ok", "LIVE", new panel.model.ChainFacts("ubyx", "BYX", 6, java.math.BigInteger.ONE));
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, r, data, SYNC, SYNC);
            s.onShow();
            String h = texts(root(s).lookup("#chain-header"));
            assertTrue(h.contains("LIVE") && h.contains("byx") && h.contains("605") && h.contains("ubyx / BYX") && h.contains("READ ONLY"), h);
            assertTrue(h.contains("00:00:10"), "block age from the shared snapshot: " + h);
            long before = r.calls.size();
            s.onSnapshot(null);
            s.onShow();
            assertEquals(before + 3, r.calls.size(), "re-entering asks for health/feesplit/params once each; the header itself adds no call");
            s.dispose();
        });
    }

    @Test
    void chainDataIsInTheBYXRailRightAfterNetworkAndOpensTheExistingRoute() {
        List<String> ids = panel.shell.ShellRoutes.rail(panel.shell.ShellContext.BYX).stream().map(x -> x.id()).toList();
        assertEquals(List.of("t-byx", "t-chain-data", "t-wallet", "t-benefits", "t-treasury"), ids);
        assertEquals("Chain data", panel.shell.ShellRoutes.require("t-chain-data").title());
    }

    @Test
    void constructingTheScreenIsLazyNoReadsNoTimersNoThreads() throws Exception {
        DeskHarness.fx(() -> {
            FakeReader r = new FakeReader();
            long threadsBefore = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("chain-data-io")).count();
            ChainDataScreen s = new ChainDataScreen(new MotionService(), CLOCK, r, null);
            assertTrue(r.calls.isEmpty(), "building the screen (post-login composition) performs no read: " + r.calls);
            assertEquals(threadsBefore, Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("chain-data-io")).count(), "no IO thread until the screen is shown");
            assertTrue(s.idle());
            s.dispose();
        });
    }
}
