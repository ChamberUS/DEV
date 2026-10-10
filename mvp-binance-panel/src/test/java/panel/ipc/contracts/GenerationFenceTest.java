package panel.ipc.contracts;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GenerationFenceTest {
    @Test void currentGenerationAcceptedButInvalidOldOrFutureAreRejected() {
        var fence = new GenerationFence(2);
        assertTrue(fence.isCurrent(2));
        for (long candidate : new long[]{-1,0,1,3}) assertFalse(fence.isCurrent(candidate));
        assertThrows(IllegalArgumentException.class, () -> new GenerationFence(0));
        assertThrows(IllegalArgumentException.class, () -> new GenerationFence(-1));
    }
    @Test void restartInvalidatesSessionLeaseAndLateResponse() {
        var fence = new GenerationFence(); long sessionLease=fence.current(), pendingResponse=fence.current();
        assertEquals(2,fence.restart());
        assertFalse(fence.isCurrent(sessionLease)); assertFalse(fence.isCurrent(pendingResponse));
        assertTrue(fence.isCurrent(2));
    }
    @Test void duplicateAndNonIncreasingSequenceRejectedWithoutAdvancingState() {
        var fence = new GenerationFence();
        assertFalse(fence.acceptEvent(1,0)); assertFalse(fence.acceptEvent(1,-1));
        assertTrue(fence.acceptEvent(1,2));
        assertFalse(fence.acceptEvent(1,2)); assertFalse(fence.acceptEvent(1,1));
        assertFalse(fence.acceptEvent(2,100));
        assertTrue(fence.acceptEvent(1,3));
    }
    @Test void streamSequenceResetsOnlyForNewGenerationAndOldEventsStayRejected() {
        var fence = new GenerationFence(); assertTrue(fence.acceptEvent(1,50));
        fence.restart(); assertFalse(fence.acceptEvent(1,51));
        assertTrue(fence.acceptEvent(2,1)); assertFalse(fence.acceptEvent(2,1));
    }
    @Test void closeIsIdempotentAndInvalidatesEvenCurrentGeneration() {
        var fence = new GenerationFence(); fence.close();fence.close();
        assertFalse(fence.isCurrent(1));assertFalse(fence.acceptEvent(1,1));
        assertThrows(IllegalStateException.class,fence::restart);
    }
    @Test void generationExhaustionClosesFenceRatherThanWrappingOrKeepingLeaseLive() {
        var fence = new GenerationFence(Long.MAX_VALUE);assertTrue(fence.isCurrent(Long.MAX_VALUE));
        assertThrows(IllegalStateException.class,fence::restart);
        assertFalse(fence.isCurrent(Long.MAX_VALUE));assertFalse(fence.acceptEvent(Long.MAX_VALUE,1));
    }
}
