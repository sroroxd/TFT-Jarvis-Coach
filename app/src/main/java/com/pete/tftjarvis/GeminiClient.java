package com.pete.tftjarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class GeminiClient {
    public static final String DEFAULT_MODEL = "gemini-3.8-flash";
    public static final String FALLBACK_MODEL = "gemini-3.5-flash-lite";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int PRIMARY_READ_TIMEOUT_MS = 16000;
    private static final int FALLBACK_READ_TIMEOUT_MS = 18000;

    public static final class RateLimitException extends Exception {
        public final int retryAfterSeconds;

        RateLimitException(int retryAfterSeconds) {
            super("Gemini Free-Limit erreicht");
            this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        }
    }

    private static final class MalformedResponseException extends Exception {
        MalformedResponseException() {
            super("Gemini-Antwort unvollständig");
        }
    }

    private static final class ApiException extends Exception {
        final int httpCode;
        final int retryAfterSeconds;
        final boolean retryable;

        ApiException(int httpCode, int retryAfterSeconds, boolean retryable, String message) {
            super(message);
            this.httpCode = httpCode;
            this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
            this.retryable = retryable;
        }
    }

    private GeminiClient() {}

    public static OpenAIClient.CoachResult coach(
            String apiKey,
            String requestedModel,
            String base64Jpeg,
            String previousState
    ) throws Exception {
        return coach(apiKey, requestedModel, base64Jpeg, null, null, previousState);
    }

    public static OpenAIClient.CoachResult coach(
            String apiKey,
            String requestedModel,
            String base64Jpeg,
            String detailBase64Jpeg,
            String detailLabel,
            String previousState
    ) throws Exception {
        String primary = normalizeModel(requestedModel);

        try {
            return call(primary, apiKey, base64Jpeg, detailBase64Jpeg, detailLabel, previousState,
                    FALLBACK_MODEL.equals(primary) ? FALLBACK_READ_TIMEOUT_MS : PRIMARY_READ_TIMEOUT_MS);
        } catch (SocketTimeoutException timeout) {
            if (FALLBACK_MODEL.equals(primary)) {
                throw new Exception("Gemini antwortet gerade zu langsam – erneut versuchen");
            }
            try {
                return call(FALLBACK_MODEL, apiKey, base64Jpeg, detailBase64Jpeg, detailLabel, previousState, FALLBACK_READ_TIMEOUT_MS);
            } catch (SocketTimeoutException secondTimeout) {
                throw new Exception("Gemini antwortet gerade zu langsam – erneut versuchen");
            } catch (ApiException second) {
                if (second.httpCode == 429) throw new RateLimitException(second.retryAfterSeconds);
                throw second;
            }
        } catch (MalformedResponseException malformed) {
            if (FALLBACK_MODEL.equals(primary)) {
                throw new Exception("Gemini-Antwort unvollständig – erneut versuchen");
            }
            try {
                return call(FALLBACK_MODEL, apiKey, base64Jpeg, detailBase64Jpeg, detailLabel,
                        previousState, FALLBACK_READ_TIMEOUT_MS);
            } catch (MalformedResponseException secondMalformed) {
                throw new Exception("Gemini-Antwort unvollständig – erneut versuchen");
            } catch (SocketTimeoutException timeout) {
                throw new Exception("Gemini antwortet gerade zu langsam – erneut versuchen");
            } catch (ApiException second) {
                if (second.httpCode == 429) throw new RateLimitException(second.retryAfterSeconds);
                throw second;
            }
        } catch (ApiException first) {
            if (!first.retryable || FALLBACK_MODEL.equals(primary)) {
                if (first.httpCode == 429) throw new RateLimitException(first.retryAfterSeconds);
                throw first;
            }

            try {
                return call(FALLBACK_MODEL, apiKey, base64Jpeg, detailBase64Jpeg, detailLabel, previousState, FALLBACK_READ_TIMEOUT_MS);
            } catch (SocketTimeoutException timeout) {
                throw new Exception("Gemini antwortet gerade zu langsam – erneut versuchen");
            } catch (ApiException second) {
                if (second.httpCode == 429) {
                    throw new RateLimitException(Math.max(first.retryAfterSeconds, second.retryAfterSeconds));
                }
                throw second;
            }
        }
    }

    private static String normalizeModel(String model) {
        if (model == null) return DEFAULT_MODEL;
        String m = model.trim();
        if (!m.startsWith("gemini-")) return DEFAULT_MODEL;
        return m;
    }

    private static OpenAIClient.CoachResult call(
            String model,
            String apiKey,
            String base64Jpeg,
            String detailBase64Jpeg,
            String detailLabel,
            String previousState,
            int readTimeoutMs
    ) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1/interactions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("x-goog-api-key", apiKey);
        conn.setRequestProperty("Content-Type", "application/json");

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("store", false);

        JSONArray content = new JSONArray();
        content.put(new JSONObject()
                .put("type", "text")
                .put("text", buildPrompt(previousState)));
        content.put(new JSONObject()
                .put("type", "image")
                .put("mime_type", "image/jpeg")
                .put("resolution", "medium")
                .put("data", base64Jpeg));

        if (detailBase64Jpeg != null && !detailBase64Jpeg.trim().isEmpty()) {
            String label = detailLabel == null || detailLabel.trim().isEmpty()
                    ? "Detailausschnitt"
                    : detailLabel.trim();
            content.put(new JSONObject()
                    .put("type", "text")
                    .put("text", "Zusätzlicher Zoom aus DEMSELBEN aktuellen Screenshot: " + label
                            + ". Nur zur besseren Lesbarkeit kleiner HUD-/Unit-Details. "
                            + "Das Vollbild bleibt die autoritative Quelle; ignoriere den Zoom, wenn er zur aktuellen Phase nicht passt."));
            content.put(new JSONObject()
                    .put("type", "image")
                    .put("mime_type", "image/jpeg")
                    .put("resolution", "high")
                    .put("data", detailBase64Jpeg));
        }

        JSONArray input = new JSONArray();
        input.put(new JSONObject()
                .put("type", "user_input")
                .put("content", content));
        body.put("input", input);

        body.put("response_format", new JSONObject()
                .put("type", "text")
                .put("mime_type", "application/json")
                .put("schema", buildSchema()));

        String thinking = FALLBACK_MODEL.equals(model) ? "minimal" : "low";
        body.put("generation_config", new JSONObject()
                .put("thinking_level", thinking)
                .put("max_output_tokens", 640));

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int code = conn.getResponseCode();
        String retryAfterHeader = conn.getHeaderField("Retry-After");
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String raw = readAll(stream);
        int retryAfter = parseRetryAfterSeconds(retryAfterHeader, raw);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            String message = compactApiError(code, raw);
            boolean retryable = code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
            throw new ApiException(code, retryAfter, retryable, message);
        }

        String text = extractOutputText(raw);
        if (text.trim().isEmpty()) throw new Exception("Gemini: keine Textantwort");

        try {
            return parseCoachJson(text);
        } catch (Exception malformed) {
            throw new MalformedResponseException();
        }
    }

    private static String buildPrompt(String previousState) {
        String state = previousState == null || previousState.trim().isEmpty()
                ? "keiner"
                : previousState.trim();

        return """
                Du bist JARVIS, ein schneller Teamfight-Tactics-Live-Coach für TFT Mobile.
                Verifizierte Spielreferenz: Set 18 "Verzauberte Wildnis", Live-Patch 18.5, Stand 23.10.2026.

                Ziel: Maximiere in der aktuellen Lobby die realistische Chance auf Platz 1.
                Nur wenn Platz 1 objektiv nicht mehr realistisch ist, sichere die bestmögliche Restplatzierung.

                Erkenne eine Phase: VOR_MATCH, PLANUNG, SHOP, AUGMENTIERUNG, KARUSSELL, KAMPF, ERGEBNIS oder UNKLAR.

                Phasen-Erkennung:
                - VOR_MATCH umfasst ausdrücklich den TFT-Homescreen/Lobby-Bildschirm vor einer Partie.
                - Wenn typische Homescreen-Elemente sichtbar sind, z. B. SPIELEN, SHOP, AUSRÜSTUNGEN,
                  FEEDBACK SENDEN, Patchnotizen, Battlepass oder Chibi-/Shop-Kacheln, ist phase=VOR_MATCH.
                - Ein klar erkennbarer TFT-Homescreen ist NICHT UNKLAR, nur weil noch kein Board sichtbar ist.
                - Auf dem Homescreen: call="TFT-Homescreen erkannt", priority=INFO, confidence=HOCH.
                - Warteschlange mit laufendem Such-Timer/Abbrechen-Schaltfläche: phase=VOR_MATCH, call="Warteschlange erkannt".
                - Spieler-Karten/Ladefortschritt vor Matchbeginn: phase=VOR_MATCH, call="Ladebildschirm erkannt".
                - Rangliste/Endergebnis mit Platzierungen und "ERNEUT SPIELEN": phase=ERGEBNIS,
                  call="Spiel beendet", priority=INFO, confidence=HOCH. Das ist niemals KAMPF.
                - UNKLAR nur verwenden, wenn weder TFT-Homescreen, Warteschlange, Ladebildschirm, Ergebnis noch eine Matchphase sicher erkennbar ist.

                Harte Regeln:
                - Das aktuelle Bild ist die primäre Wahrheit und überschreibt widersprechenden alten Status.
                - Nutze nur sicher sichtbare Fakten. Erfinde niemals Champions, Items, Augments, Attribute, Gold, Leben, Shop oder Gegnerdaten.
                - Bei entscheidender Unsicherheit: call = "Nicht sicher erkennbar".
                - AUGMENTIERUNG umfasst Augment-, Item-, Artefakt-, Amboss- und vergleichbare Auswahlfenster.
                - ENTSCHEIDUNGSMODUS: Sobald mehrere sichtbare Optionen gewählt werden müssen, MUSST du konkret entscheiden.
                  call beginnt dann exakt mit "NIMM: " und nennt den EXAKTEN sichtbaren Namen der besten Option.
                  plan nennt knapp den konkreten Grund und, wenn sicher lesbar, die zweitbeste Alternative.
                  Verboten sind Floskeln wie "nimm das beste", "wähle etwas Passendes", "je nach Team" oder das Zurückgeben der Entscheidung an den Spieler.
                  Sind die Optionsnamen nicht sicher lesbar, call="Auswahl nicht sicher lesbar – erneut analysieren", priority=INFO, confidence=NIEDRIG.
                - VOR_MATCH: nur Navigation; keine Matchstrategie erfinden. KAMPF: keine erfundenen Kauf-, Roll-, Level- oder Itemaktionen.
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
                - call: maximal drei unmittelbar sinnvolle Aktionen, höchstens 16 deutsche Wörter.
                - plan: höchstens 32 deutsche Wörter.
                - state: höchstens 52 deutsche Wörter und nur sichere, für die nächste Entscheidung relevante Fakten.
                - Karussell: Item priorisieren; Champion nur nennen, wenn sicher erkennbar.
                - new_match nur true, wenn ein frisch gestartetes Match gegenüber dem vorherigen Status eindeutig erkennbar ist; bei Zweifel false.
                - Strukturierte Beobachtungen:
                  level, gold, health: nur wenn sicher sichtbar, sonst -1.
                  board, bench, items, augments, traits: nur sicher erkannte Namen; unbekannt = [].
                  shop: nur die AKTUELL sichtbaren Shop-Champions; sonst [].
                  options: nur die AKTUELL sichtbaren auswählbaren Optionen eines Auswahlfensters
                  (Augments/Items/Artefakte/Amboss); außerhalb eines Auswahlfensters [].
                  augments enthält nur bereits bestätigte eigene Augments, NICHT die gerade angebotenen Optionen.
                  carry und tank: nur wenn sicher ableitbar, sonst leerer String.
                  stage: sichtbare Runde wie "2-5", sonst leerer String.
                  primary_trait: der aktuell stärkste erkennbare strategische Kern-Trait, z. B. "Ahnenholz"; sonst leer.
                  comp_plan: knapper strategischer Pfad für DIESE Partie, der auf Board/Traits/Items/Augments + bisherigem State aufbaut.
                  strategy_confidence: HOCH/MITTEL/NIEDRIG.
                  pivot_recommended: nur true, wenn ein Wechsel vom bisherigen Kernplan klar besser begründet ist.
                  pivot_reason: konkrete sichtbare Begründung; sonst leer.
                  Diese Felder beschreiben ausschließlich den AKTUELLEN Screenshot plus die fortlaufende Strategie; nichts raten.
                - priority:
                  KRITISCH = Entscheidung muss jetzt oder in wenigen Sekunden erfolgen.
                  WICHTIG = strategisch relevant, aber nicht sofort zeitkritisch.
                  INFO = Navigation, Beobachtung, Kampf-Warten, Unsicherheit oder Hintergrundinformation.
                - confidence:
                  HOCH = die für den call nötigen Fakten sind eindeutig sichtbar.
                  MITTEL = Kernentscheidung plausibel, aber mindestens ein Detail ist nicht eindeutig.
                  NIEDRIG = entscheidende sichtbare Fakten fehlen.
                - Bei "Nicht sicher erkennbar": priority=INFO und confidence=NIEDRIG.
                - Antworte ausschließlich gemäß JSON-Schema.

                Vorheriger Match-Kurzstatus: """ + state;
    }

    private static JSONObject buildSchema() throws Exception {
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
        properties.put("shop", new JSONObject(stringArray.toString()));
        properties.put("options", new JSONObject(stringArray.toString()));
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

        return new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray()
                        .put("phase").put("call").put("plan").put("state")
                        .put("new_match").put("priority").put("confidence")
                        .put("level").put("gold").put("health")
                        .put("board").put("bench").put("items").put("augments").put("traits")
                        .put("shop").put("options")
                        .put("carry").put("tank")
                        .put("stage").put("primary_trait").put("comp_plan")
                        .put("strategy_confidence").put("pivot_recommended").put("pivot_reason"))
                .put("additionalProperties", false);
    }

    private static OpenAIClient.CoachResult parseCoachJson(String text) throws Exception {
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
        List<String> shop = jsonStringList(parsed.optJSONArray("shop"));
        List<String> options = jsonStringList(parsed.optJSONArray("options"));
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
        if ("KAMPF".equals(phase)
                && ("Nicht sicher erkennbar".equals(call) || call.trim().isEmpty())) {
            call = "Warte auf Planungsphase";
            priority = "INFO";
        }

        return new OpenAIClient.CoachResult(
                phase, call, plan, state, newMatch, priority, confidence,
                level, gold, health, board, bench, items, augments, traits, shop, options, carry, tank,
                stage, primaryTrait, compPlan, strategyConfidence, pivotRecommended, pivotReason);
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

        JSONArray steps = response.optJSONArray("steps");
        if (steps != null) {
            for (int i = steps.length() - 1; i >= 0; i--) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                JSONArray content = step.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part == null || !"text".equals(part.optString("type"))) continue;
                    String t = part.optString("text", "").trim();
                    if (!t.isEmpty()) return t;
                }
            }
        }
        throw new Exception("Gemini: Antwortformat nicht erkannt");
    }

    private static String normalizePhase(String value) {
        if (value == null) return "UNKLAR";
        String v = value.trim().toUpperCase();
        switch (v) {
            case "VOR_MATCH":
            case "PLANUNG":
            case "SHOP":
            case "AUGMENTIERUNG":
            case "KARUSSELL":
            case "KAMPF":
            case "ERGEBNIS":
            case "UNKLAR":
                return v;
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

    private static String stripCodeFence(String text) {
        String s = text == null ? "" : text.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
        }
        return s.trim();
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

    private static int parseRetryAfterSeconds(String value, String raw) {
        if (value != null && !value.trim().isEmpty()) {
            try {
                double seconds = Double.parseDouble(value.trim());
                return Math.max(1, (int) Math.ceil(seconds));
            } catch (Exception ignored) {}
        }

        try {
            JSONObject root = new JSONObject(raw);
            JSONObject err = root.optJSONObject("error");
            JSONArray details = err == null ? null : err.optJSONArray("details");
            if (details != null) {
                for (int i = 0; i < details.length(); i++) {
                    JSONObject detail = details.optJSONObject(i);
                    if (detail == null) continue;
                    String delay = detail.optString("retryDelay", "");
                    if (delay.endsWith("s")) {
                        double seconds = Double.parseDouble(
                                delay.substring(0, delay.length() - 1)
                        );
                        return Math.max(1, (int) Math.ceil(seconds));
                    }
                }
            }
        } catch (Exception ignored) {}

        return 60;
    }

    private static String compactApiError(int code, String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                String status = err.optString("status", "");
                String msg = err.optString("message", "Gemini API-Fehler");
                String prefix = status.isEmpty() ? "" : status + ": ";
                String out = "Gemini " + code + ": " + prefix + msg;
                return out.length() > 180 ? out.substring(0, 180) + "…" : out;
            }
        } catch (Exception ignored) {}
        String cleaned = raw == null ? "" : raw.replace('\n', ' ').trim();
        if (cleaned.length() > 180) cleaned = cleaned.substring(0, 180) + "…";
        return "Gemini " + code + ": " + cleaned;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
