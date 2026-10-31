package com.pete.tftjarvis;

/**
 * Local admission control for Android SpeechRecognizer starts.
 * This limits fast create/destroy loops; it does NOT make SpeechRecognizer
 * a true streaming microphone, nor guarantee avoidance of provider quotas.
 */
final class JarvisSpeechStartGuard {
    static final long MIN_START_GAP_MS = 8000L;
    static final long QUOTA_COOLDOWN_MS = 120000L;

    private long nextStartAtElapsedMs;

    long waitBeforeStart(long elapsedMs) {
        return Math.max(0L, nextStartAtElapsedMs - Math.max(0L, elapsedMs));
    }

    void recordStart(long elapsedMs) {
        long now = Math.max(0L, elapsedMs);
        nextStartAtElapsedMs = Math.max(nextStartAtElapsedMs, now + MIN_START_GAP_MS);
    }

    void quotaExceeded(long elapsedMs) {
        blockFor(elapsedMs, QUOTA_COOLDOWN_MS);
    }

    void blockFor(long elapsedMs, long delayMs) {
        long now = Math.max(0L, elapsedMs);
        nextStartAtElapsedMs = Math.max(nextStartAtElapsedMs,
                now + Math.max(0L, Math.min(delayMs, QUOTA_COOLDOWN_MS)));
    }
}
