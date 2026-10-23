package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;

public class GeminiResponseTest {
    @Test public void extractsValidEnvelope() throws Exception {
        String raw = "{\"steps\":[{\"content\":[{\"type\":\"text\","
                + "\"text\":\"{\\\"phase\\\":\\\"UNKLAR\\\"}\"}]}]}";
        assertEquals("{\"phase\":\"UNKLAR\"}",
                GeminiClient.readValidatedOutputText(raw));
    }

    @Test public void truncatedTransportEnvelopeHasSafeFailure() {
        String malformed = "{\"steps\":[{\"content\":[{\"type\":\"text\",\"text\":\"";
        try {
            GeminiClient.readValidatedOutputText(malformed);
            fail("Must reject truncated JSON response");
        } catch (Exception expected) {
            assertEquals("Gemini-Antwort unvollständig", expected.getMessage());
            assertFalse(expected.getMessage().contains("character"));
        }
    }

    @Test public void truncatedModelBodyDoesNotBecomeAVerifiedObservation() {
        try {
            GeminiClient.parseCoachJson("{\"phase\":\"AUGMENTIERUNG\",\"call\":\"NIMM: Seelen");
            fail("Must reject truncated model output");
        } catch (Exception expected) {
            assertNotNull(expected);
        }
    }
}
