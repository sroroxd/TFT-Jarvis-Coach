package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;

public class JarvisWakePhraseTest {
    @Test public void wakeIsCaseInsensitive() {
        assertEquals("Status", JarvisWakePhrase.extract("Jarvis, Status"));
        assertEquals("was ist neu?", JarvisWakePhrase.extract("hey JARVIS was ist neu?"));
        assertEquals("", JarvisWakePhrase.extract("Jarvis!"));
    }

    @Test public void ordinarySpeechDoesNotTrigger() {
        assertNull(JarvisWakePhrase.extract("Wir reden gerade über Jarvis"));
        assertNull(JarvisWakePhrase.extract("Jarvisch ist kein Befehl"));
        assertNull(JarvisWakePhrase.extract(""));
    }

    @Test public void autoCloudRequiresExplicitConsentAndBudgetGate() {
        assertFalse(JarvisWakePhrase.autoAiAllowed(false, true, 0, 10000, 0));
        assertFalse(JarvisWakePhrase.autoAiAllowed(true, false, 0, 10000, 0));
        assertFalse(JarvisWakePhrase.autoAiAllowed(true, true, 12, 10000, 0));
        assertFalse(JarvisWakePhrase.autoAiAllowed(true, true, 3, 11000, 10000));
        assertTrue(JarvisWakePhrase.autoAiAllowed(true, true, 3, 17000, 10000));
    }
}
