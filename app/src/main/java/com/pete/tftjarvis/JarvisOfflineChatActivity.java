package com.pete.tftjarvis;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explicit offline-only text-inference test screen.
 * No network access, API key, microphone, background inference or automatic model download.
 * The GGUF is selected by the user via Android document picker.
 */
public final class JarvisOfflineChatActivity extends Activity {
    private static final int PICK_MODEL = 7001;
    // Official Qwen Q4_K_M artifact, Apache-2.0, ~491 MB; verified metadata 2026-10-31.
    private static final String VERIFIED_MODEL_SHA256 =
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String[]> turns = new ArrayList<>();
    private final File[] selected = new File[1];
    private TextView status;
    private TextView transcript;
    private EditText input;
    private Button ask;
    private boolean busy;
    private volatile boolean canceled;
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("JARVIS · OFFLINE-KI LABOR");
        title.setTextSize(24);
        root.addView(title);
        status = new TextView(this);
        status.setText("Nur lokale Inferenz. Keine Modell-Datei geladen.");
        root.addView(status);
        transcript = new TextView(this);
        transcript.setText("Noch kein Gespräch. Importiere ausschließlich die offizielle Qwen2.5-0.5B-Instruct Q4_K_M GGUF (~491 MB, Apache-2.0). SHA-256 wird vor dem Laden geprüft.");
        transcript.setTextIsSelectable(true);
        root.addView(transcript);
        input = new EditText(this);
        input.setHint("Deutsche Frage oder Folgefrage");
        input.setMinLines(2);
        root.addView(input);
        Button pick = new Button(this);
        pick.setText("GGUF-DATEI LOKAL IMPORTIEREN");
        root.addView(pick);
        pick.setOnClickListener(v -> {
            if (busy) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("application/octet-stream");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, PICK_MODEL);
        });
        ask = new Button(this);
        ask.setText("OFFLINE ANTWORT GENERIEREN");
        ask.setEnabled(false);
        root.addView(ask);
        ask.setOnClickListener(v -> generate());
        Button stop = new Button(this);
        stop.setText("STOPP");
        root.addView(stop);
        stop.setOnClickListener(v -> {
            canceled = true;
            JarvisOfflineEngine.interrupt();
            status.setText("Abbruch angefordert.");
        });
        Button reset = new Button(this);
        reset.setText("GESPRÄCH LÖSCHEN");
        root.addView(reset);
        reset.setOnClickListener(v -> {
            if (busy) { canceled = true; JarvisOfflineEngine.interrupt(); }
            turns.clear();
            transcript.setText("Gesprächskontext gelöscht.");
        });
        status.setText("Offline-Labor: Keine Cloud, keine API-Kosten. GGUF-Import kann mehrere Minuten dauern.");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK
                || data == null || data.getData() == null || busy) return;
        final Uri uri = data.getData();
        final File directory = new File(getFilesDir(), "gguf");
        busy = true;
        canceled = false;
        ask.setEnabled(false);
        status.setText("Lokales Modell wird importiert und geprüft …");
        final int request = ++generation;
        worker.execute(() -> {
            // Release the previous native model before a new potentially large import.
            JarvisOfflineEngine.unload();
            String message;
            File imported = null;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                JarvisGgufModelStore.ImportedModel result =
                        JarvisGgufModelStore.importVerified(
                                stream, directory, VERIFIED_MODEL_SHA256, () -> canceled);
                imported = result.file;
                message = "Import: " + result.bytes + " Bytes, SHA-256 " + result.sha256
                        + ". Native Modellprüfung läuft …";
            } catch (Exception e) {
                message = "Import fehlgeschlagen: " + e.getClass().getSimpleName();
            }
            final File candidate = imported;
            final String importMessage = message;
            if (candidate != null && !canceled) {
                runOnUiThread(() -> status.setText(importMessage));
            }
            boolean loaded = false;
            if (candidate != null && !canceled) {
                try { loaded = JarvisOfflineEngine.load(candidate); }
                catch (RuntimeException | LinkageError ignored) { }
            }
            // Cancellation can happen during native loading as well as file import.
            if (loaded && canceled) JarvisOfflineEngine.unload();
            final boolean ready = loaded && !canceled;
            runOnUiThread(() -> {
                if (isDestroyed() || request != generation) return;
                selected[0] = ready ? candidate : null;
                turns.clear();
                busy = false;
                ask.setEnabled(ready);
                status.setText(ready
                        ? "LLM lokal geladen. Flugmodus-Test: Frage stellen und fünfmal nachfragen."
                        : canceled
                            ? "Modellimport abgebrochen. Vorheriges Modell bleibt auf dem Gerät erhalten."
                            : candidate == null
                                ? importMessage
                                : "Modell nicht ladbar. Architektur, Quantisierung und RAM prüfen.");
            });
        });
    }

    private void generate() {
        if (busy || selected[0] == null) return;
        String question = input.getText().toString().trim();
        if (question.isEmpty() || question.length() > 1000) {
            status.setText("Bitte eine Frage bis 1000 Zeichen eingeben.");
            return;
        }
        String prompt = JarvisOfflinePrompt.build(turns, question);
        busy = true;
        canceled = false;
        ask.setEnabled(false);
        status.setText("LLM rechnet vollständig offline …");
        final int request = ++generation;
        worker.execute(() -> {
            String reply = "";
            try { reply = JarvisOfflineEngine.generate(prompt, 160); }
            catch (RuntimeException | LinkageError ignored) { }
            final String answer = reply;
            runOnUiThread(() -> {
                if (isDestroyed() || request != generation) return;
                busy = false;
                ask.setEnabled(selected[0] != null);
                if (canceled) {
                    status.setText("Generierung abgebrochen.");
                } else if (answer.trim().isEmpty()) {
                    status.setText("Keine Token erzeugt. Modell/Prompt nicht kompatibel oder Kontextlimit erreicht.");
                } else {
                    turns.add(new String[]{question, answer});
                    while (turns.size() > 5) turns.remove(0);
                    transcript.append("\n\nDu: " + question + "\nJARVIS: " + answer);
                    input.setText("");
                    status.setText("Offline-Antwort " + turns.size() + " / mindestens 6 Testfragen.");
                }
            });
        });
    }

    @Override protected void onStop() {
        canceled = true;
        JarvisOfflineEngine.interrupt();
        super.onStop();
    }

    @Override protected void onDestroy() {
        canceled = true;
        JarvisOfflineEngine.interrupt();
        worker.execute(JarvisOfflineEngine::unload);
        worker.shutdown();
        super.onDestroy();
    }
}
