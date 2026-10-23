package com.pete.tftjarvis;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic guardrails for facts and strategic continuity.
 *
 * This does not try to play TFT itself. It only rejects recommendations that
 * contradict already-verified state or lack a concrete, checkable justification.
 */
final class TftRuleEngine {
    private TftRuleEngine() {}

    static String strategyViolation(MatchState state, OpenAIClient.CoachResult result) {
        if (state == null || result == null || !state.isStrategyEstablished()) return null;

        String current = clean(state.primaryTrait);
        String proposed = clean(result.primaryTrait);
        if (current != null && proposed != null && !same(current, proposed)) {
            if (!result.pivotRecommended) {
                return "Kernwechsel ohne bestätigten Pivot";
            }
            if (clean(result.pivotReason) == null) {
                return "Pivot ohne sichtbare Begründung";
            }
        }

        if (result.pivotRecommended && clean(result.pivotReason) == null) {
            return "Pivot ohne sichtbare Begründung";
        }
        return null;
    }

    static String purchaseViolation(MatchState state, OpenAIClient.CoachResult result, String target) {
        if (state == null || result == null || target == null || !state.isStrategyEstablished()) return null;

        if (containsIgnoreCase(state.boardSnapshot(), target) || containsIgnoreCase(state.bench, target)) {
            return null; // Pair/upgrade is independently grounded in current match memory.
        }

        String text = ((result.call == null ? "" : result.call) + " "
                + (result.plan == null ? "" : result.plan)).toLowerCase(Locale.GERMAN);
        String core = clean(state.primaryTrait);
        if (core != null && text.contains(core.toLowerCase(Locale.GERMAN))) return null;

        boolean roleReason = text.contains("carry")
                || text.contains("tank")
                || text.contains("frontline")
                || text.contains("synergie")
                || text.contains("upgrade")
                || text.contains("pair")
                || text.contains("paar");
        if (roleReason) return null;

        if (result.pivotRecommended && clean(result.pivotReason) != null) return null;

        return "Off-Comp-Kauf ohne verifizierten Upgrade-, Rollen- oder Pivot-Grund";
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || target == null) return false;
        for (String value : values) {
            if (value != null && value.trim().equalsIgnoreCase(target.trim())) return true;
        }
        return false;
    }

    private static boolean same(String a, String b) {
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static String clean(String value) {
        if (value == null) return null;
        String c = value.trim();
        return c.isEmpty() ? null : c;
    }
}
