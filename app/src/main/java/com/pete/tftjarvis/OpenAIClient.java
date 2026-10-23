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
        public final String plan;
        public final String state;

        CoachResult(String call, String plan, String state) {
            this.call = call;
            this.plan = plan;
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
                Aktueller Referenzrahmen: Set 18 "Verzauberte Wildnis", Live-Patch 18.5, Stand 23.10.2026.

                Ziel:
                Jede Empfehlung soll die Wahrscheinlichkeit maximieren, diese Lobby zu gewinnen und Platz 1 zu erreichen.
                Spiele nicht automatisch auf eine sichere Top-4-Platzierung, solange eine realistische Siegchance besteht.
                Wenn Platz 1 realistisch nicht mehr erreichbar ist, sichere die bestmögliche Restplatzierung.

                Bildregeln:
                - Nutze nur Informationen, die im aktuellen Screenshot eindeutig erkennbar sind.
                - Der vorherige Kurzstatus ist nur Gedächtnisstütze. Das aktuelle Bild hat immer Vorrang.
                - Keine Vermutungen. Erfinde niemals Champion-, Gegenstands-, Artefakt-, Emblem-, Augmentierungs-, Attribut-, Gold-, Lebenspunkt- oder Shop-Informationen.
                - Keine veralteten Set-17-Empfehlungen oder Set-17-Bezeichnungen als aktuelle Referenz verwenden.
                - Wenn eine konkrete Information nicht sicher erkennbar ist, behaupte sie nicht.

                Strategische Analyse:
                - Bewerte nicht nur den Shop, sondern das gesamte sichtbare Board als System.
                - Prüfe aktive und naheliegende Synergien/Attribute, Frontline, Backline, Hauptcarry, Haupttank und Item-Halter.
                - Prüfe Gold, Level, Lebenspunkte, Bank, Streak und Tempo soweit sichtbar.
                - Entscheide, ob die aktuelle Linie gehalten, verstärkt oder verlassen werden sollte.
                - Leite einen realistischen Zielpfad für die nächsten Stufen ab: Reroll, Leveln, stabilisieren, Pivot oder auf Board-Cap skalieren.
                - Berücksichtige Contest und Gegner nur, wenn sie im Bild sicher erkennbar sind.
                - Bevorzuge den Pfad mit der höchsten realistischen Chance auf Platz 1, nicht bloß die bequemste Synergie.

                Entscheidungsreihenfolge:
                1. Zeitkritische Entscheidung: Augmentierung, Shop-Kauf, Rollen, Leveln, Gegenstand bauen, Positionierung.
                2. Boardstärke und aktuelle Synergien.
                3. Carry/Tank/Item-Halter und nächste sinnvolle Verstärkung.
                4. Econ-, Level- und Reroll-Plan.
                5. Ziel-Comp oder Pivot-Richtung, soweit aus sicheren Informationen ableitbar.
                6. Bevorzuge kurzfristige Stabilisierung, wenn deutliche Niederlagen die Siegchance stärker gefährden als weiteres Sparen.

                Shop-Regel:
                - Wenn mehrere Shop-Käufe gleichzeitig sinnvoll sind, nenne sie im selben call.
                - Priorisiere nur Käufe, die Board, Pair/Upgrade, Synergie, Carry/Tank-Pfad oder zukünftigen Pivot konkret verbessern.
                - Kaufe nicht automatisch jede brauchbare Einheit. Berücksichtige Goldschwellen und Econ.
                - Wenn ein sichtbarer Shop-Champion bewusst NICHT gekauft werden sollte, erwähne ihn nur, wenn das Weglassen strategisch wichtig ist.
                - Maximal drei unmittelbare Aktionen pro call, geordnet nach Priorität.

                Karussell-Regel:
                - Im Karussell niemals generisch "nächstgelegenen Champion" oder "irgendeinen Gegenstand" empfehlen.
                - Priorisiere den Gegenstand, der den aktuellen Carry-/Tank-/Itemplan am stärksten verbessert; Championwert ist sekundär, außer ein konkreter Champion vervollständigt ein wichtiges Upgrade oder ist außergewöhnlich wertvoll.
                - Nenne wenn sicher erkennbar konkret: "Nimm <Champion> mit <Gegenstand>".
                - Wenn der Champion nicht sicher erkennbar ist, aber der Gegenstand schon: "Nimm <Gegenstand>".
                - Wenn weder Zielchampion noch Zielgegenstand sicher erkennbar ist: call = "Karussellziel nicht sicher erkennbar".
                - Berücksichtige aktuelle Lebenspunkte/Pick-Reihenfolge nur soweit sichtbar; erfinde keine Erreichbarkeit.

                Ausgabe:
                - call = bis zu drei unmittelbar ausführbare Aktionen in einem Satz, maximal 16 deutsche Wörter.
                - plan = strategischer Kurzplan mit Synergie-/Board-Richtung, Carry/Tank und nächstem Meilenstein, maximal 32 deutsche Wörter.
                - state = sicher erkannter Match-Kurzstatus für die nächste Analyse, maximal 52 deutsche Wörter.
                - Wenn keine konkrete Aktion sicher ableitbar ist: call = "Nicht sicher erkennbar".
                - Wenn noch kein Match läuft, darf plan kurz den erkannten Vor-Match-Zustand nennen.
                - Keine Erklärtexte außerhalb des JSON.

                Antworte NUR als kompaktes JSON-Objekt:
                {"call":"bis zu drei Aktionen, maximal 16 deutsche Wörter","plan":"maximal 32 deutsche Wörter","state":"maximal 52 deutsche Wörter"}
                """;

        JSONObject body = new JSONObject();
        body.put("model", model == null || model.trim().isEmpty() ? "gpt-6-luna" : model);
        body.put("store", false);
        body.put("max_output_tokens", 2000);

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
            String call = clampWords(parsed.optString("call", "Nicht sicher erkennbar"), 16);
            String plan = clampWords(parsed.optString("plan", ""), 32);
            String state = clampWords(parsed.optString("state", ""), 52);
            if (call.trim().isEmpty()) call = "Nicht sicher erkennbar";
            return new CoachResult(call, plan, state);
        } catch (Exception ignored) {
            return new CoachResult(clampWords(text, 16), "", "");
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
