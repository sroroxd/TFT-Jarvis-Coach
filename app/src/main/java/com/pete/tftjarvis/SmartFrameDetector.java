package com.pete.tftjarvis;

import android.media.Image;

import java.nio.ByteBuffer;

/**
 * Lightweight on-device event detector.
 *
 * It samples a tiny luminance grid directly from the MediaProjection frame.
 * No image is uploaded unless the screen has become stable and materially
 * changed since the last cloud analysis.
 */
final class SmartFrameDetector {
    private static final int GRID_X = 24;
    private static final int GRID_Y = 14;

    private static final long SAMPLE_INTERVAL_MS = 550L;
    private static final long STABLE_FOR_MS = 800L;
    private static final long MIN_CLOUD_INTERVAL_MS = 7000L;
    private static final int MAX_AUTO_RETRIES = 2;
    private static final double FAILED_FRAME_CHANGE_MIN = 0.035;

    private static final double STABLE_MOTION_MAX = 0.035;
    private static final double FULL_CHANGE_MIN = 0.095;
    private static final double BOTTOM_CHANGE_MIN = 0.080;
    private static final double CENTER_CHANGE_MIN = 0.120;
    private static final double AFTER_COMBAT_CHANGE_MIN = 0.055;
    private static final double PENDING_FULL_CHANGE_MIN = 0.035;
    private static final double PENDING_BOTTOM_CHANGE_MIN = 0.030;
    private static final double PENDING_CENTER_CHANGE_MIN = 0.040;

    private byte[] previous;
    private byte[] lastAnalyzed;
    private byte[] pendingAnalyzed;
    private long lastSampleAt;
    private long stableSince;
    private long lastCloudAt;
    private boolean pendingChange;
    private boolean retryAfterFailure;
    private int consecutiveFailures;
    private byte[] lastFailedFingerprint;
    private long retryNotBefore;

    synchronized boolean shouldSample(long now) {
        return now - lastSampleAt >= SAMPLE_INTERVAL_MS;
    }

    synchronized boolean observe(Image image, long now, String lastPhase) {
        // Keep native image fingerprint work throttled to 550ms.
        if (!shouldSample(now)) return false;
        return observeFingerprint(fingerprint(image), now, lastPhase);
    }

    /**
     * Test seam for privacy-safe offline traces. Each synthetic/recorded fingerprint
     * has one luminance byte per 24x14 cell; no screenshots or model API calls.
     */
    synchronized boolean observeFingerprint(byte[] current, long now, String lastPhase) {
        if (!shouldSample(now)) return false;
        lastSampleAt = now;
        if (current == null || current.length != GRID_X * GRID_Y) return false;

        if (previous == null) {
            previous = current;
            stableSince = now;
            return false;
        }

        double motion = diff(previous, current, 0, GRID_Y);
        previous = current;

        if (motion <= STABLE_MOTION_MAX) {
            if (stableSince == 0L) stableSince = now;
        } else {
            // Remember that something changed, but do not upload while the screen is moving.
            // We wait until it settles and then verify that the final stable frame differs
            // meaningfully from the last analyzed state.
            pendingChange = true;
            stableSince = 0L;
            return false;
        }

        if (stableSince == 0L || now - stableSince < STABLE_FOR_MS) return false;
        if (now < retryNotBefore) return false;
        long retryDelay = retryAfterFailure
                ? MIN_CLOUD_INTERVAL_MS * Math.max(1, consecutiveFailures)
                : MIN_CLOUD_INTERVAL_MS;
        if (now - lastCloudAt < retryDelay) return false;

        if (lastFailedFingerprint != null) {
            // Cloud failure must not permanently freeze a static scene, but an
            // unchanged failed frame may be retried only twice with backoff.
            boolean changed = diff(lastFailedFingerprint, current, 0, GRID_Y)
                    >= FAILED_FRAME_CHANGE_MIN;
            if (changed) {
                consecutiveFailures = 0;
                retryAfterFailure = false;
                lastFailedFingerprint = null;
                retryNotBefore = 0L;
            } else if (retryAfterFailure) {
                retryAfterFailure = false;
                pendingChange = false;
                return true;
            } else if (consecutiveFailures > MAX_AUTO_RETRIES) {
                // Stop infinite calls until the picture genuinely changes.
                return false;
            }
        }

        if (lastAnalyzed == null) {
            // Establish one baseline only after a clearly stable TFT screen.
            return now - stableSince >= 1200L;
        }

        double full = diff(lastAnalyzed, current, 0, GRID_Y);
        double bottom = diff(lastAnalyzed, current, (int) (GRID_Y * 0.64), GRID_Y);
        double center = diff(lastAnalyzed, current, (int) (GRID_Y * 0.18), (int) (GRID_Y * 0.82));

        boolean meaningful;
        if ("VOR_MATCH".equals(lastPhase)) {
            // A loading screen or first planning round can differ less than animated
            // home-screen banners. Use the central playfield and require a stable frame.
            meaningful = full >= 0.085 || center >= 0.095;
        } else if ("ERGEBNIS".equals(lastPhase)) {
            meaningful = full >= 0.110 || center >= 0.120;
        } else if ("KAMPF".equals(lastPhase)) {
            meaningful = full >= AFTER_COMBAT_CHANGE_MIN;
        } else if ("AUGMENTIERUNG".equals(lastPhase) || "KARUSSELL".equals(lastPhase)) {
            meaningful = full >= AFTER_COMBAT_CHANGE_MIN || center >= CENTER_CHANGE_MIN;
        } else {
            meaningful = full >= FULL_CHANGE_MIN
                    || bottom >= BOTTOM_CHANGE_MIN
                    || center >= CENTER_CHANGE_MIN;
        }

        if (!meaningful && pendingChange
                && !"ERGEBNIS".equals(lastPhase)
                && !"VOR_MATCH".equals(lastPhase)) {
            // A short animation/input may leave only a subtle persistent UI change.
            // Use lower thresholds once, after the frame has settled. Pure transient
            // motion that returns to the old frame is discarded instead of spamming cloud.
            meaningful = full >= PENDING_FULL_CHANGE_MIN
                    || bottom >= PENDING_BOTTOM_CHANGE_MIN
                    || center >= PENDING_CENTER_CHANGE_MIN;
        }

        if (meaningful) {
            pendingChange = false;
            return true;
        }

        // The movement was transient and the final stable screen is effectively unchanged.
        pendingChange = false;
        return false;
    }

    /**
     * Queue the clean frame used for a cloud request. It becomes the new
     * baseline only after the cloud analysis succeeds.
     */
    synchronized void markAnalyzed(Image image, long now) {
        markAnalyzedFingerprint(fingerprint(image), now);
    }

    synchronized void markAnalyzedFingerprint(byte[] fp, long now) {
        if (fp != null && fp.length == GRID_X * GRID_Y) {
            pendingAnalyzed = fp.clone();
            previous = fp.clone();
        }
        stableSince = now;
        pendingChange = false;
    }

    synchronized void commitAnalyzed(long now) {
        if (pendingAnalyzed != null) {
            lastAnalyzed = pendingAnalyzed;
            previous = pendingAnalyzed;
        }
        pendingAnalyzed = null;
        lastCloudAt = now;
        stableSince = now;
        pendingChange = false;
        consecutiveFailures = 0;
        retryAfterFailure = false;
        lastFailedFingerprint = null;
        retryNotBefore = 0L;
    }

    synchronized void discardPendingAnalysis() {
        discardPendingAnalysis(System.currentTimeMillis());
    }

    synchronized void discardPendingAnalysis(long now) {
        if (pendingAnalyzed != null) {
            if (lastFailedFingerprint == null
                    || diff(lastFailedFingerprint, pendingAnalyzed, 0, GRID_Y)
                    >= FAILED_FRAME_CHANGE_MIN) {
                consecutiveFailures = 0;
            }
            lastFailedFingerprint = pendingAnalyzed;
            consecutiveFailures++;
            retryAfterFailure = consecutiveFailures <= MAX_AUTO_RETRIES;
            lastCloudAt = now;
        }
        pendingAnalyzed = null;
        stableSince = 0L;
        pendingChange = true;
    }

    synchronized void deferRetryUntil(long notBefore) {
        // If Smart-Auto fired just before a rate limit was applied, keep its
        // bounded retry instead of silently consuming it without a screenshot.
        if (lastFailedFingerprint != null
                && consecutiveFailures > 0
                && consecutiveFailures <= MAX_AUTO_RETRIES) {
            retryAfterFailure = true;
            retryNotBefore = Math.max(retryNotBefore, notBefore);
        }
    }

    synchronized void markCloudAttempt(long now) {
        lastCloudAt = now;
    }

    synchronized void reset() {
        previous = null;
        lastAnalyzed = null;
        pendingAnalyzed = null;
        lastSampleAt = 0L;
        stableSince = 0L;
        lastCloudAt = 0L;
        pendingChange = false;
        consecutiveFailures = 0;
        retryAfterFailure = false;
        lastFailedFingerprint = null;
        retryNotBefore = 0L;
    }

    private static byte[] fingerprint(Image image) {
        try {
            if (image == null || image.getPlanes() == null || image.getPlanes().length == 0) return null;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int width = image.getWidth();
            int height = image.getHeight();
            if (pixelStride < 3 || rowStride <= 0 || width <= 0 || height <= 0) return null;

            byte[] out = new byte[GRID_X * GRID_Y];
            for (int gy = 0; gy < GRID_Y; gy++) {
                int y = Math.min(height - 1, (int) (((gy + 0.5) * height) / GRID_Y));
                for (int gx = 0; gx < GRID_X; gx++) {
                    int x = Math.min(width - 1, (int) (((gx + 0.5) * width) / GRID_X));
                    int offset = y * rowStride + x * pixelStride;
                    if (offset + 2 >= buffer.limit()) return null;
                    int r = buffer.get(offset) & 0xff;
                    int g = buffer.get(offset + 1) & 0xff;
                    int b = buffer.get(offset + 2) & 0xff;
                    int luma = (r * 54 + g * 183 + b * 19) >> 8;
                    out[gy * GRID_X + gx] = (byte) luma;
                }
            }
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double diff(byte[] a, byte[] b, int rowStart, int rowEnd) {
        if (a == null || b == null || a.length != b.length) return 1.0;
        int start = Math.max(0, rowStart) * GRID_X;
        int end = Math.min(GRID_Y, rowEnd) * GRID_X;
        if (end <= start) return 0.0;

        long sum = 0L;
        int count = 0;
        for (int i = start; i < end; i++) {
            sum += Math.abs((a[i] & 0xff) - (b[i] & 0xff));
            count++;
        }
        return count == 0 ? 0.0 : (sum / (double) count) / 255.0;
    }
}
