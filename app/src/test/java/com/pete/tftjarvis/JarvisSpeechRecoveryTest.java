package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;

public final class JarvisSpeechRecoveryTest {
    @Test public void silenceIsNormalAndDoesNotCountAsRepeatedFailure() {
        assertTrue(JarvisSpeechRecovery.isSilence(6));
        assertTrue(JarvisSpeechRecovery.isSilence(7));
        assertFalse(JarvisSpeechRecovery.mustStop(6));
        assertEquals(1700L, JarvisSpeechRecovery.delayMs(7, 8));
    }

    @Test public void busyIsRecoverableButNotInATightLoop() {
        assertFalse(JarvisSpeechRecovery.mustStop(8));
        assertTrue(JarvisSpeechRecovery.delayMs(8, 1) >= 4500L);
        assertTrue(JarvisSpeechRecovery.delayMs(8, 4)
                > JarvisSpeechRecovery.delayMs(8, 1));
    }

    @Test public void permissionAndMissingLanguageStopImmediately() {
        for (int code : new int[] {9, 10, 12, 13, -100, -101}) {
            assertTrue("error " + code, JarvisSpeechRecovery.mustStop(code));
        }
    }

    @Test public void exponentialRetryIsCapped() {
        assertEquals(14000L, JarvisSpeechRecovery.delayMs(3, 99));
        assertTrue(JarvisSpeechRecovery.delayMs(5, 1) < JarvisSpeechRecovery.delayMs(5, 4));
    }

    @Test public void diagnosticsTellUserWhichAndroidErrorOccurred() {
        assertTrue(JarvisSpeechRecovery.diagnose(13).contains("Offline-Sprachpaket"));
        assertTrue(JarvisSpeechRecovery.actionableHint(9).contains("Mikrofon"));
        assertTrue(JarvisSpeechRecovery.diagnose(8).contains("beschäftigt"));
    }
}
