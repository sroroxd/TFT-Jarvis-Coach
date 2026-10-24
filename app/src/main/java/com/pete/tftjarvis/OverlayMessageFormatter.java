package com.pete.tftjarvis;

import java.util.Locale;

/**
 * Keeps internal evidence and audio diagnostics out of the normal in-game overlay.
 * Uncertain or empty results never become fabricated instructions.
 */
final class OverlayMessageFormatter {
    private OverlayMessageFormatter() {}

    static String message(String phase, String call) {
        String concise = call == null ? "" : call.trim().replaceAll("\\s+", " ");
        if ("VOR_MATCH".equals(phase)) {
            String lower = concise.toLowerCase(Locale.ROOT);
            if (lower.contains("warteschlange")) return "Warteschlange erkannt";
            if (lower.contains("homescreen")) return "TFT-Homescreen erkannt";
            if (concise.isEmpty() || lower.equals("warte auf matchstart")) {
                return "Warte auf Matchstart";
            }
        }
        if (concise.isEmpty()) return "Nicht sicher erkennbar";
        return concise;
    }

    static String detail(String phase, String call, String plan) {
        String concise = message(phase, call);
        if ("VOR_MATCH".equals(phase) || plan == null || plan.trim().isEmpty()) {
            return concise;
        }
        String next = plan.trim();
        if (next.equalsIgnoreCase(concise)) return concise;
        return concise + "\nNächster Schritt: " + next;
    }
}
