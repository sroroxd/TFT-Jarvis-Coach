package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;

public class HudRegionsTest {
    @Test public void firstGameFrameLevelTwoNotXpPurchaseCost() {
        assertTrue(HudRegions.OWN_LEVEL.contains(123f / 1536f, 668f / 709f));
        assertFalse(HudRegions.OWN_LEVEL.contains(80f / 1536f, 657f / 709f));
    }

    @Test public void laterGameFrameLevelFourIsInLevelBadge() {
        assertTrue(HudRegions.OWN_LEVEL.contains(123f / 1536f, 666f / 709f));
        assertFalse(HudRegions.OWN_LEVEL.contains(78f / 1536f, 657f / 709f));
    }

    @Test public void goldBadgeRejectsCombatTimer() {
        assertTrue(HudRegions.OWN_GOLD.contains(1477f / 1536f, 639f / 709f));
        assertFalse(HudRegions.OWN_GOLD.contains(925f / 1536f, 10f / 709f));
    }
}
