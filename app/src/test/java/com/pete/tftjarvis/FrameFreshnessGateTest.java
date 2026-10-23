package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;

public class FrameFreshnessGateTest {
    @Test public void recentScreenshotCanBeUsed() {
        long now = 100_000L;
        assertFalse(FrameFreshnessGate.shouldDiscard(now - 2_000L, now));
        assertFalse(FrameFreshnessGate.shouldDiscard(now - 20_000L, now));
    }

    @Test public void slowReplyIsRejectedInsteadOfProducingStaleAdvice() {
        assertTrue(FrameFreshnessGate.shouldDiscard(79_999L, 100_000L));
    }

    @Test public void futureTimestampFailsClosed() {
        assertTrue(FrameFreshnessGate.shouldDiscard(101_000L, 100_000L));
    }

    @Test public void unknownTimestampDoesNotCrashThePipeline() {
        assertFalse(FrameFreshnessGate.shouldDiscard(0L, 100_000L));
    }
}
