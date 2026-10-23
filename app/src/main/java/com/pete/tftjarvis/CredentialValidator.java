package com.pete.tftjarvis;

import java.util.Locale;

/**
 * Rejects values that are unsafe or obviously not API credentials before they
 * can ever be used in HTTP headers.
 */
final class CredentialValidator {
    private CredentialValidator() {}

    static String normalizeApiKey(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.isEmpty() || v.length() > 256) return "";
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c <= 0x20 || c >= 0x7f) return "";
        }
        return v;
    }

    static boolean isValidApiKey(String value) {
        return !normalizeApiKey(value).isEmpty();
    }

    static String normalizeModel(String value, String fallback) {
        if (value == null) return fallback;
        String v = value.trim();
        if (v.isEmpty()) return fallback;
        if (v.length() > 128) return fallback;
        if (!v.matches("[A-Za-z0-9._:-]+")) return fallback;
        return v;
    }

    static String invalidKeyMessage(String provider) {
        String p = provider == null ? "" : provider.toUpperCase(Locale.ROOT);
        return p.contains("OPENAI")
                ? "OpenAI API-Key ungültig – in der App neu eintragen"
                : "Gemini API-Key ungültig – in der App neu eintragen";
    }
}
