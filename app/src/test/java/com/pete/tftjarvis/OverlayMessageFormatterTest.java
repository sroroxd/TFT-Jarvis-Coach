package com.pete.tftjarvis;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class OverlayMessageFormatterTest {
    @Test
    public void preMatchHomeScreenIsShortAndReadable() {
        assertEquals("TFT-Homescreen erkannt",
                OverlayMessageFormatter.message("VOR_MATCH", "TFT-Homescreen erkannt"));
        assertEquals("Warteschlange erkannt",
                OverlayMessageFormatter.message("VOR_MATCH", "Warteschlange erkannt"));
        assertEquals("Warte auf Matchstart",
                OverlayMessageFormatter.message("VOR_MATCH", "Warte auf Matchstart"));
    }

    @Test
    public void preMatchDoesNotShowTacticalPlans() {
        assertEquals("TFT-Homescreen erkannt",
                OverlayMessageFormatter.detail("VOR_MATCH", "TFT-Homescreen erkannt",
                        "Bereit für den Start des nächsten Matches."));
    }

    @Test
    public void unknownResultDoesNotInventAdvice() {
        assertEquals("Nicht sicher erkennbar",
                OverlayMessageFormatter.message("UNKLAR", null));
    }

    @Test
    public void unclearPhaseNeverShowsAnUnverifiedPlan() {
        assertEquals("Nicht sicher erkennbar",
                OverlayMessageFormatter.detail("UNKLAR", null, "Unbelegte Aktion"));
        assertEquals("Nicht sicher erkennbar",
                OverlayMessageFormatter.detail("PLANUNG", "Nicht sicher erkennbar", "Unbelegte Aktion"));
    }

    @Test
    public void normalDetailContainsOnlyHumanFacingCallAndPlan() {
        String detail = OverlayMessageFormatter.detail(
                "PLANUNG", "Artefakt erkannt", "Träger zunächst prüfen");
        assertEquals("Artefakt erkannt\nNächster Schritt: Träger zunächst prüfen", detail);
        assertFalse(detail.contains("SICHERHEIT"));
        assertFalse(detail.contains("EVIDENZ"));
        assertFalse(detail.contains("SPRACHE"));
    }

    @Test
    public void timeoutIsNotReportedAsProviderOverload() {
        assertEquals("Zeitüberschreitung – KI-Antwort dauerte zu lange",
                OverlayMessageFormatter.transientFailureMessage(new Exception("Gemini antwortet gerade zu langsam – erneut versuchen")));
        assertEquals("Zeitüberschreitung – KI-Antwort dauerte zu lange",
                OverlayMessageFormatter.transientFailureMessage(new java.net.SocketTimeoutException()));
        assertEquals("KI vorübergehend nicht verfügbar – später erneut versuchen",
                OverlayMessageFormatter.transientFailureMessage(new Exception("HTTP 503 unavailable")));
    }
}
