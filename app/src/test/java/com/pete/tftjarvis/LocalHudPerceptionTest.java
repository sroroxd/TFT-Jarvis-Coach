package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocalHudPerceptionTest {
    @Test public void lobbyNumbersAreNotAcceptedWithoutRoundStage() {
        LocalHudPerception.Observation menuNumbers = new LocalHudPerception.Observation(
                new LocalHudPerception.Field<>(5, "MITTEL"),
                new LocalHudPerception.Field<>(50, "MITTEL"),
                new LocalHudPerception.Field<>(100, "MITTEL"), null, 1234L);
        assertFalse(LocalHudPerception.gateForPhase(menuNumbers, "VOR_MATCH").hasAny());
    }

    @Test public void matchStartStageUnlocksLocalHudAfterLobby() {
        LocalHudPerception.Observation start = new LocalHudPerception.Observation(
                new LocalHudPerception.Field<>(3, "MITTEL"),
                new LocalHudPerception.Field<>(4, "MITTEL"), null,
                new LocalHudPerception.Field<>("1-1", "HOCH"), 1234L);
        LocalHudPerception.Observation accepted =
                LocalHudPerception.gateForPhase(start, "VOR_MATCH");
        assertSame(start, accepted);
        assertEquals("1-1", accepted.stage.value);
    }

    @Test public void resultPhaseAlsoRequiresStageToResumeHud() {
        LocalHudPerception.Observation placement = new LocalHudPerception.Observation(
                null, new LocalHudPerception.Field<>(2, "MITTEL"), null, null, 1234L);
        assertFalse(LocalHudPerception.gateForPhase(placement, "ERGEBNIS").hasAny());
    }

    @Test public void runningMatchCanReadHudWithoutVisibleStage() {
        LocalHudPerception.Observation current = new LocalHudPerception.Observation(
                null, new LocalHudPerception.Field<>(30, "MITTEL"), null, null, 1234L);
        assertSame(current, LocalHudPerception.gateForPhase(current, "PLANUNG"));
    }
}
