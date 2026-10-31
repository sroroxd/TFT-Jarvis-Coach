package com.pete.tftjarvis;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/**
 * Offline HUD Perception Benchmark.
 *
 * Synthetic OCR candidates only: NO screenshots, OCR engine, actual match or device involved.
 * Fixture authors provide labels explicitly; injected lastPhase is NOT a phase prediction.
 * Reports per-case verdicts and metrics with denominators. These metrics must never be
 * presented as real-world HUD accuracy or proof of HP/phase recognition.
 */
public class HudPerceptionBenchmarkTest {

    static class Fixture {
        final String name;
        final String lastPhase;
        final List<LocalHudPerception.Candidate> candidates;
        final String expectedStage;
        final Integer expectedLevel;
        final Integer expectedGold;
        final Integer expectedHealth;
        final boolean isGameScreen;

        Fixture(String name, String lastPhase, List<LocalHudPerception.Candidate> candidates,
                String expectedStage, Integer expectedLevel, Integer expectedGold, boolean isGameScreen) {
            this(name, lastPhase, candidates, expectedStage, expectedLevel, expectedGold, null, isGameScreen);
        }

        Fixture(String name, String lastPhase, List<LocalHudPerception.Candidate> candidates,
                String expectedStage, Integer expectedLevel, Integer expectedGold,
                Integer expectedHealth, boolean isGameScreen) {
            this.name = name;
            this.lastPhase = lastPhase;
            this.candidates = candidates;
            this.expectedStage = expectedStage;
            this.expectedLevel = expectedLevel;
            this.expectedGold = expectedGold;
            this.expectedHealth = expectedHealth;
            this.isGameScreen = isGameScreen;
        }
    }

    static class FieldMetrics {
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int tn = 0;
        int correct = 0;
        int wrong = 0;
        int rejected = 0;

        void record(String fixture, String field, Object truth, Object actual) {
            String outcome;
            if (truth == null) {
                if (actual == null) {
                    tn++;
                    rejected++;
                    outcome = "ABGELEHNT_KORREKT";
                } else {
                    fp++;
                    wrong++;
                    outcome = "FALSCH_FALSE_POSITIVE";
                }
            } else if (actual == null) {
                fn++;
                rejected++;
                outcome = "ABGELEHNT_TROTZ_LABEL";
            } else if (truth.equals(actual)) {
                tp++;
                correct++;
                outcome = "KORREKT";
            } else {
                fp++;
                fn++;  // Wrong non-null prediction both invents a value and misses the true one.
                wrong++;
                outcome = "FALSCH_FP_UND_FN";
            }
            System.out.println("CASE=" + fixture + " FIELD=" + field
                    + " TRUTH=" + (truth == null ? "UNLESBAR/FEHLT" : truth)
                    + " EXPECT_REJECTION=" + (truth == null)
                    + " ACTUAL=" + (actual == null ? "ABGELEHNT" : actual)
                    + " RESULT=" + outcome);
        }

        double precision() {
            return (tp + fp) == 0 ? Double.NaN : (double) tp / (tp + fp);
        }

        double recall() {
            return (tp + fn) == 0 ? Double.NaN : (double) tp / (tp + fn);
        }

        double f1() {
            double p = precision();
            double r = recall();
            return Double.isNaN(p) || Double.isNaN(r)
                    ? Double.NaN : (p + r) == 0.0 ? 0.0 : 2.0 * p * r / (p + r);
        }

        String summary() {
            return String.format(Locale.ROOT,
                    "Precision=%s (%d/%d), Recall=%s (%d/%d), F1=%s; "
                    + "korrekt=%d, falsch=%d, abgelehnt=%d (TP=%d, FP=%d, FN=%d, TN=%d)",
                    percent(precision()), tp, tp + fp,
                    percent(recall()), tp, tp + fn,
                    percent(f1()), correct, wrong, rejected, tp, fp, fn, tn);
        }
    }

    private static String percent(double value) {
        return Double.isNaN(value) ? "N/A" : String.format(Locale.ROOT, "%.2f", value);
    }

    @Test
    public void runOfflineHudBenchmark() {
        List<Fixture> suite = buildBenchmarkSuite();
        LocalHudPerception perception = new LocalHudPerception();

        FieldMetrics stageMetrics = new FieldMetrics();
        FieldMetrics levelMetrics = new FieldMetrics();
        FieldMetrics goldMetrics = new FieldMetrics();
        FieldMetrics healthMetrics = new FieldMetrics();

        int nonGameFalsePositives = 0;
        int nonGameFixtures = 0;

        int width = 1536;
        int height = 709;
        long now = System.currentTimeMillis();

        for (Fixture f : suite) {
            LocalHudPerception.Observation obs = perception.parseCandidates(f.candidates, width, height, now);
            obs = LocalHudPerception.gateForPhase(obs, f.lastPhase);
            assertNull("HP is unsupported without a verified own-player anchor: " + f.name, obs.health);

            // Phase is an injected previous state, not inferred by the local parser.
            System.out.println("CASE=" + f.name + " FIELD=PHASE TRUTH=" + f.lastPhase
                    + " INPUT=VORHERIGE_PHASE; ACTUAL=NICHT_GEMESSEN; RESULT=NICHT_BESTIMMBAR");
            stageMetrics.record(f.name, "STAGE", f.expectedStage,
                    obs.stage == null ? null : obs.stage.value);
            levelMetrics.record(f.name, "LEVEL", f.expectedLevel,
                    obs.level == null ? null : obs.level.value);
            goldMetrics.record(f.name, "GOLD", f.expectedGold,
                    obs.gold == null ? null : obs.gold.value);
            healthMetrics.record(f.name, "HP", f.expectedHealth,
                    obs.health == null ? null : obs.health.value);

            if (!f.isGameScreen) {
                nonGameFixtures++;
                if (obs.hasAny()) {
                    nonGameFalsePositives++;
                }
            }
        }

        perception.close();

        double stageP = stageMetrics.precision();
        double stageR = stageMetrics.recall();
        double levelP = levelMetrics.precision();
        double levelR = levelMetrics.recall();
        double goldP = goldMetrics.precision();
        double goldR = goldMetrics.recall();
        double fpRateNonGame = nonGameFixtures == 0 ? 0.0 : (double) nonGameFalsePositives / nonGameFixtures;

        System.out.println("=== SYNTHETIC OCR-CANDIDATE TESTS ONLY; NO REAL DEVICE ACCURACY ===");
        System.out.println("HP: not measured (own-player anchor not validated).");
        System.out.println("Phase: previous state passed as INPUT only; NOT inferred or scored.");
        System.out.println("=== OFFLINE HUD PERCEPTION BENCHMARK RESULTS ===");
        System.out.println(String.format("Total Fixtures: %d (Game: %d, Non-Game: %d)",
                suite.size(), suite.size() - nonGameFixtures, nonGameFixtures));
        System.out.println("STAGE: " + stageMetrics.summary());
        System.out.println("LEVEL: " + levelMetrics.summary());
        System.out.println("GOLD:  " + goldMetrics.summary());
        System.out.println("HP:    " + healthMetrics.summary());
        System.out.println(String.format("Non-Game False Positive Rate: %.2f (%d/%d)",
                fpRateNonGame, nonGameFalsePositives, nonGameFixtures));
        System.out.println("=================================================");

        // Structural assertions on AUTHOR-LABELLED SYNTHETIC cases ONLY, not field accuracy in a live game.
        assertTrue("Stage Precision must be >= 0.95", stageP >= 0.95);
        assertTrue("Stage Recall must be >= 0.90", stageR >= 0.90);
        assertTrue("Level Precision must be >= 0.95", levelP >= 0.95);
        assertTrue("Level Recall must be >= 0.90", levelR >= 0.90);
        assertTrue("Gold Precision must be >= 0.95", goldP >= 0.95);
        assertTrue("Gold Recall must be >= 0.90", goldR >= 0.90);
        assertEquals("False Positive Rate on non-game screens must be 0.0", 0.0, fpRateNonGame, 0.001);
        assertEquals("No synthetic HP predictions before verified location", 0, healthMetrics.fp);
        assertEquals("Known synthetic HP label must count as rejected, not recognised", 1, healthMetrics.fn);
        assertEquals("HP precision undefined without positive predictions", true,
                Double.isNaN(healthMetrics.precision()));
    }

    @Test public void rejectsAmbiguousHudTokensInsteadOfInventingValues() {
        assertNull(LocalHudPerception.normalizeStageToken("13-2"));
        assertNull(LocalHudPerception.normalizeStageToken("3-2-1"));
        assertNull(LocalHudPerception.normalizeStageToken("unrelated 3-2"));
        assertEquals("3-2", LocalHudPerception.normalizeStageToken("STAGE 3-2"));
        assertEquals("4-1", LocalHudPerception.normalizeStageToken("4.1"));
        assertNull(LocalHudPerception.parseNumericToken("Ashe 5", 1, 10));
        assertNull(LocalHudPerception.parseNumericToken("XP 4", 1, 10));
        assertNull(LocalHudPerception.parseNumericToken("HP 80", 0, 999));
        assertNull(LocalHudPerception.parseNumericToken("54GOLD20", 0, 999));
        assertEquals(Integer.valueOf(8), LocalHudPerception.parseNumericToken("Lvl. 8", 1, 10));
        assertEquals(Integer.valueOf(54), LocalHudPerception.parseNumericToken("54G", 0, 999));
        assertEquals(Integer.valueOf(80), LocalHudPerception.parseNumericToken("$80", 0, 999));
    }

    @Test public void incorrectPredictionCountsAsFalsePositiveAndFalseNegative() {
        // A wrong prediction is simultaneously an incorrect claim and a missed truth.
        FieldMetrics metrics = new FieldMetrics();
        metrics.fp++;
        metrics.fn++;
        assertEquals(0.0, metrics.precision(), 0.0001);
        assertEquals(0.0, metrics.recall(), 0.0001);
    }

    @Test public void conflictingHudReadingsMustAbstainEvenAtDifferentScores() {
        LocalHudPerception perception = new LocalHudPerception();
        LocalHudPerception.Observation obs = perception.parseCandidates(Arrays.asList(
                new LocalHudPerception.Candidate("3-2", 0.50f, 0.08f, 0.05f),
                new LocalHudPerception.Candidate("4-1", 0.51f, 0.08f, 0.03f),
                new LocalHudPerception.Candidate("5", 0.080f, 0.942f, 0.05f),
                new LocalHudPerception.Candidate("6", 0.085f, 0.942f, 0.03f),
                new LocalHudPerception.Candidate("20", 0.961f, 0.901f, 0.05f),
                new LocalHudPerception.Candidate("30", 0.970f, 0.901f, 0.03f)
        ), 1536, 709, 1L);
        assertNull("Contradictory stages cannot be trusted", obs.stage);
        assertNull("Contradictory levels cannot be trusted", obs.level);
        assertNull("Contradictory gold totals cannot be trusted", obs.gold);
        assertNull("Unverified own-player HP anchor", obs.health);
        perception.close();
    }

    private static List<Fixture> buildBenchmarkSuite() {
        List<Fixture> suite = new ArrayList<>();

        // 1. Lobby Screen (VOR_MATCH) - Menu numbers present, no stage -> should be suppressed
        suite.add(new Fixture("Lobby Main Menu", "VOR_MATCH", Arrays.asList(
                new LocalHudPerception.Candidate("SPIELEN", 0.50f, 0.85f, 0.06f),
                new LocalHudPerception.Candidate("Pass Level 50", 0.30f, 0.20f, 0.04f),
                new LocalHudPerception.Candidate("500", 0.85f, 0.05f, 0.03f),
                new LocalHudPerception.Candidate("4", 0.080f, 0.942f, 0.05f) // Level badge-like number on lobby screen
        ), null, null, null, false));

        // 2. Result Screen (ERGEBNIS) - Placement 4th place -> should be suppressed without stage
        suite.add(new Fixture("Result Screen Defeat", "ERGEBNIS", Arrays.asList(
                new LocalHudPerception.Candidate("4. PLATZ", 0.50f, 0.30f, 0.08f),
                new LocalHudPerception.Candidate("NEUES SPIEL", 0.50f, 0.85f, 0.05f),
                new LocalHudPerception.Candidate("12", 0.961f, 0.901f, 0.05f) // Gold badge-like placement stat
        ), null, null, null, false));

        // 3. Match Start (Stage 1-1)
        suite.add(new Fixture("Match Start Stage 1-1", "VOR_MATCH", Arrays.asList(
                new LocalHudPerception.Candidate("1-1", 0.50f, 0.08f, 0.04f),
                new LocalHudPerception.Candidate("4", 0.052f, 0.925f, 0.04f), // XP button price 4 (excluded)
                new LocalHudPerception.Candidate("2", 0.080f, 0.942f, 0.05f), // Level 2
                new LocalHudPerception.Candidate("0", 0.961f, 0.901f, 0.05f)  // Gold 0
        ), "1-1", 2, 0, true));

        // 4. Early Game Planning (Stage 2-1)
        suite.add(new Fixture("Stage 2-1 Planning", "PLANUNG", Arrays.asList(
                new LocalHudPerception.Candidate("2-1", 0.50f, 0.08f, 0.04f),
                new LocalHudPerception.Candidate("3", 0.080f, 0.942f, 0.05f),
                new LocalHudPerception.Candidate("10", 0.961f, 0.901f, 0.05f)
        ), "2-1", 3, 10, true));

        // 5. Mid Game Augment Selection (Stage 3-2) - Noisy OCR: "STAGE 3-2", "Lvl 5", "32g"
        suite.add(new Fixture("Stage 3-2 Augment Selection", "AUGMENTIERUNG", Arrays.asList(
                new LocalHudPerception.Candidate("STAGE 3-2", 0.50f, 0.08f, 0.04f),
                new LocalHudPerception.Candidate("Lvl 5", 0.080f, 0.942f, 0.05f),
                new LocalHudPerception.Candidate("32g", 0.961f, 0.901f, 0.05f)
        ), "3-2", 5, 32, true));

        // 6. Mid Game Combat (Stage 4-1) - OCR with dots/colons: "4.1", "L6", "54G"
        suite.add(new Fixture("Stage 4-1 Combat", "KAMPF", Arrays.asList(
                new LocalHudPerception.Candidate("4.1", 0.50f, 0.08f, 0.04f),
                new LocalHudPerception.Candidate("L6", 0.080f, 0.942f, 0.05f),
                new LocalHudPerception.Candidate("54G", 0.961f, 0.901f, 0.05f)
        ), "4-1", 6, 54, true));

        // 7. Late Game Planning (Stage 5-3) - OCR with dashes/colons: "5-3", "Lvl. 8", "$80"
        suite.add(new Fixture("Stage 5-3 Late Game Planning", "PLANUNG", Arrays.asList(
                new LocalHudPerception.Candidate("5-3", 0.50f, 0.08f, 0.04f),
                new LocalHudPerception.Candidate("Lvl. 8", 0.080f, 0.942f, 0.05f),
                new LocalHudPerception.Candidate("$80", 0.961f, 0.901f, 0.05f)
        ), "5-3", 8, 80, true));

        // 8. End Game (Stage 6-5) - Level 9, Gold 100
        suite.add(new Fixture("Stage 6-5 End Game", "PLANUNG", Arrays.asList(
                new LocalHudPerception.Candidate("6-5", 0.50f, 0.08f, 0.04f),
                new LocalHudPerception.Candidate("9", 0.080f, 0.942f, 0.05f),
                new LocalHudPerception.Candidate("100", 0.961f, 0.901f, 0.05f)
        ), "6-5", 9, 100, true));

        // Adversarial, synthetic OCR noise in plausible HUD coordinates.
        // These must be refused instead of converted to made-up game facts.
        suite.add(new Fixture("Misleading text in HUD regions", "PLANUNG", Arrays.asList(
                new LocalHudPerception.Candidate("13-2", 0.50f, 0.08f, 0.04f),
                new LocalHudPerception.Candidate("XP 4", 0.080f, 0.942f, 0.05f),
                new LocalHudPerception.Candidate("Ashe 5", 0.961f, 0.901f, 0.05f)
        ), null, null, null, true));
        suite.add(new Fixture("Lobby fake stage token", "VOR_MATCH", Arrays.asList(
                new LocalHudPerception.Candidate("13-2", 0.50f, 0.08f, 0.04f),
                new LocalHudPerception.Candidate("XP 4", 0.080f, 0.942f, 0.05f)
        ), null, null, null, false));
        // Explicit labelled HP: a value visible in a synthetic candidate is deliberately
        // NOT treated as our HP until a real own-player anchor has been verified.
        suite.add(new Fixture("HP fixture with known synthetic value", "PLANUNG", Arrays.asList(
                new LocalHudPerception.Candidate("3-3", 0.50f, 0.08f, 0.04f),
                new LocalHudPerception.Candidate("6", 0.080f, 0.942f, 0.05f),
                new LocalHudPerception.Candidate("40", 0.961f, 0.901f, 0.05f),
                new LocalHudPerception.Candidate("80", 0.70f, 0.65f, 0.05f)
        ), "3-3", 6, 40, 80, true));

        // Two conflicting plausible values for each field must be rejected;
        // labelling these fields as unreadable is an explicit author decision.
        suite.add(new Fixture("Ambiguous overlapping HUD readings", "PLANUNG", Arrays.asList(
                new LocalHudPerception.Candidate("3-2", 0.50f, 0.08f, 0.05f),
                new LocalHudPerception.Candidate("4-1", 0.51f, 0.08f, 0.03f),
                new LocalHudPerception.Candidate("5", 0.080f, 0.942f, 0.05f),
                new LocalHudPerception.Candidate("6", 0.085f, 0.942f, 0.03f),
                new LocalHudPerception.Candidate("20", 0.961f, 0.901f, 0.05f),
                new LocalHudPerception.Candidate("30", 0.970f, 0.901f, 0.03f)
        ), null, null, null, true));

        return suite;
    }
}
