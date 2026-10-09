package byx.service.signer;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic clock and exact-instance model: no sleeps or deadline relaxation. */
class CustodyDeadlineTest {
    @TempDir Path directory;
    static final class Clock implements LongSupplier {
        long now;
        public long getAsLong() { return now; }
        void advance(long millis) { now += TimeUnit.MILLISECONDS.toNanos(millis); }
    }
    record Instance(long pid, long version, String auditIdentity) { }
    static final class Processes implements CustodyAuthority.ProcessControl {
        final Clock clock;
        final Instance prior = new Instance(100, 42, "kernel-instance-A");
        Instance live = prior;
        boolean trusted = true, observable = true, signalWorks = true, exitProven = true, ambiguous;
        long discoveryMillis, trustMillis, signalMillis, proofMillis, receivedBudget;
        int signals;
        Processes(Clock clock) { this.clock = clock; }
        public List<Object> discover() {
            clock.advance(discoveryMillis);
            if (ambiguous) { throw new IllegalStateException("AMBIGUOUS"); }
            return List.of(prior);
        }
        public Object identify(long pid) { return prior; }
        public boolean trusted(Object instance) { clock.advance(trustMillis); return trusted && instance.equals(live); }
        public boolean gone(Object instance) {
            clock.advance(proofMillis);
            if (!observable) { throw new IllegalStateException("UNPROVEN"); }
            return !instance.equals(live);
        }
        public boolean terminate(Object instance) {
            assertEquals(prior, instance);
            signals++; clock.advance(signalMillis);
            if (signalWorks && exitProven && instance.equals(live)) { live = null; }
            return signalWorks;
        }
        public boolean terminate(Object instance, CustodyAuthority.Deadline deadline) throws Exception {
            deadline.require(); receivedBudget = deadline.remaining(); return terminate(instance);
        }
        public long pid(Object instance) { return ((Instance) instance).pid(); }
        public long version(Object instance) { return ((Instance) instance).version(); }
    }
    CustodyAuthority authority(Processes p) { return new CustodyAuthority(p, directory, null); }
    CustodyAuthority.Deadline operation(Clock c) { return CustodyAuthority.Deadline.after(c, TimeUnit.MILLISECONDS.toNanos(CustodyClient.CALL_TIMEOUT_MS)); }
    void blocked(CustodyAuthority a, org.junit.jupiter.api.function.Executable action) {
        assertEquals(CustodyAuthority.UNPROVEN, assertThrows(CustodyClient.CustodyException.class, action).code());
        assertEquals(CustodyAuthority.UNPROVEN, assertThrows(CustodyClient.CustodyException.class, a::begin).code());
    }
    @Test void normalGracefulExitHasNoSignalAndComfortableMargin() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100);
        var d = operation(c); c.advance(250); p.live = null; a.finish(false, d);
        assertEquals(0, p.signals); assertTrue(d.remaining() > TimeUnit.SECONDS.toNanos(11)); assertNotNull(a.begin());
    }
    @Test void stoppedHelperForcedExternallyAndExactAbsencePrecedesNextBegin() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100);
        p.signalMillis = 10; var d = operation(c); c.advance(9000); a.finish(true, d);
        assertEquals(1, p.signals); assertNull(p.live); assertTrue(d.remaining() > 0); assertNotNull(a.begin());
    }
    @Test void staleAfterCrashIsFencedBeforeOperation() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.startup(operation(c));
        assertTrue(a.previousResultUnknown()); assertEquals(1, p.signals); assertNull(p.live); assertNotNull(a.begin());
    }
    @Test void failedSignalNeverEnablesMutation() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100); p.signalWorks = false;
        blocked(a, () -> a.finish(true, operation(c))); assertEquals(p.prior, p.live);
    }
    @Test void acknowledgedSignalWithoutExitProofNeverEnablesMutation() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100); p.exitProven = false;
        blocked(a, () -> a.finish(true, operation(c))); assertEquals(1, p.signals); assertEquals(p.prior, p.live);
    }
    @Test void identityMismatchDoesNotSignalAndBlocks() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100); p.trusted = false;
        blocked(a, () -> a.finish(true, operation(c))); assertEquals(0, p.signals);
    }
    @Test void pidVersionReuseNeverSignalsReplacement() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100);
        p.live = new Instance(100, 43, "kernel-instance-B"); a.finish(true, operation(c));
        assertEquals(0, p.signals); assertEquals(43, p.live.version()); assertNotNull(a.begin());
    }
    @Test void peerPidVersionMismatchBlocksNextBegin() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100);
        blocked(a, () -> a.peer(100, 43)); assertEquals(0, p.signals);
    }
    @Test void ambiguousDiscoveryBlocksBeforeSignal() {
        var c = new Clock(); var p = new Processes(c); p.ambiguous = true; var a = authority(p);
        blocked(a, () -> a.startup(operation(c))); assertEquals(0, p.signals);
    }
    @Test void unavailableExactInstanceStateBlocksBeforeSignal() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100); p.observable = false;
        blocked(a, () -> a.finish(true, operation(c))); assertEquals(0, p.signals);
    }
    @Test void expiredDiscoveryCannotBecomeQuiescent() {
        var c = new Clock(); var p = new Processes(c); p.discoveryMillis = 12000; var a = authority(p);
        blocked(a, () -> a.startup(operation(c))); assertEquals(0, p.signals);
    }
    @Test void expiredOperationBlocksEvenIfProcessJustExited() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100); var d = operation(c);
        c.advance(12000); p.live = null; blocked(a, () -> a.finish(false, d)); assertEquals(0, p.signals);
    }
    @Test void slowTrustCannotRenewThreeSecondFencingBudget() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100);
        p.trustMillis = 2500; p.signalMillis = 600;
        blocked(a, () -> a.finish(true, operation(c)));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(500), p.receivedBudget); assertNull(p.live);
    }
    @Test void lateAbsenceProofStillFailsClosed() throws Exception {
        var c = new Clock(); var p = new Processes(c); var a = authority(p); a.attach(100); p.proofMillis = 1600;
        blocked(a, () -> a.finish(true, operation(c))); assertEquals(1, p.signals);
    }
    @Test void reserveIsPartOfOriginalBudgetAndNeverRenewed() throws Exception {
        var c = new Clock(); var d = operation(c); assertEquals(TimeUnit.SECONDS.toNanos(9), d.exchangeWait());
        c.advance(1000); assertEquals(TimeUnit.SECONDS.toNanos(8), d.exchangeWait());
        c.advance(8500); assertEquals(TimeUnit.MILLISECONDS.toNanos(2500), d.exitProof().remaining());
        assertThrows(CustodyClient.CustodyException.class, d::exchangeWait);
    }
    @Test void nanoTimeWrapDoesNotExtendBudget() throws Exception {
        var c = new Clock(); c.now = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1); var d = operation(c);
        c.advance(1000); assertEquals(TimeUnit.SECONDS.toNanos(11), d.remaining());
        c.advance(11000); assertThrows(CustodyClient.CustodyException.class, d::require);
    }
    @Test void repeatedFencesLeaveNoOldInstanceAndHaveIndependentExplicitOperations() throws Exception {
        var c = new Clock();
        for (int i = 0; i < 100; i++) {
            var p = new Processes(c); var a = authority(p); a.attach(100); p.signalMillis = 10;
            var d = operation(c); a.finish(true, d); assertNull(p.live); assertNotNull(a.begin());
            assertEquals(1, p.signals); assertTrue(d.remaining() >= TimeUnit.MILLISECONDS.toNanos(11990));
        }
    }
    @Test void bootstrapIsHalfOriginalBudgetAndDoesNotConsumeExitProofReserve() throws Exception {
        var c = new Clock(); var d = operation(c); var bootstrap = d.bootstrap();
        assertEquals(TimeUnit.SECONDS.toNanos(6), bootstrap.remaining());
        c.advance(6000); assertEquals(0, bootstrap.remaining());
        assertEquals(TimeUnit.SECONDS.toNanos(6), d.remaining());
        assertEquals(TimeUnit.SECONDS.toNanos(3), d.exitProof().remaining());
    }
    @Test void bootstrapIncludesLaunchAndAttachAndNeverRenewsTheirTime() throws Exception {
        var c = new Clock(); var d = operation(c); var bootstrap = d.bootstrap();
        c.advance(1250); assertEquals(TimeUnit.MILLISECONDS.toNanos(4750), bootstrap.remaining());
        c.advance(4750); assertThrows(CustodyClient.CustodyException.class, bootstrap::require);
        assertTrue(d.remaining() >= CustodyAuthority.Deadline.EXIT_PROOF_NANOS);
    }
    @Test void lateBootstrapIsClippedBySameOperationDeadlineAndProofReserve() throws Exception {
        var c = new Clock(); var d = operation(c); c.advance(8000); var bootstrap = d.bootstrap();
        assertEquals(TimeUnit.SECONDS.toNanos(1), bootstrap.remaining());
        c.advance(1000); assertEquals(TimeUnit.SECONDS.toNanos(3), d.remaining());
        assertThrows(CustodyClient.CustodyException.class, d::bootstrap);
    }

}
