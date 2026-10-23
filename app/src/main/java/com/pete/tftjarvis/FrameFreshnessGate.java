package com.pete.tftjarvis;

/**
 * Rejects cloud responses for screenshots that are no longer timely enough
 * for concrete in-game decisions. Uses the capture timestamp, not response time.
 */
final class FrameFreshnessGate {
    static final long MAX_FRAME_AGE_MS = 20_000L;

    private FrameFreshnessGate() {}

    static boolean shouldDiscard(long frameObservedAt, long now) {
        // An absent timestamp provides no evidence that the image is current.
        // Fail closed instead of issuing a potentially outdated in-game call.
        return frameObservedAt <= 0L
                || now < frameObservedAt
                || now - frameObservedAt > MAX_FRAME_AGE_MS;
    }
}
