package com.pete.tftjarvis;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single-slot, latest-state manual trigger; never retains image bytes or API results.
 * Requests while processing are coalesced and replayed against a newly captured frame.
 */
final class ManualRequestLatch {
    private final AtomicBoolean pending = new AtomicBoolean(false);

    void enqueue() {
        pending.set(true);
    }

    boolean take() {
        return pending.getAndSet(false);
    }

    void clear() {
        pending.set(false);
    }
}
