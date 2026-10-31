package com.pete.tftjarvis;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class JarvisHuggingFaceClientTest {
    @Test public void preparesBoundedChatWithoutTools() throws Exception {
        JSONObject request = JarvisHuggingFaceClient.buildRequest(
                "Qwen/Qwen3-32B", "Was sagte ich?", Arrays.asList(
                        new String[] {"Hallo", "Guten Tag"},
                        new String[] {"Wie geht es?", "Gut"}));
        assertEquals("Qwen/Qwen3-32B", request.getString("model"));
        assertEquals(700, request.getInt("max_tokens"));
        JSONArray messages = request.getJSONArray("messages");
        assertEquals(6, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("assistant", messages.getJSONObject(4).getString("role"));
        assertEquals("Was sagte ich?", messages.getJSONObject(5).getString("content"));
        assertFalse(request.has("tools"));
    }

    @Test public void validatesModelIdAndOptionalProviderSuffix() {
        assertEquals("openai/gpt-oss-120b:groq",
                JarvisHuggingFaceClient.normalizeModel("openai/gpt-oss-120b:groq"));
        assertEquals(JarvisHuggingFaceClient.DEFAULT_MODEL,
                JarvisHuggingFaceClient.normalizeModel(" "));
        for (String bad : new String[] {
                "only-a-model", "https://example.org/x", "org/model?secret=1",
                "org/model\nAuthorization: Bearer X", "../evil"
        }) {
            try {
                JarvisHuggingFaceClient.normalizeModel(bad);
                fail("Accepted model: " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("Modell-ID"));
            }
        }
    }

    @Test public void extractsTextButNotReasoningOrToolCalls() throws Exception {
        assertEquals("Hallo", JarvisHuggingFaceClient.extractOutput(
                "{\"choices\":[{\"message\":{\"content\":\" Hallo \"}}]}"));
        assertEquals("Antwort", JarvisHuggingFaceClient.extractOutput(
                "{\"choices\":[{\"message\":{\"content\":["
                        + "{\"type\":\"reasoning\",\"text\":\"intern\"},"
                        + "{\"type\":\"text\",\"text\":\"Antwort\"}]}}]}"));
        for (String bad : new String[] {
                "{\"choices\":[]}", "{\"choices\":[{\"message\":{\"tool_calls\":[{}]}}]}",
                "{\"choices\":[{\"message\":{\"content\":\" \"}}]}"
        }) {
            try {
                JarvisHuggingFaceClient.extractOutput(bad);
                fail("Empty response accepted.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("keinen Antworttext"));
            }
        }
    }

    @Test public void cancelsBeforeNetwork() throws Exception {
        JarvisAiClient.CancelToken token = new JarvisAiClient.CancelToken();
        token.cancel();
        try {
            new JarvisHuggingFaceClient().answer("hf_abcdefghijklmnopqrstuvwxyz",
                    JarvisHuggingFaceClient.DEFAULT_MODEL, "Hallo",
                    Collections.emptyList(), token);
            fail("Canceled request reached network.");
        } catch (IOException expected) {
            assertEquals("Abgebrochen.", expected.getMessage());
        }
    }

    @Test public void rejectsEmptyQuestionBeforeNetwork() throws Exception {
        try {
            new JarvisHuggingFaceClient().answer("hf_abcdefghijklmnopqrstuvwxyz",
                    JarvisHuggingFaceClient.DEFAULT_MODEL, " ",
                    Collections.emptyList(), new JarvisAiClient.CancelToken());
            fail("Empty question accepted.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("4000 Zeichen"));
        }
    }
}
