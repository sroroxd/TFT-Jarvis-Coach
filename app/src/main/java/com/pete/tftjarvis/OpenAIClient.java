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
        public final String phase;
        public final String call;
        public final String plan;
        public final String state;
        public final boolean newMatch;

        CoachResult(String phase, String call, String plan, String state, boolean newMatch) {
            this.phase = phase;
            this.call = call;
            this.plan = plan;
            this.state = state;
            this.newMatch = newMatch;
        }
    }

    public static final class RateLimitException extends Exception {
        public final int retryAfterSeconds;

        RateLimitException(int retryAfterSeconds) {
            super("Rate-Limit erreicht");
            this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
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
                Du bist JARVIS, ein schneller Teamfight-Tactics-Live-Coach.
                Referenz: Set 18 "Verzauberte Wildnis", Live-Patch 18.5, Stand 23.10.2026.

                Ziel:
                Maximiere die realistische Chance auf Platz 1. Wenn das nicht mehr realistisch ist, sichere die bestmögliche Restplatzierung.

                Erkenne genau eine Phase:
                VOR_MATCH, PLANUNG, SHOP, AUGMENTIERUNG, KARUSSELL, KAMPF oder UNKLAR.

                Harte Regeln:
                - Nutze nur sicher sichtbare Informationen aus dem aktuellen Screenshot.
                - Erfinde niemals Champions, Items, Augments, Attribute, Gold, Leben, Shop-Inhalte oder Gegnerdaten.
                - Das aktuelle Bild hat Vorrang vor dem vorherigen Kurzstatus.
                - In KAMPF keine Kauf-, Roll-, Level- oder Itemaktion erfinden; normalerweise call = "Warte auf Planungsphase".
                - In VOR_MATCH nur Navigation nennen.
                - Bei Unsicherheit call = "Nicht sicher erkennbar".

                Matchwechsel:
                - new_match = true nur bei eindeutig frisch gestartetem Match, wenn der vorherige Kurzstatus klar aus einem älteren Match stammt.
                - Bei VOR_MATCH bleibt new_match = false; die App verwirft den alten Kontext selbst.
                - Bei Zweifel new_match = false.

                Entscheidung:
                1. Zuerst zeitkritische Aktion: Augment, Kauf, Rollen, Leveln, Item, Positionierung.
                2. Dann Boardstärke, Synergien, Carry, Tank und Item-Halter.
                3. Danach Econ, Level-/Reroll-Plan und realistischer Pivot.
                - Maximal drei unmittelbare Aktionen im call.
                - Shop-Käufe nur nennen, wenn sie Board, Pair/Upgrade, Synergie, Carry/Tank-Pfad oder Pivot konkret verbessern.
                - Goldschwellen und Econ berücksichtigen.
                - Karussell: Item priorisieren; Champion nur konkret nennen, wenn sicher erkennbar.

                Ausgabe:
                - phase: genau eine erlaubte Phase.
                - call: maximal 16 deutsche Wörter, sofort ausführbar.
                - plan: maximal 32 deutsche Wörter, strategischer Kurzplan.
                - state: maximal 52 deutsche Wörter, nur sicher erkannter Match-Kurzstatus.
                - new_match: true oder false.
                - Keine Erklärtexte außerhalb des JSON.

                Antworte NUR als kompaktes JSON:
                {"phase":"VOR_MATCH|PLANUNG|SHOP|AUGMENTIERUNG|KARUSSELL|KAMPF|UNKLAR","call":"...","plan":"...","state":"...","new_match":false}
                """;

        JSONObject body = new JSONObject();
        body.put("model", model == null || model.trim().isEmpty() ? "gpt-6-luna" : model);
        body.put("store", false);
        body.put("max_output_tokens", 320);

        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "input_text").put("text",
                prompt + "\nVorheriger Kurzstatus: " +
                        (previousState == null || previousState.trim().isEmpty() ? "keiner" : previousState)));
        content.put(new JSONObject()
                .put("type", "input_image")
                .put("image_url", "data:image/jpeg;base64," + base64Jpeg)
                .put("detail", "auto"));

        JSONArray input = new JSONArray();
        input.put(new JSONObject().put("role", "user").put("content", content));
        body.put("input", input);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        String retryAfter = conn.getHeaderField("Retry-After");
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String raw = readAll(stream);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            if (code == 429 && isRetryableRateLimit(raw)) {
                throw new RateLimitException(parseRetryAfterSeconds(retryAfter));
            }
            throw new Exception(compactApiError(code, raw));
        }

        String text = extractOutputText(raw);
        if (text.trim().isEmpty()) throw new Exception("Keine Textantwort erhalten");

        try {
            JSONObject parsed = new JSONObject(stripCodeFence(text));
            String phase = normalizePhase(parsed.optString("phase", "UNKLAR"));
            String call = clampWords(parsed.optString("call", "Nicht sicher erkennbar"), 16);
            String plan = clampWords(parsed.optString("plan", ""), 32);
            String state = clampWords(parsed.optString("state", ""), 52);
            boolean newMatch = parsed.optBoolean("new_match", false);
            if (call.trim().isEmpty()) call = "Nicht sicher erkennbar";
            if ("KAMPF".equals(phase) && ("Nicht sicher erkennbar".equals(call) || call.trim().isEmpty())) {
                call = "Warte auf Planungsphase";
            }
            return new CoachResult(phase, call, plan, state, newMatch);
        } catch (Exception ignored) {
            return new CoachResult("UNKLAR", clampWords(text, 16), "", "", false);
        }
    }

    private static String extractOutputText(String raw) throws Exception {
        JSONObject response = new JSONObject(raw);

        String direct = response.optString("output_text", "").trim();
        if (!direct.isEmpty()) return direct;

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
        String status = response.optString("status", "");
        if ("incomplete".equals(status)) {
            JSONObject details = response.optJSONObject("incomplete_details");
            String reason = details == null ? "" : details.optString("reason", "");
            if ("max_output_tokens".equals(reason)) {
                throw new Exception("Antwortlimit erreicht – bitte erneut MERKEN");
            }
            throw new Exception("API-Antwort unvollständig" + (reason.isEmpty() ? "" : ": " + reason));
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

    private static String normalizePhase(String phase) {
        if (phase == null) return "UNKLAR";
        String p = phase.trim().toUpperCase();
        switch (p) {
            case "VOR_MATCH":
            case "PLANUNG":
            case "SHOP":
            case "AUGMENTIERUNG":
            case "KARUSSELL":
            case "KAMPF":
            case "UNKLAR":
                return p;
            default:
                return "UNKLAR";
        }
    }

    private static boolean isRetryableRateLimit(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            if (err == null) return false;
            String code = err.optString("code", "").toLowerCase();
            String type = err.optString("type", "").toLowerCase();
            String msg = err.optString("message", "").toLowerCase();
            if (code.contains("credit") || code.contains("spend") || code.contains("usage_limit") || type.contains("insufficient_quota")) {
                return false;
            }
            return code.contains("rate_limit") || type.contains("rate_limit") || msg.contains("rate limit");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int parseRetryAfterSeconds(String value) {
        if (value == null || value.trim().isEmpty()) return 60;
        try {
            double seconds = Double.parseDouble(value.trim());
            return Math.max(1, (int) Math.ceil(seconds));
        } catch (Exception ignored) {
            return 60;
        }
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
