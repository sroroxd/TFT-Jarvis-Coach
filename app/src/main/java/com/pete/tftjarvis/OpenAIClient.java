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
import java.util.ArrayList;
import java.util.List;

public final class OpenAIClient {
    public static final class CoachResult {
        public final String phase;
        public final String call;
        public final String plan;
        public final String state;
        public final boolean newMatch;
        public final String priority;
        public final String confidence;
        public final int level;
        public final int gold;
        public final int health;
        public final List<String> board;
        public final List<String> bench;
        public final List<String> items;
        public final List<String> augments;
        public final List<String> traits;
        public final String carry;
        public final String tank;
        public final String stage;
        public final String primaryTrait;
        public final String compPlan;
        public final String strategyConfidence;
        public final boolean pivotRecommended;
        public final String pivotReason;

        CoachResult(String phase, String call, String plan, String state, boolean newMatch,
                    String priority, String confidence,
                    int level, int gold, int health,
                    List<String> board, List<String> bench, List<String> items,
                    List<String> augments, List<String> traits,
                    String carry, String tank,
                    String stage, String primaryTrait, String compPlan,
                    String strategyConfidence, boolean pivotRecommended, String pivotReason) {
            this.phase = phase;
            this.call = call;
            this.plan = plan;
            this.state = state;
            this.newMatch = newMatch;
            this.priority = priority;
            this.confidence = confidence;
            this.level = level;
            this.gold = gold;
            this.health = health;
            this.board = board;
            this.bench = bench;
            this.items = items;
            this.augments = augments;
            this.traits = traits;
            this.carry = carry;
            this.tank = tank;
            this.stage = stage;
            this.primaryTrait = primaryTrait;
            this.compPlan = compPlan;
            this.strategyConfidence = strategyConfidence;
            this.pivotRecommended = pivotRecommended;
            this.pivotReason = pivotReason;
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
        return coach(apiKey, model, base64Jpeg, null, null, previousState);
    }

    public static CoachResult coach(
            String apiKey,
            String model,
            String base64Jpeg,
            String detailBase64Jpeg,
            String detailLabel,
            String previousState
    ) throws Exception {
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

                Ziel: Maximiere in der aktuellen Lobby die realistische Chance auf Platz 1.
                Falls Platz 1 objektiv nicht mehr realistisch ist, sichere die bestmögliche Restplatzierung.

                Erkenne eine Phase: VOR_MATCH, PLANUNG, SHOP, AUGMENTIERUNG, KARUSSELL, KAMPF, ERGEBNIS oder UNKLAR.

                Regeln:
                - Nutze nur sicher sichtbare Informationen aus dem aktuellen Screenshot plus den kurzen vorherigen Match-Status.
                - Das aktuelle Bild hat immer Vorrang. Erfinde niemals Champions, Items, Augments, Attribute, Gold, Leben, Shop oder Gegnerdaten.
                - Bei entscheidender Unsicherheit: call = "Nicht sicher erkennbar".
                - AUGMENTIERUNG umfasst Augment-, Item-, Artefakt-, Amboss- und vergleichbare Auswahlfenster.
                - ENTSCHEIDUNGSMODUS: Sobald mehrere sichtbare Optionen gewählt werden müssen, MUSST du konkret entscheiden.
                  call beginnt dann exakt mit "NIMM: " und nennt den EXAKTEN sichtbaren Namen der besten Option.
                  plan nennt knapp den konkreten Grund und, wenn sicher lesbar, die zweitbeste Alternative.
                  Verboten sind Floskeln wie "nimm das beste", "wähle etwas Passendes", "je nach Team" oder das Zurückgeben der Entscheidung an den Spieler.
                  Sind die Optionsnamen nicht sicher lesbar, call="Auswahl nicht sicher lesbar – erneut analysieren", priority=INFO, confidence=NIEDRIG.
                - VOR_MATCH: nur Navigation. Warteschlange bleibt VOR_MATCH; call="Warteschlange erkannt".
                - Rangliste/Endergebnis mit Platzierungen und "ERNEUT SPIELEN": phase=ERGEBNIS,
                  call="Spiel beendet", priority=INFO, confidence=HOCH. Das ist niemals KAMPF.
                - KAMPF: keine erfundenen Kauf-, Roll-, Level- oder Itemaktionen.
                - Behandle das Match als fortlaufende Partie, NICHT als unabhängige Screenshots.
                - Der vorherige Match-State enthält die bisherige strategische Richtung. Halte diese Richtung stabil,
                  solange der aktuelle Screenshot keinen klaren Grund für einen Pivot zeigt.
                - Wenn ein Kern-Trait/Comp-Pfad bereits erkennbar und mehrfach bestätigt ist, ist er VERBINDLICHE LEITLINIE.
                  Empfiehl keine zufälligen Shop-Einheiten außerhalb dieses Pfads.
                - Eine Off-Comp-Einheit darf nur empfohlen werden, wenn sie (a) ein direktes Pair/Upgrade einer bereits gespielten Einheit ist,
                  (b) eine klar notwendige Carry-/Tank-Lücke sofort schließt oder (c) Teil eines ausdrücklich begründeten Pivots ist.
                - Wenn im Shop nichts den Kernplan sinnvoll stärkt: lieber "Nichts kaufen; Gold halten" statt irgendeinen Champion zu kaufen.
                - Ändere primary_trait/comp_plan nicht wegen eines einzelnen Shops. pivot_recommended nur bei starkem, sichtbarem Grund.
                - Priorität: zeitkritische Aktion > bestehende Win-Condition/Comp-Kern > Boardstärke/Synergien/Carry/Tank/Items > Econ/Level/Reroll/Pivot.
                - Nenne im call höchstens drei unmittelbar sinnvolle Aktionen. Kaufe nur für Kern-Comp, Pair/Upgrade, Synergie, Carry/Tank-Pfad oder klaren Pivot.
                - Karussell: Item priorisieren; Champion nur nennen, wenn sicher erkennbar.
                - new_match nur true, wenn ein frisch gestartetes Match gegenüber dem vorherigen Kurzstatus eindeutig erkennbar ist; bei Zweifel false.
                - state enthält nur sicher erkannte, für die nächste Entscheidung relevante Match-Fakten.
                - Strukturierte Beobachtungen:
                  level, gold, health: nur wenn sicher sichtbar, sonst -1.
                  board, bench, items, augments, traits: nur sicher erkannte Namen; unbekannt = [].
                  carry und tank: nur wenn sicher ableitbar, sonst leerer String.
                  stage: sichtbare Runde wie "2-5", sonst leerer String.
                  primary_trait: der aktuell stärkste erkennbare strategische Kern-Trait, z. B. "Ahnenholz"; sonst leer.
                  comp_plan: knapper strategischer Pfad für DIESE Partie, der auf Board/Traits/Items/Augments + bisherigem State aufbaut.
                  strategy_confidence: HOCH/MITTEL/NIEDRIG.
                  pivot_recommended: nur true, wenn ein Wechsel vom bisherigen Kernplan klar besser begründet ist.
                  pivot_reason: konkrete sichtbare Begründung; sonst leer.
                  Diese Felder beschreiben ausschließlich den AKTUELLEN Screenshot plus die fortlaufende Strategie; nichts raten.
                - priority:
                  KRITISCH = Entscheidung muss jetzt oder in wenigen Sekunden erfolgen; Verzögerung kann die Runde/Option kosten.
                  WICHTIG = strategisch relevant für die nächsten Schritte, aber nicht sofort zeitkritisch.
                  INFO = Navigation, Beobachtung, Kampf-Warten, Unsicherheit oder Hintergrundinformation.
                - confidence:
                  HOCH = die für den call nötigen Fakten sind eindeutig sichtbar.
                  MITTEL = Kernentscheidung plausibel, aber mindestens ein Detail ist nicht eindeutig.
                  NIEDRIG = für einen konkreten Befehl fehlen entscheidende sichtbare Fakten.
                - Bei "Nicht sicher erkennbar" immer priority=INFO und confidence=NIEDRIG.
                - Antworte knapp auf Deutsch und fülle ausschließlich das vorgegebene JSON-Schema.
                """;

        JSONObject body = new JSONObject();
        body.put("model", model == null || model.trim().isEmpty() ? "gpt-6-luna" : model);
        body.put("store", false);
        body.put("max_output_tokens", 640);
        body.put("reasoning", new JSONObject().put("effort", "none"));

        JSONObject properties = new JSONObject();
        properties.put("phase", new JSONObject()
                .put("type", "string")
                .put("enum", new JSONArray()
                        .put("VOR_MATCH").put("PLANUNG").put("SHOP")
                        .put("AUGMENTIERUNG").put("KARUSSELL").put("KAMPF").put("ERGEBNIS").put("UNKLAR")));
        properties.put("call", new JSONObject().put("type", "string"));
        properties.put("plan", new JSONObject().put("type", "string"));
        properties.put("state", new JSONObject().put("type", "string"));
        properties.put("new_match", new JSONObject().put("type", "boolean"));
        properties.put("priority", new JSONObject()
                .put("type", "string")
                .put("enum", new JSONArray().put("KRITISCH").put("WICHTIG").put("INFO")));
        properties.put("confidence", new JSONObject()
                .put("type", "string")
                .put("enum", new JSONArray().put("HOCH").put("MITTEL").put("NIEDRIG")));
        properties.put("level", new JSONObject().put("type", "integer"));
        properties.put("gold", new JSONObject().put("type", "integer"));
        properties.put("health", new JSONObject().put("type", "integer"));
        JSONObject stringArray = new JSONObject()
                .put("type", "array")
                .put("items", new JSONObject().put("type", "string"));
        properties.put("board", stringArray);
        properties.put("bench", new JSONObject(stringArray.toString()));
        properties.put("items", new JSONObject(stringArray.toString()));
        properties.put("augments", new JSONObject(stringArray.toString()));
        properties.put("traits", new JSONObject(stringArray.toString()));
        properties.put("carry", new JSONObject().put("type", "string"));
        properties.put("tank", new JSONObject().put("type", "string"));
        properties.put("stage", new JSONObject().put("type", "string"));
        properties.put("primary_trait", new JSONObject().put("type", "string"));
        properties.put("comp_plan", new JSONObject().put("type", "string"));
        properties.put("strategy_confidence", new JSONObject()
                .put("type", "string")
                .put("enum", new JSONArray().put("HOCH").put("MITTEL").put("NIEDRIG")));
        properties.put("pivot_recommended", new JSONObject().put("type", "boolean"));
        properties.put("pivot_reason", new JSONObject().put("type", "string"));

        JSONObject schema = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray()
                        .put("phase").put("call").put("plan").put("state").put("new_match")
                        .put("priority").put("confidence")
                        .put("level").put("gold").put("health")
                        .put("board").put("bench").put("items").put("augments").put("traits")
                        .put("carry").put("tank")
                        .put("stage").put("primary_trait").put("comp_plan")
                        .put("strategy_confidence").put("pivot_recommended").put("pivot_reason"))
                .put("additionalProperties", false);

        body.put("text", new JSONObject().put("format", new JSONObject()
                .put("type", "json_schema")
                .put("name", "jarvis_coach")
                .put("strict", true)
                .put("schema", schema)));

        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "input_text").put("text",
                prompt + "\nVorheriger Kurzstatus: " +
                        (previousState == null || previousState.trim().isEmpty() ? "keiner" : previousState)));
        content.put(new JSONObject()
                .put("type", "input_image")
                .put("image_url", "data:image/jpeg;base64," + base64Jpeg)
                .put("detail", "auto"));

        if (detailBase64Jpeg != null && !detailBase64Jpeg.trim().isEmpty()) {
            String label = detailLabel == null || detailLabel.trim().isEmpty()
                    ? "Detailausschnitt"
                    : detailLabel.trim();
            content.put(new JSONObject().put("type", "input_text").put("text",
                    "Zusätzlicher Zoom aus DEMSELBEN aktuellen Screenshot: " + label
                            + ". Er dient nur zur besseren Lesbarkeit. Das Vollbild bleibt autoritativ; "
                            + "ignoriere den Zoom, wenn er zur aktuellen Phase nicht passt."));
            content.put(new JSONObject()
                    .put("type", "input_image")
                    .put("image_url", "data:image/jpeg;base64," + detailBase64Jpeg)
                    .put("detail", "high"));
        }

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
            String priority = normalizePriority(parsed.optString("priority", "INFO"));
            String confidence = normalizeConfidence(parsed.optString("confidence", "NIEDRIG"));
            int level = parsed.optInt("level", -1);
            int gold = parsed.optInt("gold", -1);
            int health = parsed.optInt("health", -1);
            List<String> board = jsonStringList(parsed.optJSONArray("board"));
            List<String> bench = jsonStringList(parsed.optJSONArray("bench"));
            List<String> items = jsonStringList(parsed.optJSONArray("items"));
            List<String> augments = jsonStringList(parsed.optJSONArray("augments"));
            List<String> traits = jsonStringList(parsed.optJSONArray("traits"));
            String carry = parsed.optString("carry", "").trim();
            String tank = parsed.optString("tank", "").trim();
            String stage = parsed.optString("stage", "").trim();
            String primaryTrait = parsed.optString("primary_trait", "").trim();
            String compPlan = clampWords(parsed.optString("comp_plan", ""), 26);
            String strategyConfidence = normalizeConfidence(parsed.optString("strategy_confidence", "NIEDRIG"));
            boolean pivotRecommended = parsed.optBoolean("pivot_recommended", false);
            String pivotReason = clampWords(parsed.optString("pivot_reason", ""), 22);
            if (call.trim().isEmpty()) call = "Nicht sicher erkennbar";
            if ("Nicht sicher erkennbar".equalsIgnoreCase(call.trim())) {
                priority = "INFO";
                confidence = "NIEDRIG";
            }
            if ("KAMPF".equals(phase) && ("Nicht sicher erkennbar".equals(call) || call.trim().isEmpty())) {
                call = "Warte auf Planungsphase";
                priority = "INFO";
            }
            return new CoachResult(phase, call, plan, state, newMatch, priority, confidence,
                    level, gold, health, board, bench, items, augments, traits, carry, tank,
                    stage, primaryTrait, compPlan, strategyConfidence, pivotRecommended, pivotReason);
        } catch (Exception ignored) {
            return new CoachResult("UNKLAR", "KI-Antwort unvollständig – erneut analysieren", "", "", false, "INFO", "NIEDRIG",
                    -1, -1, -1,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), "", "",
                    "", "", "", "NIEDRIG", false, "");
        }
    }

    private static List<String> jsonStringList(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length() && out.size() < 12; i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
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
            case "ERGEBNIS":
            case "UNKLAR":
                return p;
            default:
                return "UNKLAR";
        }
    }

    private static String normalizePriority(String value) {
        if (value == null) return "INFO";
        String v = value.trim().toUpperCase();
        if ("KRITISCH".equals(v) || "WICHTIG".equals(v) || "INFO".equals(v)) return v;
        return "INFO";
    }

    private static String normalizeConfidence(String value) {
        if (value == null) return "NIEDRIG";
        String v = value.trim().toUpperCase();
        if ("HOCH".equals(v) || "MITTEL".equals(v) || "NIEDRIG".equals(v)) return v;
        return "NIEDRIG";
    }

    private static boolean isRetryableRateLimit(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            if (err == null) return false;
            String code = err.optString("code", "").toLowerCase();
            String type = err.optString("type", "").toLowerCase();
            String msg = err.optString("message", "").toLowerCase();
            String joined = code + " " + type + " " + msg;
            if (joined.contains("credit") || joined.contains("billing") || joined.contains("quota")
                    || joined.contains("spend") || joined.contains("usage_limit")
                    || joined.contains("insufficient_quota")) {
                return false;
            }
            return joined.contains("rate_limit") || joined.contains("rate limit") || joined.contains("slow_down");
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
