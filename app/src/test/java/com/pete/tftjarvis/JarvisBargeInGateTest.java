package com.pete.tftjarvis;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class JarvisBargeInGateTest {
    @Test public void rejectsExactTtsEcho() {
        assertTrue(JarvisBargeInGate.isLikelyEcho(
                "Das Wetter ist schön.", "Heute ist es sonnig. Das Wetter ist schön."));
        assertFalse(JarvisBargeInGate.mightBeInterruption(
                "Das Wetter ist schön", "Das Wetter ist schön."));
    }

    @Test public void rejectsOverlappingRecognizedTts() {
        assertTrue(JarvisBargeInGate.isLikelyEcho(
                "heute schönes wetter", "Heute gibt es schönes Wetter"));
    }

    @Test public void acceptsDifferentUserSpeech() {
        assertTrue(JarvisBargeInGate.mightBeInterruption(
                "Nein, ich meinte morgen!", "Heute regnet es in Hannover."));
        assertTrue(JarvisBargeInGate.mightBeInterruption(
                "Was ist mit Berlin?", "Hamburg liegt im Norden."));
    }

    @Test public void acceptsExplicitStopOnlyIfNotTtsEcho() {
        assertTrue(JarvisBargeInGate.isStopCommand("Jarvis, stopp!"));
        assertTrue(JarvisBargeInGate.mightBeInterruption("Stopp!", "Ich erkläre es dir."));
        assertFalse(JarvisBargeInGate.mightBeInterruption("Stopp", "Stopp ist ein Befehl."));
    }

    @Test public void finalOnlyDistinctRequestCanRecoverMissedPartial() {
        assertTrue(JarvisBargeInGate.isStrongFinalInterruption(
                "Nein, ich meinte eigentlich Berlin und morgen!",
                "In Hamburg ist es heute regnerisch und kalt."));
    }

    @Test public void finalOnlyEchoAndShortSpeechAreNotAutoDispatched() {
        assertFalse(JarvisBargeInGate.isStrongFinalInterruption(
                "Das Wetter ist schön.", "Heute ist sonnig. Das Wetter ist schön."));
        assertFalse(JarvisBargeInGate.isStrongFinalInterruption(
                "Nein", "Das Wetter ist schön."));
        assertFalse(JarvisBargeInGate.isStrongFinalInterruption(
                "In Hamburg ist es heute regnerisch und kalt",
                "In Hamburg ist es heute regnerisch und kalt"));
    }

    @Test public void finalOnlyStopCommandWorksWhenNotPartOfTts() {
        assertTrue(JarvisBargeInGate.isStrongFinalInterruption(
                "Stopp!", "Hier ist die ausführliche Antwort auf deine Frage."));
        assertFalse(JarvisBargeInGate.isStrongFinalInterruption(
                "Stopp", "Stopp ist ein Wort für Unterbrechen."));
    }

    @Test public void ignoresUncertainSingleWord() {
        assertFalse(JarvisBargeInGate.mightBeInterruption("Hallo", "Ich antworte gerade."));
        assertFalse(JarvisBargeInGate.mightBeInterruption("", "Ich antworte gerade."));
    }
}
