package byx.service.signer;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CustodyOriginChecksTest {
    @Test void independentChecksOverlapAndBothMustFinish() throws Exception {
        var liveStarted = new CountDownLatch(1); var parentStarted = new CountDownLatch(1);
        assertTrue(CustodyClient.verifyBoth(() -> {
            liveStarted.countDown(); assertTrue(parentStarted.await(3, TimeUnit.SECONDS)); return true;
        }, () -> {
            parentStarted.countDown(); assertTrue(liveStarted.await(3, TimeUnit.SECONDS)); return true;
        }, CustodyAuthority.Deadline.operation()));
    }
    @Test void eitherFailureDeniesEvenIfOtherCheckPasses() throws Exception {
        for (boolean liveOk : new boolean[] {false, true}) {
            var count = new AtomicInteger();
            assertFalse(CustodyClient.verifyBoth(() -> { count.incrementAndGet(); return liveOk; },
                    () -> { count.incrementAndGet(); return !liveOk; }, CustodyAuthority.Deadline.operation()));
            assertEquals(2, count.get());
        }
    }
    @Test void eitherExceptionCannotAuthorize() {
        assertThrows(java.util.concurrent.ExecutionException.class, () -> CustodyClient.verifyBoth(
                () -> { throw new java.io.IOException(); }, () -> true, CustodyAuthority.Deadline.operation()));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> CustodyClient.verifyBoth(
                () -> true, () -> { throw new java.io.IOException(); }, CustodyAuthority.Deadline.operation()));
    }
    @Test void expirationDuringChecksCannotAuthorizeOrRenewBudget() {
        var now = new java.util.concurrent.atomic.AtomicLong();
        var deadline = CustodyAuthority.Deadline.after(now::get, TimeUnit.SECONDS.toNanos(12));
        assertEquals(CustodyAuthority.UNPROVEN, assertThrows(CustodyClient.CustodyException.class,
                () -> CustodyClient.verifyBoth(() -> { now.set(TimeUnit.SECONDS.toNanos(12)); return true; },
                        () -> true, deadline)).code());
        assertEquals(0, deadline.remaining());
    }
    @Test void repeatedChecksAreFreshRatherThanCached() throws Exception {
        var calls = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            assertTrue(CustodyClient.verifyBoth(() -> { calls.incrementAndGet(); return true; },
                    () -> { calls.incrementAndGet(); return true; }, CustodyAuthority.Deadline.operation()));
        }
        assertEquals(40, calls.get());
    }
}
