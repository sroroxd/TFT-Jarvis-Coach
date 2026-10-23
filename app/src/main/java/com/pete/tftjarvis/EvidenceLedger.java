package com.pete.tftjarvis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small per-match evidence ledger.
 *
 * Facts are stored with source, confidence and age. A fresh local OCR value is
 * never silently replaced by a conflicting cloud observation. Agreement between
 * independent sources is promoted to VERIFIED_AGREEMENT.
 */
final class EvidenceLedger {
    enum Source {
        LOCAL_OCR,
        CLOUD_VISION,
        VERIFIED_AGREEMENT
    }

    static final class Evidence {
        final String key;
        final String value;
        final Source source;
        final String confidence;
        final long observedAt;
        final long epoch;

        Evidence(String key, String value, Source source, String confidence,
                 long observedAt, long epoch) {
            this.key = key;
            this.value = value;
            this.source = source;
            this.confidence = confidence;
            this.observedAt = observedAt;
            this.epoch = epoch;
        }
    }

    private static final long FRESH_CONFLICT_MS = 5_000L;
    // OCR and cloud can only corroborate each other when measured from the
    // same screenshot. Values a few seconds apart may be different rounds.
    private static final long SAME_FRAME_TOLERANCE_MS = 250L;
    private static final long PROMPT_MAX_AGE_MS = 45_000L;

    private final Map<String, Evidence> latest = new LinkedHashMap<>();
    private final List<Long> conflictTimes = new ArrayList<>();
    private long epoch = 1L;

    synchronized void recordLocal(LocalHudPerception.Observation observation) {
        if (observation == null) return;
        long at = observation.observedAt > 0L ? observation.observedAt : System.currentTimeMillis();
        if (observation.stage != null) {
            upsert("stage", observation.stage.value, Source.LOCAL_OCR,
                    observation.stage.confidence, at);
        }
        if (observation.level != null) {
            upsert("level", String.valueOf(observation.level.value), Source.LOCAL_OCR,
                    observation.level.confidence, at);
        }
        if (observation.gold != null) {
            upsert("gold", String.valueOf(observation.gold.value), Source.LOCAL_OCR,
                    observation.gold.confidence, at);
        }
        if (observation.health != null) {
            upsert("health", String.valueOf(observation.health.value), Source.LOCAL_OCR,
                    observation.health.confidence, at);
        }
    }

    synchronized void recordCloud(OpenAIClient.CoachResult result, long frameObservedAt) {
        if (result == null) return;
        long now = System.currentTimeMillis();
        // A model response can arrive many seconds after the screenshot was taken.
        // Both modalities must be dated to the SAME frame, not the network response.
        long at = frameObservedAt > 0L && frameObservedAt <= now ? frameObservedAt : now;
        String conf = normalizeConfidence(result.confidence);

        putCloud("phase", result.phase, conf, at);
        if (result.stage != null && !result.stage.trim().isEmpty()) putCloud("stage", result.stage, conf, at);
        if (result.level >= 0) putCloud("level", String.valueOf(result.level), conf, at);
        if (result.gold >= 0) putCloud("gold", String.valueOf(result.gold), conf, at);
        if (result.health >= 0) putCloud("health", String.valueOf(result.health), conf, at);
        putCloudList("board", result.board, conf, at);
        putCloudList("bench", result.bench, conf, at);
        putCloudList("items", result.items, conf, at);
        putCloudList("traits", result.traits, conf, at);
        putCloudList("augments", result.augments, conf, at);
        putCloudList("shop", result.shop, conf, at);
        putCloudList("options", result.options, conf, at);
    }

    synchronized boolean hasFreshConflict() {
        pruneConflicts(System.currentTimeMillis());
        return !conflictTimes.isEmpty();
    }

    synchronized String toPromptContext() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (Evidence e : latest.values()) {
            if (e.epoch != epoch || now - e.observedAt > PROMPT_MAX_AGE_MS) continue;
            if (!any) {
                sb.append("\n--- EVIDENCE LEDGER ---\n");
                sb.append("Nutze diese belegten Fakten als harte Leitplanken. ")
                        .append("Bei Konflikt gilt frische lokale OCR vor unbestätigter Cloud-Wahrnehmung.\n");
                any = true;
            }
            sb.append(e.key).append('=').append(e.value)
                    .append(" [").append(e.source)
                    .append(", ").append(e.confidence)
                    .append(", ").append(ageSeconds(now, e.observedAt)).append("s]\n");
        }
        if (hasFreshConflict()) {
            sb.append("WARNUNG: Frischer Wahrnehmungskonflikt vorhanden. ")
                    .append("Keine zeitkritische Aktion auf widersprüchlichen HUD-Fakten aufbauen.\n");
        }
        return sb.toString();
    }

    synchronized String debugSummary() {
        long now = System.currentTimeMillis();
        int fresh = 0;
        int verified = 0;
        int local = 0;
        for (Evidence e : latest.values()) {
            if (e.epoch != epoch || now - e.observedAt > PROMPT_MAX_AGE_MS) continue;
            fresh++;
            if (e.source == Source.VERIFIED_AGREEMENT) verified++;
            if (e.source == Source.LOCAL_OCR) local++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("EVIDENZ: ").append(fresh)
                .append(" frisch, ").append(verified).append(" bestätigt");
        if (local > 0) sb.append(", ").append(local).append(" lokal");
        if (hasFreshConflict()) sb.append(" | KONFLIKT");
        return sb.toString();
    }

    synchronized void clear() {
        latest.clear();
        conflictTimes.clear();
        epoch++;
    }

    private void putCloud(String key, String value, String confidence, long at) {
        if (value == null || value.trim().isEmpty() || "UNKLAR".equalsIgnoreCase(value.trim())) return;
        upsert(key, value.trim(), Source.CLOUD_VISION, confidence, at);
    }

    private void putCloudList(String key, List<String> values, String confidence, long at) {
        if (values == null || values.isEmpty()) return;
        String joined = String.join(", ", values);
        if (!joined.trim().isEmpty()) upsert(key, joined, Source.CLOUD_VISION, confidence, at);
    }

    private void upsert(String key, Object rawValue, Source source, String confidence, long at) {
        if (rawValue == null) return;
        String value = String.valueOf(rawValue).trim();
        if (value.isEmpty()) return;

        Evidence old = latest.get(key);
        if (old != null && old.epoch == epoch) {
            boolean sameFrame = Math.abs(at - old.observedAt) <= SAME_FRAME_TOLERANCE_MS;
            if (sameFrame && sameValue(old.value, value)) {
                // Repeated observations from one modality are not independent evidence.
                // Only a local OCR + cloud vision agreement can be marked VERIFIED.
                boolean independent = (old.source == Source.LOCAL_OCR && source == Source.CLOUD_VISION)
                        || (old.source == Source.CLOUD_VISION && source == Source.LOCAL_OCR);
                Source resolved = independent || old.source == Source.VERIFIED_AGREEMENT
                        ? Source.VERIFIED_AGREEMENT : source;
                String conf = independent ? strongerConfidence(old.confidence, confidence)
                        : normalizeConfidence(confidence);
                latest.put(key, new Evidence(key, value, resolved,
                        conf, Math.max(at, old.observedAt), epoch));
                return;
            }

            if (sameFrame && !sameValue(old.value, value)) {
                boolean contradictsLocal = isTrustedLocal(old.source)
                        && source == Source.CLOUD_VISION;
                boolean contradictsCloud = old.source == Source.CLOUD_VISION
                        && source == Source.LOCAL_OCR;
                if (contradictsLocal || contradictsCloud) {
                    conflictTimes.add(System.currentTimeMillis());
                    pruneConflicts(System.currentTimeMillis());
                    // A fresh local reading takes precedence regardless of which
                    // modality happened to be recorded first.
                    if (contradictsLocal) return;
                }
            }

            // A delayed observation must not rewind the factual HUD state.
            if (at < old.observedAt) return;
        }

        latest.put(key, new Evidence(key, value, source,
                normalizeConfidence(confidence), at, epoch));
    }

    private static boolean isTrustedLocal(Source source) {
        return source == Source.LOCAL_OCR || source == Source.VERIFIED_AGREEMENT;
    }

    private void pruneConflicts(long now) {
        conflictTimes.removeIf(t -> now - t > FRESH_CONFLICT_MS);
    }

    private static boolean sameValue(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private static long ageSeconds(long now, long at) {
        return Math.max(0L, (now - at) / 1000L);
    }

    private static String normalizeConfidence(String value) {
        if (value == null) return "NIEDRIG";
        String v = value.trim().toUpperCase(Locale.ROOT);
        if ("HOCH".equals(v) || "MITTEL".equals(v) || "NIEDRIG".equals(v)) return v;
        return "NIEDRIG";
    }

    private static String strongerConfidence(String a, String b) {
        if ("HOCH".equals(a) || "HOCH".equals(b)) return "HOCH";
        if ("MITTEL".equals(a) || "MITTEL".equals(b)) return "MITTEL";
        return "NIEDRIG";
    }
}
