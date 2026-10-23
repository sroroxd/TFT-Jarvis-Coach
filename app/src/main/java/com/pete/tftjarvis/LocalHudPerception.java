package com.pete.tftjarvis;

import android.graphics.Bitmap;
import android.graphics.Rect;

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

    private static final class Candidate {
        final String text;
        final Rect box;

        Candidate(String text, Rect box) {
            this.text = text;
            this.box = box;
        }

        float centerX(int width) {
            return width <= 0 ? 0f : box.exactCenterX() / width;
        }

        float centerY(int height) {
            return height <= 0 ? 0f : box.exactCenterY() / height;
        }

        float heightNorm(int height) {
            return height <= 0 ? 0f : (float) box.height() / height;
        }
    }

    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    Observation observe(Bitmap source, String lastPhase) {
        long now = System.currentTimeMillis();
        if (source == null || source.getWidth() < 320 || source.getHeight() < 180) {
            return empty(now);
        }

        String phase = lastPhase == null ? "UNKLAR" : lastPhase.trim().toUpperCase(Locale.ROOT);
        if ("ERGEBNIS".equals(phase)) {
            return empty(now);
        }

        Bitmap input = downscale(source, 1280);
        try {
            Text text = Tasks.await(
                    recognizer.process(InputImage.fromBitmap(input, 0)),
                    900,
                    TimeUnit.MILLISECONDS
            );
            Observation observation = parse(text, input.getWidth(), input.getHeight(), now);
            // The first in-game frame arrives while lastPhase is still VOR_MATCH.
            // Trust early numeric HUD observations only if a game stage is seen.
            if ("VOR_MATCH".equals(phase) && observation.stage == null) {
                return empty(now);
            }
            return observation;
        } catch (Exception ignored) {
            return empty(now);
        } finally {
            if (input != source && !input.isRecycled()) input.recycle();
        }
    }

    private Observation parse(Text text, int width, int height, long now) {
        List<Candidate> candidates = new ArrayList<>();
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

        Field<String> stage = findStage(candidates, width, height);
        // The level number is inside the small blue badge, NOT the
        // adjacent "ERF kaufen" button whose price frequently shows "4".
        Field<Integer> level = findNumber(candidates, width, height,
                1, 10, HudRegions.OWN_LEVEL);
        Field<Integer> gold = findNumber(candidates, width, height,
                0, 999, HudRegions.OWN_GOLD);
        // Never treat the top-center combat timer as own HP.
        // Own HP needs a future player-identity anchored OCR source.
        Field<Integer> health = null;

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

            String normalized = c.text
                    .replace('–', '-')
                    .replace('—', '-')
                    .replaceAll("\\s+", "");
            if (!normalized.matches("[1-9]-[1-9]")) continue;

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

            Integer value = parseNumericToken(c.text);
            if (value == null || value < min || value > max) continue;

            float distance = Math.abs(x - region.expectedX) + Math.abs(y - region.expectedY);
            float score = c.heightNorm(height) * 120f - distance * 18f;
            if (score > bestScore) {
                best = c;
                bestValue = value;
                bestScore = score;
            }
        }

        // Numeric OCR is intentionally conservative. MatchState promotes it after
        // cloud agreement on the same frame or a second matching local observation.
        return best == null ? null : new Field<>(bestValue, "MITTEL");
    }

    private static Integer parseNumericToken(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim();
        if (cleaned.isEmpty()) return null;

        // Reject words and mixed champion/item labels; tolerate harmless UI symbols.
        if (cleaned.matches(".*[A-Za-zÄÖÜäöüß].*")) return null;
        String digits = cleaned.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 3) return null;

        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Bitmap downscale(Bitmap source, int maxSide) {
        int w = source.getWidth();
        int h = source.getHeight();
        int largest = Math.max(w, h);
        if (largest <= maxSide) return source;
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
    public void close() {
        recognizer.close();
    }
}
