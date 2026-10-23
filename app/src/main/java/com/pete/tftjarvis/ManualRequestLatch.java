package com.pete.tftjarvis;

import java.util.concurrent.atomic.AtomicBoolean;

/** A one-slot manual trigger, never stores screenshots or model responses. */
final class ManualRequestLatch {
    private final AtomicBoolean pending = new AtomicBoolean();

    void enqueue() { pending.set(true); }
    boolean take() { return pending.getAndSet(false); }
    void clear() { pending.set(false); }
}
