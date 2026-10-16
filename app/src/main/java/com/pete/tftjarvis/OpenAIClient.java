package com.pete.tftjarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class OpenAIClient {
    public static final class CoachResult {
        public final String call;
        public final String state;

        CoachResult(String call, String state) {
            this.call = call;
            this.state = state;
        }
    }

    private OpenAIClient() {}

    public static CoachResult coach(String apiKey, String model, String base64Jpeg, String previousState) throws Exception {
        URL url = new URL("https://api.openai.com/v1/responses");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(45000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");

        String prompt = """
                Du bist JARVIS, ein extrem schneller Teamfight-Tactics-Live-Coach.
                Ziel: maximale Top-1-Wahrscheinlichkeit, nicht nur Top 4.

                Analysiere ausschließlich Informationen, die im aktuellen Screenshot wirklich sichtbar sind. Nutze den vorherigen Kurzstatus nur als Gedächtnisstütze und verwerfe ihn sofort, wenn das Bild widerspricht.

                Prioritäten:
                1. Sofort kritische Entscheidungen: Augment, Shop-Kauf, Level/Roll, Item-Slam, Positionierung.
                2. Boardstärke, HP, Gold/Econ, Level, Shop, Bench, Items, Traits und Gegner soweit sichtbar.
                3. Spiele auf maximale Board-Cap; wenn Platz 1 objektiv unrealistisch wirkt, sichere die bestmögliche Platzierung.

                Sicherheitsregeln gegen Halluzinationen:
                - Erfinde niemals Champion-, Item-, Augment- oder Trait-Namen.
                - Wenn die konkrete Entscheidung nicht sicher sichtbar ist, call = "Nicht sicher erkennbar".
                - Keine langen Erklärungen im call.

                Antworte NUR als kompaktes JSON-Objekt:
                {"call":"maximal fünf deutsche Wörter","state":"maximal 28 deutsche Wörter zum aktuellen Boardzustand"}
                """;

        JSONObject body = new JSONObject();
        body.put("model", model == null || model.trim().isEmpty() ? "gpt-6-luna" : model);
        body.put("store", false);
        body.put("max_output_tokens", 120);

        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "input_text").put("text",
                prompt + "\nVorheriger Kurzstatus: " +
                        (previousState == null || previousState.trim().isEmpty() ? "keiner" : previousState)));
        content.put(new JSONObject()
                .put("type", "input_image")
                .put("image_url", "data:image/jpeg;base64," + base64Jpeg)
                .put("detail", "high"));

        JSONArray input = new JSONArray();
        input.put(new JSONObject().put("role", "user").put("content", content));
        body.put("input", input);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String raw = readAll(stream);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception(compactApiError(code, raw));
        }

        String text = extractOutputText(raw);
        if (text.trim().isEmpty()) throw new Exception("Keine Textantwort erhalten");

        try {
            JSONObject parsed = new JSONObject(stripCodeFence(text));
            String call = clampWords(parsed.optString("call", "Nicht sicher erkennbar"), 5);
            String state = clampWords(parsed.optString("state", ""), 28);
            if (call.trim().isEmpty()) call = "Nicht sicher erkennbar";
            return new CoachResult(call, state);
        } catch (Exception ignored) {
            return new CoachResult(clampWords(text, 5), "");
        }
    }

    private static String extractOutputText(String raw) throws Exception {
        JSONObject response = new JSONObject(raw);
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "";
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            JSONArray parts = item.optJSONArray("content");
            if (parts == null) continue;
            for (int j = 0; j < parts.length(); j++) {
                JSONObject part = parts.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) {
                    String text = part.optString("text", "").trim();
                    if (!text.isEmpty()) return text;
                }
            }
        }
        return "";
    }

    private static String stripCodeFence(String text) {
        String s = text.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        return s.trim();
    }

    private static String compactApiError(int code, String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                String msg = err.optString("message", "API-Fehler");
                return "API " + code + ": " + msg;
            }
        } catch (Exception ignored) {}
        if (raw == null) raw = "";
        raw = raw.replace('\n', ' ').trim();
        if (raw.length() > 220) raw = raw.substring(0, 220) + "…";
        return "API " + code + ": " + raw;
    }

    private static String clampWords(String text, int maxWords) {
        if (text == null) return "";
        String cleaned = text.replace('\n', ' ').trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return "";
        String[] words = cleaned.split(" ");
        if (words.length <= maxWords) return cleaned;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
