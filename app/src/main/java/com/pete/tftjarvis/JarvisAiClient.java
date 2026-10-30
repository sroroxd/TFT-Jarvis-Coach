package com.pete.tftjarvis;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Opt-in, explicitly invoked HTTPS client for a user's separate OpenAI API account.
 * There is no automatic linkage with ChatGPT conversations, memories or plugins.
 * The client never runs for local commands or automatically after speech recognition.
 */
final class JarvisAiClient {
    static final String MODEL = "gpt-4.1-mini";
    private static final String ENDPOINT = "https://api.openai.com/v1/responses";
    private static final int MAX_RESPONSE_BYTES = 262144;

    static final class CancelToken {
        private volatile boolean canceled;
        volatile HttpURLConnection connection;

        void cancel() {
            canceled = true;
            HttpURLConnection active = connection;
            if (active != null) active.disconnect();
        }

        boolean isCanceled() { return canceled; }
    }

    String answer(String apiKey, String question, List<String[]> priorTurns, CancelToken token)
            throws IOException {
        if (apiKey == null || apiKey.isEmpty()) throw new IOException("Kein API-Schlüssel.");
        if (question == null || question.trim().isEmpty() || question.length() > 4000) {
            throw new IOException("Bitte eine Frage mit höchstens 4000 Zeichen eingeben.");
        }
        if (token.isCanceled()) throw new IOException("Abgebrochen.");
        final JSONObject request;
        try {
            JSONArray input = new JSONArray();
            for (String[] turn : priorTurns) {
                if (turn != null && turn.length >= 2) {
                    input.put(new JSONObject().put("role", "user").put("content", turn[0]));
                    input.put(new JSONObject().put("role", "assistant").put("content", turn[1]));
                }
            }
            input.put(new JSONObject().put("role", "user").put("content", question.trim()));
            request = new JSONObject()
                    .put("model", MODEL)
                    .put("store", false)
                    .put("max_output_tokens", 700)
                    .put("instructions", "Du bist JARVIS, ein hilfreicher deutschsprachiger Android-Assistent. "
                            + "Antworte präzise, ehrlich und kompakt. Du bist eine eigenständige API-Instanz "
                            + "und hast keine direkte Einsicht in ChatGPT-Chats, Erinnerungen oder Plugins. "
                            + "Behaupte niemals, eine Smartphone-Aktion ausgeführt zu haben. "
                            + "Gib keine gesicherten Echtzeitinformationen ohne externe Quelle vor.")
                    .put("input", input);
        } catch (JSONException e) {
            throw new IOException("Anfrage konnte nicht vorbereitet werden.");
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            token.connection = conn;
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setUseCaches(false);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            int status = conn.getResponseCode();
            if (status == 401 || status == 403) {
                throw new IOException("API-Zugang abgelehnt. Schlüssel oder Berechtigungen prüfen.");
            }
            if (status == 429) {
                throw new IOException("API-Limit erreicht. Abrechnung oder Rate-Limit prüfen.");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("KI-Dienst nicht verfügbar (HTTP " + status + ").");
            }
            String response;
            try (InputStream inputStream = conn.getInputStream()) {
                response = readLimited(inputStream);
            }
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            return extractOutput(response);
        } catch (JSONException e) {
            throw new IOException("KI-Dienst hat eine ungültige Antwort gesendet.");
        } finally {
            token.connection = null;
            if (conn != null) conn.disconnect();
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (bytes.size() + read > MAX_RESPONSE_BYTES) {
                throw new IOException("Die Antwort des KI-Dienstes ist zu groß.");
            }
            bytes.write(buffer, 0, read);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    static String extractOutput(String json) throws JSONException, IOException {
        JSONObject root = new JSONObject(json);
        StringBuilder answer = new StringBuilder();
        JSONArray outputs = root.optJSONArray("output");
        if (outputs != null) {
            for (int i = 0; i < outputs.length(); i++) {
                JSONObject message = outputs.optJSONObject(i);
                if (message == null) continue;
                JSONArray content = message.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject segment = content.optJSONObject(j);
                    if (segment != null && "output_text".equals(segment.optString("type"))) {
                        if (answer.length() > 0) answer.append("\n");
                        answer.append(segment.optString("text"));
                    }
                }
            }
        }
        if (answer.toString().trim().isEmpty()) {
            throw new IOException("Der KI-Dienst lieferte keinen Antworttext.");
        }
        return answer.toString().trim();
    }
}
