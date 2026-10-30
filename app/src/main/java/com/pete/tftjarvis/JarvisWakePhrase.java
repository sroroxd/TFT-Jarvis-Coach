package com.pete.tftjarvis;

import java.util.Locale;

/** Local, deterministic wake phrase, before any optional chargeable cloud call. */
final class JarvisWakePhrase {
    private JarvisWakePhrase() {}

    /** Returns null when the phrase did not address JARVIS. Empty string = wake only. */
    static String extract(String phrase) {
        if (phrase == null) return null;
        String s = phrase.trim();
        if (s.isEmpty()) return null;
        s = s.replaceFirst("(?i)^(hey|hallo)\\s+", "");
        if (!s.toLowerCase(Locale.GERMAN).matches("^jarvis(?:\\b|[,:!?-]).*")) return null;
        return s.replaceFirst("(?i)^jarvis[\\s,:!?-]*", "").trim();
    }

    static boolean autoAiAllowed(boolean consent, boolean configured,
                                 int requests, long now, long lastRequest) {
        return consent && configured && requests < 12
                && (lastRequest == 0L || now - lastRequest >= 6000L);
    }
}
