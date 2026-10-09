package panel.accountview;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import panel.tradeview.DeskHarness;

class AccountReadTest {
    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "worker/FX callback did not complete");
    }

    @Test void slowReadDoesNotBlockFxAndOldReplyCannotReplaceTheNewRequest() throws Exception {
        AccountRead[] holder = new AccountRead[1];
        CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1), fresh = new CountDownLatch(1);
        AtomicInteger stale = new AtomicInteger();
        try {
            DeskHarness.fx(() -> {
                holder[0] = new AccountRead();
                holder[0].load(() -> {
                    assertFalse(Platform.isFxApplicationThread());
                    reached.countDown();
                    // A slow channel may ignore interruption. Its reply must still be fenced.
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    return "old";
                }, (value, failure) -> stale.incrementAndGet());
            });
            await(reached);
            DeskHarness.fx(() -> holder[0].load(() -> "new", (value, failure) -> {
                assertTrue(Platform.isFxApplicationThread());
                assertEquals("new", value);
                assertNull(failure);
                fresh.countDown();
            })); // This FX pulse completes while the old channel is still blocked.
            release.countDown();
            await(fresh);
            assertEquals(0, stale.get());
        } finally {
            release.countDown();
            DeskHarness.fx(() -> { if (holder[0] != null) holder[0].close(); });
        }
    }

    @Test void hidingOrDisposingDoesNotPublishAnInterruptedReadAsAnError() throws Exception {
        AccountRead[] holder = new AccountRead[1];
        CountDownLatch reached = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        DeskHarness.fx(() -> {
            holder[0] = new AccountRead();
            holder[0].load(() -> {
                reached.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { throw new IllegalStateException("read cancelled", e); }
                finally { finished.countDown(); }
                return "unreachable";
            }, (value, failure) -> callbacks.incrementAndGet());
        });
        await(reached);
        DeskHarness.fx(() -> { holder[0].cancel(); holder[0].close(); });
        await(finished);
        DeskHarness.fx(() -> assertEquals(0, callbacks.get()));
    }
}
