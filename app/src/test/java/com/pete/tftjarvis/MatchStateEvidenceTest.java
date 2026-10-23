package com.pete.tftjarvis;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class MatchStateEvidenceTest {
    private static OpenAIClient.CoachResult cloud(int level, int gold, int hp) {
        return new OpenAIClient.CoachResult(
                "KAMPF", "Nicht sicher erkennbar", "", "", false,
                "INFO", "HOCH", level, gold, hp,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "", "", "1-3", "", "", "NIEDRIG",
                false, "");
    }

    @Test public void oneCloudImageCannotClaimThreeHighConfidenceHudNumbers() {
        MatchState state = new MatchState();
        state.apply(cloud(4, 5, 100));
        assertNull(state.level);
        assertNull(state.gold);
        assertNull(state.health);
    }

    @Test public void repeatedCloudObservationsStayMediumConfidence() {
        MatchState state = new MatchState();
        state.apply(cloud(2, 5, 100));
        state.apply(cloud(2, 5, 100));
        assertEquals(Integer.valueOf(2), state.level);
        assertEquals("MITTEL", state.levelConfidence);
        assertEquals(Integer.valueOf(5), state.gold);
        assertEquals("MITTEL", state.goldConfidence);
        assertEquals(Integer.valueOf(100), state.health);
        assertEquals("MITTEL", state.healthConfidence);
    }

    @Test public void contradictoryLowerLevelIsMarkedUncertainThenCorrected() {
        MatchState state = new MatchState();
        state.level = 4;
        state.levelConfidence = "HOCH";
        state.levelObservedAt = System.currentTimeMillis();
        state.apply(cloud(2, 5, -1));
        assertEquals(Integer.valueOf(4), state.level);
        assertEquals("NIEDRIG", state.levelConfidence);
        state.apply(cloud(2, 5, -1));
        assertEquals(Integer.valueOf(2), state.level);
        assertEquals("MITTEL", state.levelConfidence);
    }
}
