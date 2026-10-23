package com.pete.tftjarvis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

        String newPlan = cleanOrNull(result.plan);
        if (newPlan != null) plan = newPlan;

        observedAt = System.currentTimeMillis();
    }

    public synchronized String toPromptContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- LETZTER SICHERER MATCH-STATE ---\n");
        sb.append("WICHTIG: Der aktuelle Screenshot ist autoritativ. Dieser State ist nur Kontext der letzten Analyse.\n");
        sb.append("Nicht aufgeführte Werte sind UNBEKANNT; niemals ergänzen oder raten.\n");

        if (phase != null && !"UNKLAR".equals(phase)) sb.append("Phase zuletzt: ").append(phase).append('\n');
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
        if (plan != null) sb.append("Letzter Plan: ").append(plan).append('\n');
        if (summary != null) sb.append("Letzte sichere Fakten: ").append(summary).append('\n');
        if (observedAt > 0L) sb.append("State-Zeitstempel(ms): ").append(observedAt).append('\n');

        return sb.toString();
    }

    public synchronized boolean hasMemory() {
        return observedAt > 0L || plan != null || summary != null
                || level != null || gold != null || health != null
                || !board.isEmpty() || !bench.isEmpty() || !items.isEmpty()
                || !augments.isEmpty() || !traits.isEmpty();
    }

    public synchronized void clear() {
        clearObservation();
        plan = null;
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
        observedAt = 0L;
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
