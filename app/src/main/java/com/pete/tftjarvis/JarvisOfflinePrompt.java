package com.pete.tftjarvis;

import java.util.List;

/**
 * Small, bounded Qwen2.5-Instruct ChatML prompt for the offline test laboratory.
 * Only the application may create role delimiters. Untrusted text is escaped.
 */
final class JarvisOfflinePrompt {
    private static final int FOLLOWUPS = 5;
    private static final int HISTORY_QUESTION_CHARS = 220;
    private static final int HISTORY_ANSWER_CHARS = 300;
    private static final int CURRENT_QUESTION_CHARS = 1000;

    private JarvisOfflinePrompt() { }

    static String build(List<String[]> history, String question) {
        if (question == null || question.trim().isEmpty() || question.length() > CURRENT_QUESTION_CHARS) {
            throw new IllegalArgumentException("Ungültige Frage.");
        }
        StringBuilder prompt = new StringBuilder();
        roleStart(prompt, "system");
        prompt.append("Du bist JARVIS, ein lokaler deutschsprachiger Assistent. ")
                .append("Antworte natürlich und kurz auf Deutsch. ")
                .append("Du hast keinen Internetzugriff und darfst keine Geräteaktionen vortäuschen.");
        roleEnd(prompt);

        if (history != null) {
            int start = Math.max(0, history.size() - FOLLOWUPS);
            for (int i = start; i < history.size(); i++) {
                String[] turn = history.get(i);
                if (turn == null || turn.length < 2) continue;
                roleStart(prompt, "user");
                prompt.append(sanitize(turn[0], HISTORY_QUESTION_CHARS));
                roleEnd(prompt);
                roleStart(prompt, "assistant");
                prompt.append(sanitize(turn[1], HISTORY_ANSWER_CHARS));
                roleEnd(prompt);
            }
        }
        roleStart(prompt, "user");
        prompt.append(sanitize(question, CURRENT_QUESTION_CHARS));
        roleEnd(prompt);
        // Open assistant turn is required by the Qwen2.5-Instruct chat template.
        roleStart(prompt, "assistant");
        return prompt.toString();
    }

    private static void roleStart(StringBuilder prompt, String role) {
        prompt.append("<|im_start|>").append(role).append('\n');
    }

    private static void roleEnd(StringBuilder prompt) {
        prompt.append("<|im_end|>\n");
    }

    private static String sanitize(String raw, int maxChars) {
        if (raw == null) return "";
        String cleaned = raw.replace('\r', ' ').replace('\n', ' ')
                .replace("<|", "[")
                .replace("|>", "]").trim();
        return cleaned.substring(0, Math.min(cleaned.length(), maxChars));
    }
}
