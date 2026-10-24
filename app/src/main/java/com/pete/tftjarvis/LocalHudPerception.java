package com.pete.tftjarvis;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device perception for the tiny, high-value TFT Mobile HUD fields.
 *
 * The overlay is hidden before this runs, so OCR sees the clean game frame.
 * We deliberately keep numeric observations at MITTEL until either the cloud
 * agrees on the same frame or MatchState sees the same local value again.
 */
final class LocalHudPerception implements AutoCloseable {
    static final class Field<T> {
        final T value;
        final String confidence;

        Field(T value, String confidence) {
            this.value = value;
            this.confidence = confidence;
        }
    }

    static final class Observation {
        final Field<Integer> level;
        final Field<Integer> gold;
        final Field<Integer> health;
        final Field<String> stage;
        final long observedAt;

        Observation(Field<Integer> level, Field<Integer> gold, Field<Integer> health,
                    Field<String> stage, long observedAt) {
            this.level = level;
            this.gold = gold;
            this.health = health;
            this.stage = stage;
            this.observedAt = observedAt;
        }

        boolean hasAny() {
            return level != null || gold != null || health != null || stage != null;
        }

        String toPromptContext() {
            if (!hasAny()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("\n--- AKTUELLE LOKALE HUD-MESSUNG (GLEICHER FRAME) ---\n");
            sb.append("Diese Werte stammen aus lokaler OCR vor dem KI-Aufruf. ")
                    .append("Bei Konflikt nicht blind überschreiben; JARVIS verifiziert sie feldweise.\n");
            if (stage != null) sb.append("Stage lokal: ").append(stage.value)
                    .append(" [").append(stage.confidence).append("]\n");
            if (level != null) sb.append("Level lokal: ").append(level.value)
                    .append(" [").append(level.confidence).append("]\n");
            if (gold != null) sb.append("Gold lokal: ").append(gold.value)
                    .append(" [").append(gold.confidence).append("]\n");
            if (health != null) sb.append("HP lokal: ").append(health.value)
                    .append(" [").append(health.confidence).append("]\n");
            return sb.toString();
        }
    }

    static class Candidate {
        final String text;
        final Rect box;
        final float relX;
        final float relY;
        final float relHeight;

        Candidate(String text, Rect box) {
            this.text = text;
            this.box = box;
            this.relX = -1f;
            this.relY = -1f;
            this.relHeight = -1f;
        }

        Candidate(String text, float relX, float relY, float relHeight) {
            this.text = text;
            this.box = null;
            this.relX = relX;
            this.relY = relY;
            this.relHeight = relHeight;
        }

        float centerX(int width) {
            if (relX >= 0f) return relX;
            return (box == null || width <= 0) ? 0f : box.exactCenterX() / width;
        }

        float centerY(int height) {
            if (relY >= 0f) return relY;
            return (box == null || height <= 0) ? 0f : box.exactCenterY() / height;
        }

        float heightNorm(int height) {
            if (relHeight >= 0f) return relHeight;
            return (box == null || height <= 0) ? 0f : (float) box.height() / height;
        }
    }

    private static final Pattern STAGE_PATTERN = Pattern.compile("(?i)^(?:(?:STAGE|RUNDE)\\s*)?([1-9]-[1-9])$");
    private static final Pattern LEVEL_TOKEN_PATTERN = Pattern.compile("(?i)^(?:(?:LVL\\.?|LEVEL|L)\\s*)?([0-9]{1,2})$");
    private static final Pattern GOLD_TOKEN_PATTERN = Pattern.compile("(?i)^[$]?\\s*([0-9]{1,3})\\s*(?:G|GOLD)?$");

    private TextRecognizer recognizer;

    private synchronized TextRecognizer getRecognizer() {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        return recognizer;
    }

    Observation observe(Bitmap source, String lastPhase) {
        long now = System.currentTimeMillis();
        if (source == null || source.getWidth() < 320 || source.getHeight() < 180) {
            return empty(now);
        }

        Bitmap input = downscale(source, 1280);
        int width = input.getWidth();
        int height = input.getHeight();
        Task<Text> pending = null;
        try {
            pending = getRecognizer().process(InputImage.fromBitmap(input, 0));
            if (input != source) {
                final Bitmap taskBitmap = input;
                pending.addOnCompleteListener(done -> {
                    if (!taskBitmap.isRecycled()) taskBitmap.recycle();
                });
            }
            Text text = Tasks.await(pending, 900, TimeUnit.MILLISECONDS);
            return gateForPhase(parse(text, width, height, now), lastPhase);
        } catch (Exception ignored) {
            return empty(now);
        } finally {
            if (pending == null && input != source && !input.isRecycled()) {
                input.recycle();
            }
        }
    }

    static Observation gateForPhase(Observation observation, String lastPhase) {
        if (observation == null) return empty(System.currentTimeMillis());
        String phase = lastPhase == null ? "UNKLAR"
                : lastPhase.trim().toUpperCase(Locale.ROOT);
        if (("VOR_MATCH".equals(phase) || "ERGEBNIS".equals(phase))
                && observation.stage == null) {
            return empty(observation.observedAt);
        }
        return observation;
    }

    private Observation parse(Text text, int width, int height, long now) {
        List<Candidate> candidates = new ArrayList<>();
        if (text != null) {
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    for (Text.Element element : line.getElements()) {
                        Rect box = element.getBoundingBox();
                        String value = element.getText();
                        if (box != null && value != null && !value.trim().isEmpty()) {
                            candidates.add(new Candidate(value.trim(), box));
                        }
                    }
                }
            }
        }
        return parseCandidates(candidates, width, height, now);
    }

    Observation parseCandidates(List<Candidate> candidates, int width, int height, long now) {
        if (candidates == null) candidates = new ArrayList<>();
        Field<String> stage = findStage(candidates, width, height);
        Field<Integer> level = findNumber(candidates, width, height,
                1, 10, HudRegions.OWN_LEVEL);
        Field<Integer> gold = findNumber(candidates, width, height,
                0, 999, HudRegions.OWN_GOLD);
        Field<Integer> health = null; // No own-player HP anchor validated yet.

        return new Observation(level, gold, health, stage, now);
    }

    private Field<String> findStage(List<Candidate> candidates, int width, int height) {
        Candidate best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        String bestValue = null;

        for (Candidate c : candidates) {
            float x = c.centerX(width);
            float y = c.centerY(height);
            if (x < 0.24f || x > 0.76f || y > 0.25f) continue;

            String normalized = normalizeStageToken(c.text);
            if (normalized == null) continue;

            float score = c.heightNorm(height) * 100f
                    - Math.abs(x - 0.50f) * 16f
                    - y * 8f;
            if (score > bestScore) {
                best = c;
                bestScore = score;
                bestValue = normalized;
            }
        }

        return best == null ? null : new Field<>(bestValue, "HOCH");
    }

    static String normalizeStageToken(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim()
                .replace('–', '-')
                .replace('—', '-')
                .replace('.', '-')
                .replace(':', '-')
                .replace('_', '-');
        cleaned = cleaned.replaceAll("\\s*-\\s*", "-");
        Matcher m = STAGE_PATTERN.matcher(cleaned);
        if (m.matches()) {
            return m.group(1);
        }
        return null;
    }

    private Field<Integer> findNumber(
            List<Candidate> candidates,
            int width,
            int height,
            int min,
            int max,
            HudRegions.Region region
    ) {
        Candidate best = null;
        Integer bestValue = null;
        float bestScore = Float.NEGATIVE_INFINITY;

        for (Candidate c : candidates) {
            float x = c.centerX(width);
            float y = c.centerY(height);
            if (!region.contains(x, y)) continue;

            Integer value = parseNumericToken(c.text, min, max);
            if (value == null) continue;

            float distance = Math.abs(x - region.expectedX) + Math.abs(y - region.expectedY);
            float score = c.heightNorm(height) * 120f - distance * 18f;
            if (score > bestScore) {
                best = c;
                bestValue = value;
                bestScore = score;
            }
        }

        return best == null ? null : new Field<>(bestValue, "MITTEL");
    }

    static Integer parseNumericToken(String raw, int min, int max) {
        if (raw == null) return null;
        String cleaned = raw.trim();
        if (cleaned.isEmpty()) return null;

        // OCR tokens must match the WHOLE expected HUD label. Stripping all
        // non-digits would turn "Ashe 5", "XP 4" or unrelated UI text into
        // falsely confirmed level/gold readings.
        Pattern pattern = (min == 1 && max == 10)
                ? LEVEL_TOKEN_PATTERN : GOLD_TOKEN_PATTERN;
        Matcher match = pattern.matcher(cleaned);
        if (!match.matches()) return null;
        try {
            int parsed = Integer.parseInt(match.group(1));
            return (parsed >= min && parsed <= max) ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Bitmap downscale(Bitmap source, int maxSide) {
        int w = source.getWidth();
        int h = source.getHeight();
        int largest = Math.max(w, h);
        if (largest <= maxSide) return source.copy(Bitmap.Config.ARGB_8888, false);
        float scale = (float) maxSide / largest;
        return Bitmap.createScaledBitmap(
                source,
                Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)),
                true
        );
    }

    private static Observation empty(long now) {
        return new Observation(null, null, null, null, now);
    }

    @Override
    public synchronized void close() {
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }
}
