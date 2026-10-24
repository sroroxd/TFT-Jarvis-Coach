package com.pete.tftjarvis;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Offline HUD Perception Benchmark.
 *
 * Evaluates LocalHudPerception and HudRegions against a suite of offline test fixtures
 * representing various TFT game screens, noisy OCR candidate variations, and non-game
 * screens (Lobby, Result).
 *
 * Measures quantitative metrics: Precision, Recall, F1-Score, and False Positive Rate.
 */
public class HudPerceptionBenchmarkTest {

    static class Fixture {
        final String name;
        final String lastPhase;
        final List<LocalHudPerception.Candidate> candidates;
        final String expectedStage;
        final Integer expectedLevel;
        final Integer expectedGold;
        final boolean isGameScreen;

        Fixture(String name, String lastPhase, List<LocalHudPerception.Candidate> candidates,
                String expectedStage, Integer expectedLevel, Integer expectedGold, boolean isGameScreen) {
            this.name = name;
            this.lastPhase = lastPhase;
            this.candidates = candidates;
            this.expectedStage = expectedStage;
            this.expectedLevel = expectedLevel;
            this.expectedGold = expectedGold;
            this.isGameScreen = isGameScreen;
        }
    }

    static class FieldMetrics {
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int tn = 0;

        double precision() {
            return (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp);
        }

        double recall() {
            return (tp + fn) == 0 ? 1.0 : (double) tp / (tp + fn);
        }

        double f1() {
            double p = precision();
            double r = recall();
            return (p + r) == 0.0 ? 0.0 : 2.0 * p * r / (p + r);
        }
    }

    @Test
    public void runOfflineHudBenchmark() {
        List<Fixture> suite = buildBenchmarkSuite();
        LocalHudPerception perception = new LocalHudPerception();

        FieldMetrics stageMetrics = new FieldMetrics();
        FieldMetrics levelMetrics = new FieldMetrics();
        FieldMetrics goldMetrics = new FieldMetrics();

        int nonGameFalsePositives = 0;
        int nonGameFixtures = 0;

        int width = 1536;
        int height = 709;
        long now = System.currentTimeMillis();

        for (Fixture f : suite) {
            LocalHudPerception.Observation obs = perception.parseCandidates(f.candidates, width, height, now);
            obs = LocalHudPerception.gateForPhase(obs, f.lastPhase);
            assertNull("HP is unsupported without a verified own-player anchor: " + f.name, obs.health);

            // Stage evaluation
            String predStage = obs.stage != null ? obs.stage.value : null;
            if (f.expectedStage != null) {
                if (f.expectedStage.equals(predStage)) stageMetrics.tp++;
                else if (predStage != null) { stageMetrics.fp++; stageMetrics.fn++; }
                else stageMetrics.fn++;
            } else {
                if (predStage != null) stageMetrics.fp++;
                else stageMetrics.tn++;
            }

            // Level evaluation
            Integer predLevel = obs.level != null ? obs.level.value : null;
            if (f.expectedLevel != null) {
                if (f.expectedLevel.equals(predLevel)) levelMetrics.tp++;
                else if (predLevel != null) { levelMetrics.fp++; levelMetrics.fn++; }
                else levelMetrics.fn++;
            } else {
                if (predLevel != null) levelMetrics.fp++;
                else levelMetrics.tn++;
            }

            // Gold evaluation
            Integer predGold = obs.gold != null ? obs.gold.value : null;
            if (f.expectedGold != null) {
                if (f.expectedGold.equals(predGold)) goldMetrics.tp++;
                else if (predGold != null) { goldMetrics.fp++; goldMetrics.fn++; }
                else goldMetrics.fn++;
            } else {
                if (predGold != null) goldMetrics.fp++;
                else goldMetrics.tn++;
            }

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
        System.out.println("Phase: not independently inferred by this local OCR benchmark.");
        System.out.println("=== OFFLINE HUD PERCEPTION BENCHMARK RESULTS ===");
        System.out.println(String.format("Total Fixtures: %d (Game: %d, Non-Game: %d)",
                suite.size(), suite.size() - nonGameFixtures, nonGameFixtures));
        System.out.println(String.format("STAGE: Precision=%.2f, Recall=%.2f, F1=%.2f (TP=%d, FP=%d, FN=%d, TN=%d)",
                stageP, stageR, stageMetrics.f1(), stageMetrics.tp, stageMetrics.fp, stageMetrics.fn, stageMetrics.tn));
        System.out.println(String.format("LEVEL: Precision=%.2f, Recall=%.2f, F1=%.2f (TP=%d, FP=%d, FN=%d, TN=%d)",
                levelP, levelR, levelMetrics.f1(), levelMetrics.tp, levelMetrics.fp, levelMetrics.fn, levelMetrics.tn));
        System.out.println(String.format("GOLD:  Precision=%.2f, Recall=%.2f, F1=%.2f (TP=%d, FP=%d, FN=%d, TN=%d)",
                goldP, goldR, goldMetrics.f1(), goldMetrics.tp, goldMetrics.fp, goldMetrics.fn, goldMetrics.tn));
        System.out.println(String.format("Non-Game False Positive Rate: %.2f (%d/%d)",
                fpRateNonGame, nonGameFalsePositives, nonGameFixtures));
        System.out.println("=================================================");

        // Assertions for high accuracy, precision, recall and zero false positives on non-game screens
        assertTrue("Stage Precision must be >= 0.95", stageP >= 0.95);
        assertTrue("Stage Recall must be >= 0.90", stageR >= 0.90);
        assertTrue("Level Precision must be >= 0.95", levelP >= 0.95);
        assertTrue("Level Recall must be >= 0.90", levelR >= 0.90);
        assertTrue("Gold Precision must be >= 0.95", goldP >= 0.95);
        assertTrue("Gold Recall must be >= 0.90", goldR >= 0.90);
        assertEquals("False Positive Rate on non-game screens must be 0.0", 0.0, fpRateNonGame, 0.001);
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
        return suite;
    }
}
