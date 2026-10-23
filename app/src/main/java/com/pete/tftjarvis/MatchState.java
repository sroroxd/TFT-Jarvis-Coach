package com.pete.tftjarvis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Persistent, conservative memory for one TFT match.
 *
 * The current screenshot is always authoritative. Confirmed values survive
 * screenshots where a HUD field is temporarily hidden. Medium-confidence
 * numeric changes need a second matching observation before replacing a
 * confirmed value; low-confidence observations never overwrite it.
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
    public String stageConfidence = "NIEDRIG";
    public long stageObservedAt;

    public String primaryTrait;
    public String compPlan;
    public String strategyConfidence = "NIEDRIG";
    public int strategyEvidence;
    public String lastPivotReason;

    public String levelConfidence = "NIEDRIG";
    public String goldConfidence = "NIEDRIG";
    public String healthConfidence = "NIEDRIG";
    public long levelObservedAt;
    public long goldObservedAt;
    public long healthObservedAt;

    private Integer pendingLevel;
    private int pendingLevelCount;
    private Integer pendingGold;
    private int pendingGoldCount;
    private Integer pendingHealth;
    private int pendingHealthCount;
    private String pendingStage;
    private int pendingStageCount;

    private final List<String> recentHistory = new ArrayList<>();
    public long observedAt;

    public synchronized void apply(OpenAIClient.CoachResult result) {
        apply(result, null);
    }

    public synchronized void apply(OpenAIClient.CoachResult result, LocalHudPerception.Observation localHud) {
        if (result == null) return;
        final long now = System.currentTimeMillis();
        final String observationConfidence = normalizeConfidence(result.confidence);

        String seenPhase = safePhase(result.phase);
        if (!"UNKLAR".equals(seenPhase)) phase = seenPhase;

        if (localHud != null && localHud.level != null) {
            updateLevel(localHud.level.value,
                    localConfidence(localHud.level, result.level == localHud.level.value), now);
        } else {
            updateLevel(result.level, cloudOnlyConfidence(observationConfidence), now);
        }

        if (localHud != null && localHud.gold != null) {
            updateGold(localHud.gold.value,
                    localConfidence(localHud.gold, result.gold == localHud.gold.value), now);
        } else {
            updateGold(result.gold, cloudOnlyConfidence(observationConfidence), now);
        }

        if (localHud != null && localHud.health != null) {
            updateHealth(localHud.health.value,
                    localConfidence(localHud.health, result.health == localHud.health.value), now);
        } else {
            updateHealth(result.health, cloudOnlyConfidence(observationConfidence), now);
        }

        String seenStage = cleanOrNull(result.stage);
        if (localHud != null && localHud.stage != null) {
            boolean agrees = seenStage != null && seenStage.equals(localHud.stage.value);
            updateStage(localHud.stage.value, localConfidence(localHud.stage, agrees), now);
        } else {
            updateStage(seenStage, cloudOnlyConfidence(observationConfidence), now);
        }

        updateList(result.board, board, observationConfidence);
        updateList(result.bench, bench, observationConfidence);
        updateList(result.items, items, observationConfidence);
        updateList(result.augments, augments, observationConfidence);
        updateList(result.traits, traits, observationConfidence);

        if (!"NIEDRIG".equals(observationConfidence)) {
            String seenCarry = cleanOrNull(result.carry);
            String seenTank = cleanOrNull(result.tank);
            if (seenCarry != null) carry = seenCarry;
            if (seenTank != null) tank = seenTank;
        }

        String seenSummary = cleanOrNull(result.state);
        if (seenSummary != null) summary = seenSummary;
        confidence = observationConfidence;

        String newPlan = cleanOrNull(result.plan);
        if (newPlan != null) plan = newPlan;

        applyStrategy(result);
        observedAt = now;
        rememberHistory(result);
    }

    public synchronized String toPromptContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- BEOBACHTETER MATCH-STATE (TEILWEISE UNBESTÄTIGT) ---\n");
        sb.append("Der aktuelle Screenshot ist autoritativ. Dieser State ist nur fortlaufender Kontext.\n");
        sb.append("Fehlende aktuelle Sichtbarkeit bedeutet NICHT automatisch, dass ein bestätigter Wert verschwunden ist.\n");
        sb.append("Nie aus alten Werten neue Fakten erfinden; bei Widerspruch aktuellen Screenshot vorsichtig prüfen.\n");

        if (phase != null && !"UNKLAR".equals(phase)) sb.append("Letzte Phase: ").append(phase).append('\n');
        if (stage != null) {
            sb.append("Letzte Stage: ").append(stage)
                    .append(" [").append(stageConfidence).append(", ")
                    .append(ageLabel(stageObservedAt)).append("]\n");
        }
        appendVerifiedInt(sb, "Level", level, levelConfidence, levelObservedAt);
        appendVerifiedInt(sb, "Gold", gold, goldConfidence, goldObservedAt);
        appendVerifiedInt(sb, "HP", health, healthConfidence, healthObservedAt);

        appendList(sb, "Zuletzt bestätigtes Board", board);
        appendList(sb, "Zuletzt bestätigte Bench", bench);
        appendList(sb, "Zuletzt bestätigte Items", items);
        appendList(sb, "Bestätigte/erkannte Augments", augments);
        appendList(sb, "Zuletzt bestätigte Traits", traits);

        if (carry != null) sb.append("Carry: ").append(carry).append('\n');
        if (tank != null) sb.append("Tank: ").append(tank).append('\n');

        sb.append("--- STRATEGISCHE LEITPLANKEN ---\n");
        if (primaryTrait != null) {
            sb.append("Kern-Trait: ").append(primaryTrait).append('\n');
            sb.append("Bestätigungen: ").append(strategyEvidence).append('\n');
            sb.append("Strategie-Sicherheit: ").append(strategyConfidence).append('\n');
            if (isStrategyEstablished()) {
                sb.append("STATUS: KERNPLAN GEFESTIGT. Keine Off-Comp-Käufe ohne konkreten Upgrade-/Carry-/Tank-/Pivot-Grund.\n");
            } else {
                sb.append("STATUS: TENDENZ. Früh flexibel bleiben; Kern noch nicht hart festnageln.\n");
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
        return sb.toString();
    }

    public synchronized String debugSummary() {
        StringBuilder sb = new StringBuilder("STATE:");
        if (level != null) sb.append(" L").append(level).append("(").append(shortConfidence(levelConfidence, levelObservedAt)).append(")");
        if (gold != null) sb.append(" G").append(gold).append("(").append(shortConfidence(goldConfidence, goldObservedAt)).append(")");
        if (health != null) sb.append(" HP").append(health).append("(").append(shortConfidence(healthConfidence, healthObservedAt)).append(")");
        if (stage != null) sb.append(" ").append(stage).append("(")
                .append(shortConfidence(stageConfidence, stageObservedAt)).append(")");
        if (!traits.isEmpty()) sb.append("\nTRAITS: ").append(joinLimited(traits, 4));
        if (!augments.isEmpty()) sb.append("\nAUGMENTS: ").append(joinLimited(augments, 3));
        if (sb.length() == 6) sb.append(" noch keine bestätigten HUD-Werte");
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
        plan = null;
        summary = null;
        confidence = "NIEDRIG";
        stage = null;
        stageConfidence = "NIEDRIG";
        stageObservedAt = 0L;

        primaryTrait = null;
        compPlan = null;
        strategyConfidence = "NIEDRIG";
        strategyEvidence = 0;
        lastPivotReason = null;

        levelConfidence = "NIEDRIG";
        goldConfidence = "NIEDRIG";
        healthConfidence = "NIEDRIG";
        levelObservedAt = 0L;
        goldObservedAt = 0L;
        healthObservedAt = 0L;

        pendingLevel = null;
        pendingLevelCount = 0;
        pendingGold = null;
        pendingGoldCount = 0;
        pendingHealth = null;
        pendingHealthCount = 0;
        pendingStage = null;
        pendingStageCount = 0;

        recentHistory.clear();
        observedAt = 0L;
    }

    private void updateLevel(int raw, String conf, long now) {
        Integer value = boundedInt(raw, 1, 10);
        if (value == null || "NIEDRIG".equals(conf)) return;
        if (level != null && value < level) {
            // A confirmed TFT level normally does not decrease within a match,
            // but OCR can mistake the adjacent XP cost for the level. Do not
            // retain a high-confidence erroneous reading indefinitely.
            levelConfidence = "NIEDRIG";
            if (value.equals(pendingLevel)) pendingLevelCount++;
            else {
                pendingLevel = value;
                pendingLevelCount = 1;
            }
            if (pendingLevelCount >= 2) {
                level = value;
                levelConfidence = "MITTEL";
                levelObservedAt = now;
                pendingLevel = null;
                pendingLevelCount = 0;
            }
            return;
        }

        boolean largeJump = level != null && value > level + 2;
        if ("HOCH".equals(conf) && !largeJump) {
            level = value;
            levelConfidence = conf;
            levelObservedAt = now;
            pendingLevel = null;
            pendingLevelCount = 0;
            return;
        }
        if (level != null && level.equals(value)) {
            levelConfidence = strongerConfidence(levelConfidence, conf);
            levelObservedAt = now;
            pendingLevel = null;
            pendingLevelCount = 0;
            return;
        }
        if (value.equals(pendingLevel)) pendingLevelCount++; else {
            pendingLevel = value;
            pendingLevelCount = 1;
        }
        if (pendingLevelCount >= 2) {
            level = value;
            levelConfidence = conf;
            levelObservedAt = now;
            pendingLevel = null;
            pendingLevelCount = 0;
        }
    }

    private void updateGold(int raw, String conf, long now) {
        Integer value = boundedInt(raw, 0, 999);
        if (value == null || "NIEDRIG".equals(conf)) return;
        if ("HOCH".equals(conf) || (gold != null && gold.equals(value))) {
            gold = value;
            goldConfidence = strongerConfidence(goldConfidence, conf);
            goldObservedAt = now;
            pendingGold = null;
            pendingGoldCount = 0;
            return;
        }
        if (value.equals(pendingGold)) pendingGoldCount++; else {
            pendingGold = value;
            pendingGoldCount = 1;
        }
        if (pendingGoldCount >= 2) {
            gold = value;
            goldConfidence = conf;
            goldObservedAt = now;
            pendingGold = null;
            pendingGoldCount = 0;
        }
    }

    private void updateHealth(int raw, String conf, long now) {
        Integer value = boundedInt(raw, 0, 100);
        if (value == null || "NIEDRIG".equals(conf)) return;
        if ("HOCH".equals(conf) || (health != null && health.equals(value))) {
            health = value;
            healthConfidence = strongerConfidence(healthConfidence, conf);
            healthObservedAt = now;
            pendingHealth = null;
            pendingHealthCount = 0;
            return;
        }
        if (value.equals(pendingHealth)) pendingHealthCount++; else {
            pendingHealth = value;
            pendingHealthCount = 1;
        }
        if (pendingHealthCount >= 2) {
            health = value;
            healthConfidence = conf;
            healthObservedAt = now;
            pendingHealth = null;
            pendingHealthCount = 0;
        }
    }

    private void updateStage(String raw, String conf, long now) {
        String value = cleanOrNull(raw);
        if (value == null || !value.matches("\\d+-\\d+") || "NIEDRIG".equals(conf)) return;
        if (stage != null && compareStage(value, stage) < 0) return;

        boolean hugeJump = stage != null && stageMajor(value) > stageMajor(stage) + 1;
        if (stage != null && stage.equals(value)) {
            stageConfidence = strongerConfidence(stageConfidence, conf);
            stageObservedAt = now;
            pendingStage = null;
            pendingStageCount = 0;
            return;
        }

        if ("HOCH".equals(conf) && !hugeJump) {
            stage = value;
            stageConfidence = conf;
            stageObservedAt = now;
            pendingStage = null;
            pendingStageCount = 0;
            return;
        }

        if (value.equals(pendingStage)) pendingStageCount++; else {
            pendingStage = value;
            pendingStageCount = 1;
        }
        if (pendingStageCount >= 2) {
            stage = value;
            stageConfidence = conf;
            stageObservedAt = now;
            pendingStage = null;
            pendingStageCount = 0;
        }
    }

    private static String cloudOnlyConfidence(String confidence) {
        // A single generative claim is not independent evidence of a HUD value.
        return "NIEDRIG".equals(normalizeConfidence(confidence)) ? "NIEDRIG" : "MITTEL";
    }

    private static String localConfidence(LocalHudPerception.Field<?> field, boolean cloudAgrees) {
        if (field == null) return "NIEDRIG";
        return cloudAgrees ? "HOCH" : normalizeConfidence(field.confidence);
    }

    private static void updateList(List<String> source, List<String> target, String conf) {
        if (source == null || source.isEmpty() || "NIEDRIG".equals(conf)) return;
        if (!"HOCH".equals(conf) && !target.isEmpty()) return;
        List<String> cleaned = new ArrayList<>();
        for (String value : source) {
            String c = cleanOrNull(value);
            if (c != null && cleaned.size() < 12 && !containsIgnoreCase(cleaned, c)) cleaned.add(c);
        }
        if (cleaned.isEmpty()) return;
        target.clear();
        target.addAll(cleaned);
    }

    private void applyStrategy(OpenAIClient.CoachResult result) {
        String candidateTrait = cleanOrNull(result.primaryTrait);
        String candidatePlan = cleanOrNull(result.compPlan);
        String candidateConfidence = normalizeConfidence(result.strategyConfidence);
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

    private static Integer boundedInt(int value, int min, int max) {
        return value >= min && value <= max ? value : null;
    }

    private static void appendVerifiedInt(StringBuilder sb, String label, Integer value, String conf, long at) {
        if (value == null) return;
        sb.append(label).append(": ").append(value)
                .append(" [").append(conf).append(", ")
                .append(ageLabel(at)).append("]\n");
    }

    private static String ageLabel(long at) {
        if (at <= 0L) return "Zeit unbekannt";
        long sec = Math.max(0L, (System.currentTimeMillis() - at) / 1000L);
        if (sec < 5L) return "gerade beobachtet";
        if (sec < 60L) return "vor " + sec + "s";
        return "vor " + (sec / 60L) + "min";
    }

    private static String shortConfidence(String value, long observedAt) {
        long age = observedAt <= 0L ? Long.MAX_VALUE : System.currentTimeMillis() - observedAt;
        if (age > 45_000L) return "ALT";
        if (age > 15_000L && "HOCH".equals(value)) return "M";
        if ("HOCH".equals(value)) return "H";
        if ("MITTEL".equals(value)) return "M";
        return "?";
    }

    public synchronized boolean isStrategyEstablished() {
        return primaryTrait != null
                && strategyEvidence >= 3
                && stageMajor(stage) >= 3
                && !"NIEDRIG".equals(strategyConfidence);
    }

    private static int compareStage(String a, String b) {
        int am = stageMajor(a);
        int bm = stageMajor(b);
        if (am != bm) return Integer.compare(am, bm);
        return Integer.compare(stageMinor(a), stageMinor(b));
    }

    private static int stageMajor(String s) {
        try {
            if (s == null) return -1;
            int dash = s.indexOf('-');
            return dash > 0 ? Integer.parseInt(s.substring(0, dash)) : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int stageMinor(String s) {
        try {
            if (s == null) return -1;
            int dash = s.indexOf('-');
            return dash >= 0 && dash + 1 < s.length() ? Integer.parseInt(s.substring(dash + 1)) : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String joinLimited(List<String> values, int max) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(max, values.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values.get(i));
        }
        if (values.size() > max) sb.append(" +").append(values.size() - max);
        return sb.toString();
    }

    private static boolean sameText(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static String normalizeConfidence(String value) {
        if (value == null) return "NIEDRIG";
        String v = value.trim().toUpperCase(Locale.ROOT);
        if ("HOCH".equals(v) || "MITTEL".equals(v) || "NIEDRIG".equals(v)) return v;
        return "NIEDRIG";
    }

    private static String strongerConfidence(String a, String b) {
        if ("HOCH".equals(a) || "HOCH".equals(b)) return "HOCH";
        if ("MITTEL".equals(a) || "MITTEL".equals(b)) return "MITTEL";
        return "NIEDRIG";
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

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        for (String v : values) {
            if (v.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }

    private static void appendList(StringBuilder sb, String name, List<String> values) {
        if (values == null || values.isEmpty()) return;
        sb.append(name).append(": ").append(String.join(", ", values)).append('\n');
    }

    public synchronized List<String> boardSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(board));
    }
}
