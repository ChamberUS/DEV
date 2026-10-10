package panel.ipc.contracts;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Synthetic verifier outcomes ONLY; no attestation, pairing or authorized session is simulated. */
class IdentityGateTest {
    private static IdentityGate.Evidence evidence(long generation) {
        return new IdentityGate.Evidence(generation,"synthetic-instance-A",Set.of());
    }
    private static IdentityGate.Verifier verified(long until) {
        return e -> new IdentityGate.Result(IdentityGate.Status.VERIFIED,e,until);
    }
    @Test void onlyExplicitVerifiedCurrentBoundEvidenceSatisfiesIdentityPrerequisite() {
        assertTrue(IdentityGate.evaluate(verified(20), evidence(1),1,10).identitySatisfied());
        for (var status : IdentityGate.Status.values()) {
            if (status == IdentityGate.Status.VERIFIED) continue;
            var decision = IdentityGate.evaluate(e -> new IdentityGate.Result(status,e,20),evidence(1),1,10);
            assertEquals(status,decision.status()); assertFalse(decision.identitySatisfied());
        }
    }
    @Test void noVerifierOrEvidenceNeverPassesAndCannotCallVerifier() {
        assertEquals(IdentityGate.Status.UNSUPPORTED,IdentityGate.evaluate(null,evidence(1),1,10).status());
        assertEquals(IdentityGate.Status.MISSING,IdentityGate.evaluate(e -> {fail("no evidence");return null;},null,1,10).status());
    }
    @Test void userPidPathFilesAndConnectivityAreNotApplicationIdentity() {
        for (var observation : IdentityGate.Observation.values()) {
            var hints = new IdentityGate.Evidence(1,"synthetic-instance-A",Set.of(observation));
            assertFalse(IdentityGate.evaluate(null,hints,1,10).identitySatisfied());
            assertFalse(IdentityGate.evaluate(e -> new IdentityGate.Result(IdentityGate.Status.UNTRUSTED_APPLICATION,e,20),hints,1,10).identitySatisfied());
        }
    }
    @Test void invalidAndStaleEvidenceIsDeniedBeforeCallingVerifier() {
        var calls = new AtomicInteger(); IdentityGate.Verifier verifier = e -> { calls.incrementAndGet();return verified(20).verify(e); };
        for (long generation : new long[]{0,-1}) assertEquals(IdentityGate.Status.INVALID,IdentityGate.evaluate(verifier,evidence(generation),1,10).status());
        assertEquals(IdentityGate.Status.INVALID,IdentityGate.evaluate(verifier,evidence(1),0,10).status());
        assertEquals(IdentityGate.Status.INVALID,IdentityGate.evaluate(verifier,new IdentityGate.Evidence(1,"",Set.of()),1,10).status());
        assertEquals(IdentityGate.Status.STALE,IdentityGate.evaluate(verifier,evidence(1),2,10).status());
        assertEquals(0,calls.get());
    }
    @Test void expiredOrSubstitutedVerifierBindingCannotPass() {
        assertEquals(IdentityGate.Status.STALE,IdentityGate.evaluate(verified(10),evidence(1),1,10).status());
        assertEquals(IdentityGate.Status.STALE,IdentityGate.evaluate(verified(9),evidence(1),1,10).status());
        var other = new IdentityGate.Evidence(1,"synthetic-instance-B",Set.of());
        assertEquals(IdentityGate.Status.INVALID,IdentityGate.evaluate(e -> new IdentityGate.Result(IdentityGate.Status.VERIFIED,other,20),evidence(1),1,10).status());
    }
    @Test void malformedOrThrowingVerifierFailsClosedWithoutLeakingExceptionText() {
        for (IdentityGate.Verifier verifier : new IdentityGate.Verifier[]{e -> null,e -> new IdentityGate.Result(null,e,20),e -> {throw new IllegalStateException("untrusted detail");},e -> {throw new UnsatisfiedLinkError("unsupported adapter");}}) {
            assertEquals(IdentityGate.Status.INVALID,IdentityGate.evaluate(verifier,evidence(1),1,10).status());
        }
    }
    @Test void evidenceObservationsAreImmutableAndRestartDoesNotVerifyNewPeer() {
        var fence = new GenerationFence(); var old = evidence(fence.current());
        assertThrows(UnsupportedOperationException.class, () -> old.observations().add(IdentityGate.Observation.PID));
        assertTrue(IdentityGate.evaluate(verified(20),old,fence.current(),10).identitySatisfied());
        fence.restart();
        assertFalse(IdentityGate.evaluate(verified(20),old,fence.current(),10).identitySatisfied());
        assertFalse(IdentityGate.evaluate(null,evidence(fence.current()),fence.current(),10).identitySatisfied());
    }
}
