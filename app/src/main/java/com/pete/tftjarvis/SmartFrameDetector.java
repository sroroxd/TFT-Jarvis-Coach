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

    private static final double STABLE_MOTION_MAX = 0.035;
    private static final double FULL_CHANGE_MIN = 0.095;
    private static final double BOTTOM_CHANGE_MIN = 0.080;
    private static final double CENTER_CHANGE_MIN = 0.120;
    private static final double AFTER_COMBAT_CHANGE_MIN = 0.055;

    private byte[] previous;
    private byte[] lastAnalyzed;
    private long lastSampleAt;
    private long stableSince;
    private long lastCloudAt;

    synchronized boolean shouldSample(long now) {
        return now - lastSampleAt >= SAMPLE_INTERVAL_MS;
    }

    synchronized boolean observe(Image image, long now, String lastPhase) {
        if (!shouldSample(now)) return false;
        lastSampleAt = now;

        byte[] current = fingerprint(image);
        if (current == null) return false;

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
            stableSince = 0L;
            return false;
        }

        if (stableSince == 0L || now - stableSince < STABLE_FOR_MS) return false;
        if (now - lastCloudAt < MIN_CLOUD_INTERVAL_MS) return false;

        if (lastAnalyzed == null) {
            // Establish one baseline only after a clearly stable TFT screen.
            return now - stableSince >= 1200L;
        }

        double full = diff(lastAnalyzed, current, 0, GRID_Y);
        double bottom = diff(lastAnalyzed, current, (int) (GRID_Y * 0.64), GRID_Y);
        double center = diff(lastAnalyzed, current, (int) (GRID_Y * 0.18), (int) (GRID_Y * 0.82));

        if ("KAMPF".equals(lastPhase)) {
            return full >= AFTER_COMBAT_CHANGE_MIN;
        }
        if ("AUGMENTIERUNG".equals(lastPhase) || "KARUSSELL".equals(lastPhase)) {
            return full >= AFTER_COMBAT_CHANGE_MIN || center >= CENTER_CHANGE_MIN;
        }
        return full >= FULL_CHANGE_MIN || bottom >= BOTTOM_CHANGE_MIN || center >= CENTER_CHANGE_MIN;
    }

    synchronized void markAnalyzed(Image image, long now) {
        byte[] fp = fingerprint(image);
        if (fp != null) {
            lastAnalyzed = fp;
            previous = fp;
        }
        lastCloudAt = now;
        stableSince = now;
    }

    synchronized void markCloudAttempt(long now) {
        lastCloudAt = now;
    }

    synchronized void reset() {
        previous = null;
        lastAnalyzed = null;
        lastSampleAt = 0L;
        stableSince = 0L;
        lastCloudAt = 0L;
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
