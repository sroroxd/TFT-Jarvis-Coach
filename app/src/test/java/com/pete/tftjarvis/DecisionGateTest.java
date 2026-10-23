package com.pete.tftjarvis;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class DecisionGateTest {
    private static OpenAIClient.CoachResult result(
            String phase, String call, List<String> shop, List<String> options) {
        return new OpenAIClient.CoachResult(
                phase, call, "", "", false, "INFO", "HOCH",
                5, 20, 90,
                null, null, null, null, null,
                shop, options,
                "", "", "2-1", "", "", "NIEDRIG", false, "");
    }

    @Test public void augmentNameMustNotMatchBeginningOfDifferentOption() {
        OpenAIClient.CoachResult raw = result("AUGMENTIERUNG", "NIMM: Eisenrüstung",
                null, Collections.singletonList("Eis"));
        assertTrue(DecisionGate.validate(raw, new MatchState(), null).blocked);
    }

    @Test public void fullyVisibleAugmentNameIsAccepted() {
        OpenAIClient.CoachResult raw = result("AUGMENTIERUNG", "NIMM: Eis",
                null, Collections.singletonList("Eis"));
        assertFalse(DecisionGate.validate(raw, new MatchState(), null).blocked);
    }

    @Test public void shopNameMustNotMatchAnotherChampionPrefix() {
        OpenAIClient.CoachResult raw = result("PLANUNG", "Kaufe Asher",
                Collections.singletonList("Ashe"), null);
        assertTrue(DecisionGate.validate(raw, new MatchState(), null).blocked);
    }

    @Test public void fullyVisibleShopNameIsAccepted() {
        OpenAIClient.CoachResult raw = result("PLANUNG", "Kaufe Ashe",
                Collections.singletonList("Ashe"), null);
        assertFalse(DecisionGate.validate(raw, new MatchState(), null).blocked);
    }
}
