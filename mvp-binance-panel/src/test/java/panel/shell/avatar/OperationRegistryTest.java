package panel.shell.avatar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import panel.shell.avatar.OperationRegistry.Presentation;

/** D-13: loading/error come only from real tokens; stale tokens are inert; the watchdog is presentation-only. */
class OperationRegistryTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final MascotTokens tk = MascotTokens.shared();
    private final OperationRegistry reg = new OperationRegistry(now::get, tk);

    private void at(long ms) {
        now.set(1_000 + ms);
    }

    @Test
    void tokensComeFromTheDesignFile() {
        assertEquals(300, tk.showDelayMs());
        assertEquals(500, tk.minShowMs());
        assertEquals(15_000, tk.deadlineMs());
        assertEquals(4_000, tk.errorHoldMs());
        assertEquals(1200, tk.orbitMsPerRev());
        assertEquals(490, tk.reactTotalMs());
        assertEquals(7, tk.blinkSeed());
        assertEquals(5, tk.ringCount());
    }

    @Test
    void shortOperationsNeverFlicker() {
        var t = reg.begin("quick");
        at(299);
        assertEquals(Presentation.NONE, reg.presentation());
        reg.end(t, true, null);
        at(1_000);
        assertEquals(Presentation.NONE, reg.presentation());
    }

    @Test
    void loadingAppearsAfterTheDelayAndStaysTheMinimumTime() {
        var t = reg.begin("slow");
        at(300);
        assertEquals(Presentation.LOADING, reg.presentation());
        reg.end(t, true, null);
        at(320);
        assertEquals(Presentation.LOADING, reg.presentation(), "minimum visible time");
        at(799);
        assertEquals(Presentation.LOADING, reg.presentation());
        at(801);
        assertEquals(Presentation.NONE, reg.presentation());
    }

    @Test
    void oneOperationEndingNeverFinishesAnother() {
        var a = reg.begin("a");
        at(100);
        var b = reg.begin("b");
        at(400);
        assertEquals(Presentation.LOADING, reg.presentation());
        reg.end(a, true, null);
        reg.end(a, true, null); // repeated: ignored
        assertEquals(1, reg.pending());
        at(2_000);
        assertEquals(Presentation.LOADING, reg.presentation(), "b is still pending");
        reg.end(b, true, null);
        at(3_000);
        assertEquals(Presentation.NONE, reg.presentation());
    }

    @Test
    void staleAndForeignTokensAreInert() {
        var old = reg.begin("before logout");
        reg.clearAll(); // session change
        var fresh = reg.begin("after login");
        at(400);
        assertEquals(Presentation.LOADING, reg.presentation());
        reg.end(old, false, "LATE"); // late callback from the previous session
        assertEquals(1, reg.pending(), "the newer operation is untouched");
        assertEquals(Presentation.LOADING, reg.presentation(), "and no error was raised");
        var other = new OperationRegistry(now::get, tk);
        var foreign = other.begin("elsewhere");
        reg.end(foreign, false, "FOREIGN");
        assertEquals(1, reg.pending());
        assertEquals(Presentation.LOADING, reg.presentation());
        reg.end(fresh, true, null);
    }

    @Test
    void aFailureShowsTheErrorLookForFourSecondsThenReturnsToLoadingIfStillPending() {
        var a = reg.begin("a");
        var b = reg.begin("b");
        at(500);
        reg.end(a, false, "BOOM");
        assertEquals(Presentation.ERROR, reg.presentation(), "the failure is shown even though b continues");
        assertEquals("BOOM", reg.errorReason());
        at(500 + 3_999);
        assertEquals(Presentation.ERROR, reg.presentation());
        at(500 + 4_001);
        assertEquals(Presentation.LOADING, reg.presentation(), "back to loading: b is still real work");
        reg.end(b, true, null);
    }

    @Test
    void successDoesNotRaiseAnError() {
        var t = reg.begin("ok");
        at(500);
        assertEquals(Presentation.LOADING, reg.presentation()); // the frame loop saw it
        reg.end(t, true, null);
        at(600);
        assertNull(reg.errorReason());
        assertEquals(Presentation.LOADING, reg.presentation()); // minimum time, not an error
    }

    @Test
    void theFifteenSecondWatchdogOnlyChangesThePresentation() {
        var t = reg.begin("signing"); // e.g. a custody call whose result may still be UNKNOWN
        at(14_999);
        assertEquals(Presentation.LOADING, reg.presentation());
        at(15_000);
        assertEquals(Presentation.ERROR, reg.presentation(), "the look says it did not finish");
        assertEquals("DID_NOT_FINISH", reg.errorReason(), "never 'failed': the outcome is not known to the avatar");
        assertEquals(1, reg.pending(), "the operation is still registered: the watchdog never cancels, retries or completes it");
        at(15_000 + 4_001);
        assertEquals(Presentation.NONE, reg.presentation(), "the avatar stops claiming it is busy");
        reg.end(t, true, "LATE_RESULT"); // the real result still arrives and is accepted silently
        assertEquals(0, reg.pending());
        at(30_000);
        assertEquals(Presentation.NONE, reg.presentation());
    }

    @Test
    void clearAllDropsTheVisibleErrorAndEverythingPending() {
        var t = reg.begin("x");
        at(500);
        reg.end(t, false, "E");
        reg.begin("y");
        reg.clearAll();
        assertEquals(0, reg.pending());
        assertEquals(Presentation.NONE, reg.presentation());
    }
}
