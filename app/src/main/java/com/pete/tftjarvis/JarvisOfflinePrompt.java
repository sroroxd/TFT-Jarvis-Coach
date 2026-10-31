package com.pete.tftjarvis;

import java.util.List;

/** Pure-Java, bounded conversational prompt for the offline inference lab. */
final class JarvisOfflinePrompt {
    private JarvisOfflinePrompt() { }

    static String build(List<String[]> history, String question) {
        if (question == null || question.trim().isEmpty() || question.length() > 1000)
            throw new IllegalArgumentException("Ungültige Frage.");
        StringBuilder result = new StringBuilder();
        result.append("Du bist JARVIS, ein lokaler deutschsprachiger Assistent. ")
                .append("Antworte kurz und natürlich auf Deutsch. ")
                .append("Du hast keinen Internetzugriff und darfst keine Geräteaktionen vortäuschen.\n");
        int from = Math.max(0, history.size() - 5);
        for (int i = from; i < history.size(); i++) {
            String[] turn = history.get(i);
            if (turn == null || turn.length < 2) continue;
            appendBounded(result, "Nutzer: ", turn[0], 800);
            appendBounded(result, "JARVIS: ", turn[1], 1000);
        }
        appendBounded(result, "Nutzer: ", question, 1000);
        result.append("JARVIS: ");
        return result.toString();
    }

    private static void appendBounded(StringBuilder out, String role, String value, int max) {
        if (value == null) return;
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        out.append(role).append(clean, 0, Math.min(clean.length(), max)).append('\n');
    }
}
