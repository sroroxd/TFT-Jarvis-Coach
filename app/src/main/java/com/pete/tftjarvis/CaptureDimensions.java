package com.pete.tftjarvis;

/** Validates projection geometry before resizing native ImageReader buffers. */
final class CaptureDimensions {
    // MediaProjection can capture a smaller app window or the entire display.
    private static final int MIN_DIMENSION = 32;
    private static final int MAX_DIMENSION = 8192;

    private CaptureDimensions() {}

    static boolean valid(int width, int height) {
        return width >= MIN_DIMENSION && height >= MIN_DIMENSION
                && width <= MAX_DIMENSION && height <= MAX_DIMENSION;
    }

    /**
     * The TFT Mobile board is shown in landscape. Never interpret a portrait
     * app window or a severely letterboxed stream as a calibrated TFT HUD.
     */
    static boolean isCalibratedLandscape(int width, int height) {
        return valid(width, height) && width >= height * 1.4;
    }

    static boolean shouldResize(int currentWidth, int currentHeight,
                                int capturedWidth, int capturedHeight) {
        return valid(capturedWidth, capturedHeight)
                && (currentWidth != capturedWidth || currentHeight != capturedHeight);
    }
}
