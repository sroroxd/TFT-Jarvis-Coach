package com.pete.tftjarvis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Typed, conservative memory for one TFT match.
 *
 * Unknown values stay null/empty. The current screenshot always wins; this
 * object is context from the last successful analysis, never a source of truth
 * over the current frame.
 */
public final class MatchState {
    public Integer level;
    public Integer gold;
    public Integer health;
    public String phase = "UNKLAR";

    public final List<String> board = new ArrayList<>();
    public final List<String> bench = new ArrayList<>();
    public final List<String> items = new ArrayList<>();
    public final List<String> augments = new ArrayList<>();
    public final List<String> traits = new ArrayList<>();

    public String carry;
    public String tank;
    public String plan;
    public String summary;
    public String confidence = "NIEDRIG";
    public String stage;

    // Strategic memory persists across screenshots and is only cleared for a new match.
    public String primaryTrait;
    public String compPlan;
    public String strategyConfidence = "NIEDRIG";
    public int strategyEvidence;
    public String lastPivotReason;

    private final List<String> recentHistory = new ArrayList<>();
    public long observedAt;

    public synchronized void apply(OpenAIClient.CoachResult result) {
        if (result == null) return;

        // Observational fields describe the newest analyzed screenshot only.
        clearObservation();

        phase = safePhase(result.phase);
        level = knownInt(result.level);
        gold = knownInt(result.gold);
        health = knownInt(result.health);

        copyClean(result.board, board);
        copyClean(result.bench, bench);
        copyClean(result.items, items);
        copyClean(result.augments, augments);
        copyClean(result.traits, traits);

        carry = cleanOrNull(result.carry);
        tank = cleanOrNull(result.tank);
        summary = cleanOrNull(result.state);
        confidence = result.confidence == null ? "NIEDRIG" : result.confidence;
        stage = cleanOrNull(result.stage);

        String newPlan = cleanOrNull(result.plan);
        if (newPlan != null) plan = newPlan;

        applyStrategy(result);
        observedAt = System.currentTimeMillis();
        rememberHistory(result);
    }

    public synchronized String toPromptContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- LETZTER SICHERER MATCH-STATE ---\n");
        sb.append("WICHTIG: Der aktuelle Screenshot ist autoritativ. Dieser State ist nur Kontext der letzten Analyse.\n");
        sb.append("Nicht aufgeführte Werte sind UNBEKANNT; niemals ergänzen oder raten.\n");

        if (phase != null && !"UNKLAR".equals(phase)) sb.append("Phase zuletzt: ").append(phase).append('\n');
        if (stage != null) sb.append("Stage zuletzt: ").append(stage).append('\n');
        if (level != null) sb.append("Level: ").append(level).append('\n');
        if (gold != null) sb.append("Gold: ").append(gold).append('\n');
        if (health != null) sb.append("HP: ").append(health).append('\n');

        appendList(sb, "Board", board);
        appendList(sb, "Bench", bench);
        appendList(sb, "Items", items);
        appendList(sb, "Augments", augments);
        appendList(sb, "Traits", traits);

        if (carry != null) sb.append("Carry: ").append(carry).append('\n');
        if (tank != null) sb.append("Tank: ").append(tank).append('\n');

        sb.append("--- STRATEGISCHE LEITPLANKEN ---\n");
        if (primaryTrait != null) {
            sb.append("Kern-Trait: ").append(primaryTrait).append('\n');
            sb.append("Bestätigungen: ").append(strategyEvidence).append('\n');
            sb.append("Strategie-Sicherheit: ").append(strategyConfidence).append('\n');
            if (strategyEvidence >= 2) {
                sb.append("STATUS: KERNPLAN GEFESTIGT. Keine Off-Comp-Käufe ohne konkreten Upgrade-/Carry-/Tank-/Pivot-Grund.\n");
            }
        } else {
            sb.append("Kern-Trait: noch nicht sicher festgelegt.\n");
        }
        if (compPlan != null) sb.append("Comp-Plan: ").append(compPlan).append('\n');
        if (lastPivotReason != null) sb.append("Letzter Pivot-Grund: ").append(lastPivotReason).append('\n');
        if (plan != null) sb.append("Letzter taktischer Plan: ").append(plan).append('\n');

        if (!recentHistory.isEmpty()) {
            sb.append("--- LETZTE ENTSCHEIDUNGEN ---\n");
            for (String item : recentHistory) sb.append(item).append('\n');
        }
        if (summary != null) sb.append("Letzte sichere Fakten: ").append(summary).append('\n');
        if (observedAt > 0L) sb.append("State-Zeitstempel(ms): ").append(observedAt).append('\n');

        return sb.toString();
    }

    public synchronized boolean hasMemory() {
        return observedAt > 0L || plan != null || summary != null
                || primaryTrait != null || compPlan != null
                || level != null || gold != null || health != null
                || !board.isEmpty() || !bench.isEmpty() || !items.isEmpty()
                || !augments.isEmpty() || !traits.isEmpty();
    }

    public synchronized void clear() {
        clearObservation();
        plan = null;
        primaryTrait = null;
        compPlan = null;
        strategyConfidence = "NIEDRIG";
        strategyEvidence = 0;
        lastPivotReason = null;
        recentHistory.clear();
    }

    private void clearObservation() {
        level = null;
        gold = null;
        health = null;
        phase = "UNKLAR";
        board.clear();
        bench.clear();
        items.clear();
        augments.clear();
        traits.clear();
        carry = null;
        tank = null;
        summary = null;
        confidence = "NIEDRIG";
        stage = null;
        observedAt = 0L;
    }

    private void applyStrategy(OpenAIClient.CoachResult result) {
        String candidateTrait = cleanOrNull(result.primaryTrait);
        String candidatePlan = cleanOrNull(result.compPlan);
        String candidateConfidence = result.strategyConfidence == null
                ? "NIEDRIG"
                : result.strategyConfidence.trim().toUpperCase(Locale.ROOT);
        boolean trustworthy = "HOCH".equals(candidateConfidence) || "MITTEL".equals(candidateConfidence);

        if (candidateTrait == null || !trustworthy) {
            if (candidatePlan != null && primaryTrait == null) compPlan = candidatePlan;
            return;
        }

        if (primaryTrait == null) {
            primaryTrait = candidateTrait;
            compPlan = candidatePlan;
            strategyConfidence = candidateConfidence;
            strategyEvidence = 1;
            return;
        }

        if (sameText(primaryTrait, candidateTrait)) {
            strategyEvidence = Math.min(9, strategyEvidence + 1);
            strategyConfidence = strongerConfidence(strategyConfidence, candidateConfidence);
            if (candidatePlan != null) compPlan = candidatePlan;
            return;
        }

        // Do not let one isolated shop rewrite the comp. A strategy switch requires
        // an explicit high-confidence pivot from the model.
        if (result.pivotRecommended && "HOCH".equals(candidateConfidence)) {
            primaryTrait = candidateTrait;
            compPlan = candidatePlan;
            strategyConfidence = candidateConfidence;
            strategyEvidence = 1;
            lastPivotReason = cleanOrNull(result.pivotReason);
        }
    }

    private void rememberHistory(OpenAIClient.CoachResult result) {
        StringBuilder line = new StringBuilder("• ");
        if (stage != null) line.append(stage).append(" | ");
        line.append(phase == null ? "UNKLAR" : phase);
        if (primaryTrait != null) line.append(" | Kern=").append(primaryTrait);
        String call = cleanOrNull(result.call);
        if (call != null) line.append(" | ").append(call);
        recentHistory.add(line.toString());
        while (recentHistory.size() > 4) recentHistory.remove(0);
    }

    private static boolean sameText(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static String strongerConfidence(String a, String b) {
        if ("HOCH".equals(a) || "HOCH".equals(b)) return "HOCH";
        if ("MITTEL".equals(a) || "MITTEL".equals(b)) return "MITTEL";
        return "NIEDRIG";
    }

    private static Integer knownInt(int value) {
        return value >= 0 ? value : null;
    }

    private static String safePhase(String value) {
        if (value == null || value.trim().isEmpty()) return "UNKLAR";
        return value.trim();
    }

    private static String cleanOrNull(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static void copyClean(List<String> source, List<String> target) {
        if (source == null) return;
        for (String value : source) {
            String cleaned = cleanOrNull(value);
            if (cleaned != null && target.size() < 12) target.add(cleaned);
        }
    }

    private static void appendList(StringBuilder sb, String name, List<String> values) {
        if (values == null || values.isEmpty()) return;
        sb.append(name).append(": ").append(String.join(", ", values)).append('\n');
    }

    public synchronized List<String> boardSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(board));
    }
}
