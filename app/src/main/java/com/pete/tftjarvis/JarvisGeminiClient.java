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
 * Opt-in Gemini text inference. On-device speech capture and Android TTS are separate.
 * A provider's free tier is not a billing guarantee. Never call without explicit consent.
 */
final class JarvisGeminiClient {
    static final String MODEL = "gemini-2.5-flash";
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent";
    private static final int MAX_RESPONSE_BYTES = 262144;

    String answer(String apiKey, String question, List<String[]> priorTurns,
                  JarvisAiClient.CancelToken token) throws IOException {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IOException("Kein Gemini-API-Schlüssel.");
        if (question == null || question.trim().isEmpty() || question.length() > 4000) {
            throw new IOException("Bitte eine Frage mit höchstens 4000 Zeichen eingeben.");
        }
        if (token.isCanceled()) throw new IOException("Abgebrochen.");

        final JSONObject payload;
        try {
            JSONArray contents = new JSONArray();
            for (String[] turn : priorTurns) {
                if (turn != null && turn.length >= 2) {
                    contents.put(message("user", turn[0]));
                    contents.put(message("model", turn[1]));
                }
            }
            contents.put(message("user", question.trim()));
            payload = new JSONObject()
                    .put("systemInstruction", new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text",
                                    "Du bist JARVIS, ein hilfreicher deutschsprachiger Sprachassistent. "
                                    + "Antworte natürlich, präzise und nicht zu ausführlich. "
                                    + "Du hast keinen Zugriff auf andere ChatGPT-Gespräche oder Apps. "
                                    + "Behaupte niemals, eine Geräteaktion ausgeführt zu haben."))))
                    .put("contents", contents)
                    .put("generationConfig", new JSONObject().put("maxOutputTokens", 800));
        } catch (JSONException e) {
            throw new IOException("Gemini-Anfrage konnte nicht vorbereitet werden.");
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            token.connection = conn;
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(35000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("x-goog-api-key", apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            int status = conn.getResponseCode();
            if (status == 400) throw new IOException("Gemini hat die Anfrage abgelehnt. Modell oder API-Einstellungen prüfen.");
            if (status == 401 || status == 403) {
                throw new IOException("Gemini-Zugang abgelehnt. API-Schlüssel, Modellzugriff und Region prüfen.");
            }
            if (status == 429) {
                throw new IOException("Gemini-Kontingent aufgebraucht. Keine automatische kostenpflichtige Umschaltung.");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Gemini nicht verfügbar (HTTP " + status + ").");
            }
            String response;
            try (InputStream in = conn.getInputStream()) {
                response = readLimited(in);
            }
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            return extractOutput(response);
        } catch (JSONException e) {
            throw new IOException("Gemini hat eine ungültige Antwort gesendet.");
        } finally {
            token.connection = null;
            if (conn != null) conn.disconnect();
        }
    }

    private static JSONObject message(String role, String text) throws JSONException {
        return new JSONObject().put("role", role).put("parts",
                new JSONArray().put(new JSONObject().put("text", text)));
    }

    static String extractOutput(String json) throws JSONException, IOException {
        JSONObject root = new JSONObject(json);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IOException("Gemini lieferte keine Antwort (ggf. Inhaltsfilter).");
        }
        JSONObject first = candidates.optJSONObject(0);
        JSONObject content = first == null ? null : first.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && !part.optString("text").trim().isEmpty()
                        && !part.optBoolean("thought", false)) {
                    if (text.length() > 0) text.append("\n");
                    text.append(part.optString("text"));
                }
            }
        }
        if (text.toString().trim().isEmpty()) {
            throw new IOException("Gemini lieferte keinen Antworttext.");
        }
        return text.toString().trim();
    }

    private static String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int size;
        while ((size = in.read(buffer)) != -1) {
            if (out.size() + size > MAX_RESPONSE_BYTES) {
                throw new IOException("Gemini-Antwort überschreitet das Größenlimit.");
            }
            out.write(buffer, 0, size);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
