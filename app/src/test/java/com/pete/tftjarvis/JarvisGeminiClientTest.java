package com.pete.tftjarvis;

import org.junit.Test;
import java.io.IOException;
import java.util.Collections;
import static org.junit.Assert.*;

public class JarvisGeminiClientTest {
    @Test public void extractsTextCandidate() throws Exception {
        assertEquals("Guten Abend.\nWas kann ich tun?",
                JarvisGeminiClient.extractOutput("{\"candidates\":[{\"content\":{\"parts\":["
                        + "{\"text\":\"Guten Abend.\"},{\"text\":\"Was kann ich tun?\"}]}}]}"));
    }

    @Test public void ignoresInternalThoughtParts() throws Exception {
        assertEquals("Ergebnis", JarvisGeminiClient.extractOutput(
                "{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"thought\":true,\"text\":\"intern\"},{\"text\":\"Ergebnis\"}]}}]}"));
    }

    @Test public void rejectsEmptyOrFilteredResponse() throws Exception {
        for (String payload : new String[] {
                "{\"candidates\":[]}",
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}",
                "{\"candidates\":[{\"content\":{\"parts\":[]}}]}"
        }) {
            try {
                JarvisGeminiClient.extractOutput(payload);
                fail("Empty Gemini output must not be interpreted as a reply.");
            } catch (IOException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        }
    }

    @Test public void neverCallsNetworkAfterCancel() throws Exception {
        JarvisAiClient.CancelToken token = new JarvisAiClient.CancelToken();
        token.cancel();
        try {
            new JarvisGeminiClient().answer("not-real-key", "Hallo",
                    Collections.emptyList(), token);
            fail("Canceled requests must fail.");
        } catch (IOException expected) {
            assertEquals("Abgebrochen.", expected.getMessage());
        }
    }

    @Test public void rejectsMissingQuestionBeforeNetwork() throws Exception {
        try {
            new JarvisGeminiClient().answer("not-real-key", " ",
                    Collections.emptyList(), new JarvisAiClient.CancelToken());
            fail("Empty question must fail.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("4000"));
        }
    }
}
