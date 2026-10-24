package com.pete.tftjarvis;

import java.util.List;
import java.util.Locale;

/**
 * Final deterministic gate between AI output and the player.
 *
 * Perception facts may still update the MatchState when a strategic instruction
 * is blocked, but the blocked strategic change is stripped from the result.
 */
final class DecisionGate {
    static final class GateResult {
        final OpenAIClient.CoachResult result;
        final boolean blocked;
        final String reason;

        GateResult(OpenAIClient.CoachResult result, boolean blocked, String reason) {
            this.result = result;
            this.blocked = blocked;
            this.reason = reason;
        }
    }

    private DecisionGate() {}

    static GateResult validate(OpenAIClient.CoachResult raw, MatchState state, EvidenceLedger ledger) {
        if (raw == null) return blocked(null, "Leere KI-Antwort", "Entscheidung nicht verifiziert – erneut analysieren");

        if ("KRITISCH".equals(raw.priority) && "NIEDRIG".equals(raw.confidence)) {
            return blocked(raw, "Kritische Aktion bei niedriger Sicherheit",
                    "Entscheidung nicht sicher genug – erneut analysieren");
        }

        if (ledger != null && ledger.hasFreshConflict() && "KRITISCH".equals(raw.priority)) {
            return blocked(raw, "Frischer HUD-Widerspruch",
                    "HUD-Werte widersprüchlich – erneut analysieren");
        }

        String continuity = TftRuleEngine.strategyViolation(state, raw);
        if (continuity != null) {
            return blocked(raw, continuity, "Strategiewechsel nicht verifiziert – bisherigen Kern halten");
        }

        if ("AUGMENTIERUNG".equals(raw.phase)) {
            GateResult selection = validateSelection(raw);
            if (selection != null) return selection;
        }

        if ("KAMPF".equals(raw.phase) && containsForbiddenCombatAction(raw.call)) {
            return blocked(raw, "Kauf/Roll/Level-Aktion während Kampf",
                    "Warte auf Planungsphase");
        }

        if (("SHOP".equals(raw.phase) || "PLANUNG".equals(raw.phase)) && isBuyCall(raw.call)) {
            String target = mentionedValue(raw.shop, raw.call);
            if (target == null) {
                return blocked(raw, "Kaufziel nicht im sichtbaren Shop verifiziert",
                        "Kauf nicht verifiziert – Gold halten");
            }
            String purchaseViolation = TftRuleEngine.purchaseViolation(state, raw, target);
            if (purchaseViolation != null) {
                return blocked(raw, purchaseViolation,
                        "Off-Comp-Kauf blockiert – Gold halten");
            }
        }

        return new GateResult(raw, false, "");
    }

    private static GateResult validateSelection(OpenAIClient.CoachResult raw) {
        boolean concrete = raw.call != null
                && raw.call.trim().toUpperCase(Locale.GERMAN).startsWith("NIMM:");
        boolean actionableConfidence = "HOCH".equals(raw.confidence) || "MITTEL".equals(raw.confidence);

        if (raw.options == null || raw.options.isEmpty()) {
            if (concrete || (actionableConfidence && !"INFO".equals(raw.priority))) {
                return blocked(raw, "Keine sichtbaren Auswahloptionen verifiziert",
                        "Auswahl nicht sicher verifiziert – erneut analysieren");
            }
            return null;
        }

        if (!concrete) {
            return blocked(raw, "Auswahl erkannt, aber keine konkrete Option genannt",
                    "Auswahl erkannt – konkrete Option erneut bestimmen");
        }

        if (mentionedValue(raw.options, raw.call) == null) {
            return blocked(raw, "Empfehlung gehört nicht zu den verifizierten sichtbaren Optionen",
                    "Empfohlene Option nicht verifiziert – erneut analysieren");
        }
        return null;
    }

    private static GateResult blocked(OpenAIClient.CoachResult raw, String reason, String safeCall) {
        if (raw == null) {
            return new GateResult(null, true, reason);
        }
        OpenAIClient.CoachResult safe = new OpenAIClient.CoachResult(
                raw.phase,
                safeCall,
                "",
                raw.state,
                raw.newMatch,
                "INFO",
                "NIEDRIG",
                raw.level,
                raw.gold,
                raw.health,
                raw.board,
                raw.bench,
                raw.items,
                raw.augments,
                raw.traits,
                raw.shop,
                raw.options,
                raw.carry,
                raw.tank,
                raw.stage,
                "",
                "",
                "NIEDRIG",
                false,
                ""
        );
        return new GateResult(safe, true, reason);
    }

    private static boolean containsForbiddenCombatAction(String call) {
        if (call == null) return false;
        String t = call.trim().toLowerCase(Locale.GERMAN);
        return t.contains("kauf")
                || t.contains("roll")
                || t.contains("reroll")
                || t.contains("level")
                || t.contains("xp kaufen")
                || t.startsWith("nimm:");
    }

    private static boolean isBuyCall(String call) {
        if (call == null) return false;
        String t = call.trim().toLowerCase(Locale.GERMAN);
        return t.startsWith("kauf ")
                || t.startsWith("kauf:")
                || t.startsWith("kaufe ")
                || t.startsWith("kaufe:")
                || t.contains("; kauf ")
                || t.contains("; kauf:")
                || t.contains("; kaufe ")
                || t.contains("; kaufe:")
                || t.contains(", kauf ")
                || t.contains(", kauf:")
                || t.contains(", kaufe ")
                || t.contains(", kaufe:")
                || t.contains(" kaufen");
    }

    private static String mentionedValue(List<String> values, String text) {
        if (values == null || values.isEmpty() || text == null) return null;
        String haystack = text.toLowerCase(Locale.GERMAN);
        String best = null;
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            String needle = value.trim().toLowerCase(Locale.GERMAN);
            // A short option/unit name must not validate a different, longer
            // visible word (e.g. "Eis" in "Eisenrüstung").
            if (containsWholeName(haystack, needle)
                    && (best == null || value.length() > best.length())) {
                best = value.trim();
            }
        }
        return best;
    }

    private static boolean containsWholeName(String haystack, String needle) {
        int from = 0;
        while (from < haystack.length()) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) return false;
            int right = index + needle.length();
            boolean leftBoundary = index == 0 || !isNameCharacter(haystack.charAt(index - 1));
            boolean rightBoundary = right == haystack.length()
                    || !isNameCharacter(haystack.charAt(right));
            if (leftBoundary && rightBoundary) return true;
            from = index + 1;
        }
        return false;
    }

    private static boolean isNameCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }
}
