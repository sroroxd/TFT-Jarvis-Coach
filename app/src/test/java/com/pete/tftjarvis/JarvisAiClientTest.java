package com.pete.tftjarvis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;
import java.io.IOException;
import java.util.Collections;

public class JarvisAiClientTest {
    @Test public void parsesOnlyResponseText() throws Exception {
        String payload = "{\"output\":[{\"type\":\"message\",\"content\":["
                + "{\"type\":\"output_text\",\"text\":\"Guten Tag.\"},"
                + "{\"type\":\"output_text\",\"text\":\"JARVIS ist bereit.\"}]}]}";
        assertEquals("Guten Tag.\nJARVIS ist bereit.", JarvisAiClient.extractOutput(payload));
    }

    @Test public void rejectsNonTextOutput() throws Exception {
        try {
            JarvisAiClient.extractOutput("{\"output\":[{\"type\":\"reasoning\",\"content\":[]}]}");
            fail("Non-text output must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("keinen Antworttext"));
        }
    }

    @Test public void rejectsCanceledRequestBeforeNetwork() throws Exception {
        JarvisAiClient.CancelToken cancel = new JarvisAiClient.CancelToken();
        cancel.cancel();
        try {
            new JarvisAiClient().answer("not-a-real-key", "Hallo",
                    Collections.emptyList(), cancel);
            fail("Canceled request must not reach the network");
        } catch (IOException expected) {
            assertEquals("Abgebrochen.", expected.getMessage());
        }
    }

    @Test public void rejectsMissingTextBeforeNetwork() throws Exception {
        try {
            new JarvisAiClient().answer("not-a-real-key", " ",
                    Collections.emptyList(), new JarvisAiClient.CancelToken());
            fail("Empty question must be rejected before network");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("4000 Zeichen"));
        }
    }
}
