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

    @Test public void emptyResponseIsRejected() {
        try {
            GeminiClient.readValidatedOutputText("{}");
            fail("Empty transport response must be rejected");
        } catch (Exception expected) {
            assertEquals("Gemini-Antwort unvollständig", expected.getMessage());
        }
    }
}
