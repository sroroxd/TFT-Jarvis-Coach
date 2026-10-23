package com.pete.tftjarvis;

import android.graphics.Bitmap;

/**
 * Phase-aware normalized TFT Mobile regions derived from real landscape screenshots.
 * The full frame is always sent as the source of truth; this class only provides
 * one optional zoom from the same frame to improve small HUD/text recognition.
 */
final class TftRoiSelector {
    static final class Selection {
        final Bitmap bitmap;
        final String label;

        Selection(Bitmap bitmap, String label) {
            this.bitmap = bitmap;
            this.label = label;
        }
    }

    private TftRoiSelector() {}

    static Selection select(Bitmap source, String lastPhase) {
        if (source == null) return null;
        String phase = lastPhase == null ? "UNKLAR" : lastPhase.trim().toUpperCase();

        switch (phase) {
            case "SHOP":
            case "PLANUNG":
                // Broad decision ROI on purpose: the next frame may already be an augment,
                // item/anvil or other choice screen while lastPhase still says PLANUNG/SHOP.
                // This avoids sending a stale shop-only crop on the first critical choice frame.
                return crop(source, 0.17f, 0.00f, 0.76f, 0.68f,
                        "Planung/Shop/Auswahl – phasenwechsel-sicherer Entscheidungsbereich");
            case "AUGMENTIERUNG":
                // Augment/item-choice cards are normally centered across the upper/middle screen.
                return crop(source, 0.22f, 0.00f, 0.76f, 0.57f,
                        "Augment-/Auswahlbereich");
            case "KAMPF":
                // Improve unit/item/position visibility without wasting pixels on outer HUD.
                return crop(source, 0.20f, 0.10f, 0.62f, 0.73f,
                        "Kampf – Boardzentrum");
            case "KARUSSELL":
                return crop(source, 0.12f, 0.06f, 0.76f, 0.84f,
                        "Karussell – zentrale Auswahl");
            default:
                // First/unclear/pre-match calls stay full-frame only to save free-tier budget.
                return null;
        }
    }

    private static Selection crop(
            Bitmap source,
            float left,
            float top,
            float width,
            float height,
            String label
    ) {
        int sw = source.getWidth();
        int sh = source.getHeight();
        if (sw <= 1 || sh <= 1) return null;

        int x = clamp(Math.round(sw * left), 0, sw - 1);
        int y = clamp(Math.round(sh * top), 0, sh - 1);
        int w = clamp(Math.round(sw * width), 1, sw - x);
        int h = clamp(Math.round(sh * height), 1, sh - y);

        if (w < 32 || h < 32) return null;
        return new Selection(Bitmap.createBitmap(source, x, y, w, h), label);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
