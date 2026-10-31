package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;

public final class JarvisSpeechStartGuardTest {
    @Test public void respectsMinimumStartSpacing() {
        JarvisSpeechStartGuard gate = new JarvisSpeechStartGuard();
        assertEquals(0, gate.waitBeforeStart(1000));
        gate.recordStart(1000);
        assertEquals(8000, gate.waitBeforeStart(1000));
        assertEquals(6000, gate.waitBeforeStart(3000));
        assertEquals(0, gate.waitBeforeStart(9000));
    }

    @Test public void quotaErrorLocksOutImmediateManualRestart() {
        JarvisSpeechStartGuard gate = new JarvisSpeechStartGuard();
        gate.recordStart(5000);
        gate.quotaExceeded(5100);
        assertEquals(120000, gate.waitBeforeStart(5100));
        assertEquals(70000, gate.waitBeforeStart(55100));
        assertEquals(0, gate.waitBeforeStart(125100));
    }

    @Test public void restoredCooldownCanOnlyTightenGateAndIsCapped() {
        JarvisSpeechStartGuard gate = new JarvisSpeechStartGuard();
        gate.recordStart(10000);
        gate.blockFor(10000, 60000);
        assertEquals(60000, gate.waitBeforeStart(10000));
        gate.blockFor(10500, 1000);
        assertEquals(59500, gate.waitBeforeStart(10500));
        gate.blockFor(11000, Long.MAX_VALUE);
        assertEquals(120000, gate.waitBeforeStart(11000));
    }
}
