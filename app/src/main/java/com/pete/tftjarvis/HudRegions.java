package com.pete.tftjarvis;

/** Normalized portrait-independent regions for the TFT Mobile landscape HUD. */
final class HudRegions {
    static final class Region {
        final float left, right, top, bottom, expectedX, expectedY;

        Region(float left, float right, float top, float bottom,
               float expectedX, float expectedY) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.expectedX = expectedX;
            this.expectedY = expectedY;
        }

        boolean contains(float x, float y) {
            return x >= left && x <= right && y >= top && y <= bottom;
        }
    }

    // S24 Ultra landscape TFT screenshots: level badge x ~= .080, y ~= .94;
    // adjacent XP-cost "4" is x ~= .052 and MUST be excluded.
    static final Region OWN_LEVEL = new Region(
            0.065f, 0.110f, 0.875f, 0.990f, 0.080f, 0.942f);

    // Coin counter within the round gold badge at bottom right.
    static final Region OWN_GOLD = new Region(
            0.920f, 0.995f, 0.820f, 0.980f, 0.961f, 0.901f);

    private HudRegions() {}
}
