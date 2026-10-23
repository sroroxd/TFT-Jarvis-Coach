package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;

public class ManualRequestLatchTest {
    @Test public void queuedRequestIsConsumedExactlyOnce() {
        ManualRequestLatch latch = new ManualRequestLatch();
        assertFalse(latch.take());
        latch.enqueue();
        assertTrue(latch.take());
        assertFalse(latch.take());
    }

    @Test public void repeatedRequestsCoalesceWithoutUnboundedQueue() {
        ManualRequestLatch latch = new ManualRequestLatch();
        latch.enqueue();
        latch.enqueue();
        latch.enqueue();
        assertTrue(latch.take());
        assertFalse(latch.take());
    }

    @Test public void endingSessionClearsTheQueue() {
        ManualRequestLatch latch = new ManualRequestLatch();
        latch.enqueue();
        latch.clear();
        assertFalse(latch.take());
    }
}
