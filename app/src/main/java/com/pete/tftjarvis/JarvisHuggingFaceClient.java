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

/** Opt-in Hugging Face text inference. No automatic paid fallback or tool execution. */
final class JarvisHuggingFaceClient {
    static final String DEFAULT_MODEL = "Qwen/Qwen3-32B";
    private static final String ENDPOINT = "https://router.huggingface.co/v1/chat/completions";
    private static final int MAX_BYTES = 262144;

    static String normalizeModel(String raw) {
        String id = raw == null || raw.trim().isEmpty() ? DEFAULT_MODEL : raw.trim();
        // No URL, whitespace, query strings or user-supplied destination.
        if (id.length() > 128 || !id.matches(
                "[A-Za-z0-9][A-Za-z0-9._-]{0,63}/[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
                + "(?::[A-Za-z0-9][A-Za-z0-9._-]{0,31})?")) {
            throw new IllegalArgumentException("Ungültige Hugging-Face-Modell-ID.");
        }
        return id;
    }

    static JSONObject buildRequest(String model, String question, List<String[]> history)
            throws IOException {
        if (question == null || question.trim().isEmpty() || question.length() > 4000) {
            throw new IOException("Bitte eine Frage mit höchstens 4000 Zeichen eingeben.");
        }
        final String selectedModel;
        try {
            selectedModel = normalizeModel(model);
        } catch (IllegalArgumentException e) {
            throw new IOException("Hugging-Face-Modell-ID ungültig (Organisation/Modell).");
        }
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content",
                    "Du bist JARVIS, ein deutschsprachiger persönlicher Sprachassistent. "
                    + "Antworte natürlich, präzise und kompakt. Du hast keinen Zugriff auf "
                    + "ChatGPT, andere Konten oder Smartphone-Apps. Behaupte niemals, "
                    + "eine Aktion ausgeführt zu haben, die nicht bestätigt wurde."));
            if (history != null) {
                for (int i = Math.max(0, history.size() - 4); i < history.size(); i++) {
                    String[] turn = history.get(i);
                    if (turn == null || turn.length < 2
                            || turn[0] == null || turn[1] == null) continue;
                    messages.put(new JSONObject().put("role", "user")
                            .put("content", limit(turn[0], 4000)));
                    messages.put(new JSONObject().put("role", "assistant")
                            .put("content", limit(turn[1], 2000)));
                }
            }
            messages.put(new JSONObject().put("role", "user")
                    .put("content", question.trim()));
            return new JSONObject().put("model", selectedModel)
                    .put("messages", messages).put("max_tokens", 700);
        } catch (JSONException e) {
            throw new IOException("Hugging-Face-Anfrage konnte nicht erstellt werden.");
        }
    }

    private static String limit(String value, int length) {
        return value.length() > length ? value.substring(0, length) : value;
    }

    String answer(String apiKey, String model, String question, List<String[]> history,
            JarvisAiClient.CancelToken token) throws IOException {
        if (apiKey == null || !apiKey.matches("[A-Za-z0-9_-]{12,256}")) {
            throw new IOException("Ungültiger Hugging-Face-Zugangstoken.");
        }
        if (token == null) throw new IOException("Abbruchstatus fehlt.");
        if (token.isCanceled()) throw new IOException("Abgebrochen.");
        JSONObject request = buildRequest(model, question, history);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            token.connection = conn;
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(35000);
            conn.setInstanceFollowRedirects(false); // Never forward Bearer token.
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            int status = conn.getResponseCode();
            if (status == 401 || status == 403) {
                throw new IOException("Hugging-Face-Token oder Berechtigungen ungültig.");
            }
            if (status == 402) {
                throw new IOException("Hugging-Face-Abrechnung/Kontingent prüfen. Kein Fallback.");
            }
            if (status == 400 || status == 404 || status == 422) {
                throw new IOException("Hugging-Face-Modell/Provider nicht verfügbar (HTTP "
                        + status + ").");
            }
            if (status == 429) {
                throw new IOException("Hugging-Face-Limit erreicht. Keine automatische Umschaltung.");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Hugging Face nicht verfügbar (HTTP " + status + ").");
            }
            final String response;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int size;
                while ((size = in.read(buffer)) != -1) {
                    if (bytes.size() + size > MAX_BYTES) {
                        throw new IOException("Hugging-Face-Antwort zu groß.");
                    }
                    bytes.write(buffer, 0, size);
                }
                response = bytes.toString(StandardCharsets.UTF_8.name());
            }
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            return extractOutput(response);
        } catch (JSONException e) {
            throw new IOException("Hugging Face lieferte fehlerhaftes JSON.");
        } catch (IOException e) {
            if (token.isCanceled()) throw new IOException("Abgebrochen.");
            throw e;
        } finally {
            token.connection = null;
            if (conn != null) conn.disconnect();
        }
    }

    static String extractOutput(String json) throws JSONException, IOException {
        JSONObject root = new JSONObject(json);
        JSONArray choices = root.optJSONArray("choices");
        JSONObject first = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = first == null ? null : first.optJSONObject("message");
        Object content = message == null ? null : message.opt("content");
        StringBuilder text = new StringBuilder();
        if (content instanceof String) {
            text.append((String) content);
        } else if (content instanceof JSONArray) {
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))
                        && !part.optString("text").trim().isEmpty()) {
                    if (text.length() > 0) text.append("\n");
                    text.append(part.optString("text"));
                }
            }
        }
        if (text.toString().trim().isEmpty()) {
            throw new IOException("Hugging Face lieferte keinen Antworttext.");
        }
        return text.toString().trim();
    }
}
