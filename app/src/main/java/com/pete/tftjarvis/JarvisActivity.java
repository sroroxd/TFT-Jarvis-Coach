package com.pete.tftjarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pete.tftjarvis.core.JarvisCoreEngine;
import com.pete.tftjarvis.core.LocalControlCapability;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * JARVIS is a self-contained Android UI. Local commands are always handled offline.
 * The user's separate API account is optional, disabled until explicit consent,
 * and is NEVER automatically triggered by speech or unknown text commands.
 */
public final class JarvisActivity extends Activity implements LocalControlCapability.Controls {
    private static final String PREFS = "jarvis_core_preferences";
    private static final String MUTED = "speech_muted";
    private static final int MAX_CONTEXT_TURNS = 4;

    private EditText commandInput;
    private TextView output;
    private TextView systemStatus;
    private Button muteButton;
    private Button voiceButton;
    private Button aiConfigButton;
    private Button aiSendButton;
    private JarvisSpeechInput voiceInput;
    private String speechState = "BEREIT";
    private JarvisCoreEngine core;
    private TextToSpeech tts;
    private boolean speechMuted;
    private boolean ttsReady;

    private final JarvisSecretStore keyStore = new JarvisSecretStore();
    private final JarvisAiClient aiClient = new JarvisAiClient();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final List<String[]> sessionTurns = new ArrayList<>();
    private JarvisAiClient.CancelToken currentAiToken;
    private Future<?> currentAiTask;
    private long requestGeneration;
    private boolean aiBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jarvis);

        commandInput = findViewById(R.id.jarvisCommand);
        output = findViewById(R.id.jarvisOutput);
        systemStatus = findViewById(R.id.jarvisStatus);
        muteButton = findViewById(R.id.jarvisMuteButton);
        voiceButton = findViewById(R.id.jarvisVoiceButton);
        aiConfigButton = findViewById(R.id.jarvisAiConfigButton);
        aiSendButton = findViewById(R.id.jarvisAiSendButton);

        speechMuted = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(MUTED, false);
        core = new JarvisCoreEngine();
        core.register(new LocalControlCapability(this));

        voiceInput = new JarvisSpeechInput(this, new JarvisSpeechInput.Callbacks() {
            @Override public void onSpeechState(String state) {
                speechState = state;
                updateStatus();
            }

            @Override public void onRecognizedText(String text) {
                commandInput.setText(text);
                runCommand(text);
                // Open questions remain in the text field. No automatic cloud call.
            }

            @Override public void onSpeechError(String description) {
                output.setText(description);
                updateStatus();
            }
        });
        voiceButton.setOnClickListener(v -> {
            if (tts != null) tts.stop();
            voiceInput.startOrCancel();
        });

        findViewById(R.id.jarvisExecuteButton).setOnClickListener(v -> {
            runCommand(commandInput.getText().toString());
        });
        findViewById(R.id.jarvisHelpButton).setOnClickListener(v -> runCommand("Hilfe"));
        findViewById(R.id.jarvisStatusButton).setOnClickListener(v -> runCommand("Status"));
        muteButton.setOnClickListener(v -> runCommand(speechMuted ? "Laut" : "Stumm"));
        aiConfigButton.setOnClickListener(v -> openAiSetup());
        aiSendButton.setOnClickListener(v -> askAi());
        findViewById(R.id.jarvisClearSessionButton).setOnClickListener(v -> {
            cancelAiRequest();
            sessionTurns.clear();
            output.setText("KI-Gesprächskontext im Arbeitsspeicher gelöscht.");
        });
        commandInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                runCommand(commandInput.getText().toString());
                return true;
            }
            return false;
        });

        updateStatus();
        tts = new TextToSpeech(this, state -> runOnUiThread(() -> {
            if (isDestroyed() || tts == null) return;
            if (state == TextToSpeech.SUCCESS) {
                int language = tts.setLanguage(Locale.GERMAN);
                ttsReady = language != TextToSpeech.LANG_MISSING_DATA
                        && language != TextToSpeech.LANG_NOT_SUPPORTED;
            } else {
                ttsReady = false;
            }
            updateStatus();
        }));
        output.setText("JARVIS lokal bereit. Lokale Befehle funktionieren ohne KI. "
                + "Für eine separate, möglicherweise kostenpflichtige KI: KI EINRICHTEN.");
    }

    private void runCommand(String command) {
        JarvisCoreEngine.Result result = core.handle(command);
        output.setText(result.message);
        updateStatus();
        String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        if (result.outcome == JarvisCoreEngine.Outcome.DONE
                && !normalized.equals("abbrechen")
                && !normalized.equals("jarvis abbrechen")) {
            speak(result.message);
        }
    }

    /** Only the dedicated KI FRAGEN button can transmit text to the API. */
    private void askAi() {
        String question = commandInput.getText().toString().trim();
        if (question.isEmpty() || question.length() > 4000) {
            output.setText("Bitte eine Frage mit höchstens 4000 Zeichen eingeben.");
            return;
        }
        // Keep local controls offline even when the AI button was used.
        JarvisCoreEngine.Result local = core.handle(question);
        if (local.outcome != JarvisCoreEngine.Outcome.UNSUPPORTED) {
            runCommand(question);
            return;
        }
        if (aiBusy) {
            output.setText("Eine KI-Anfrage läuft bereits. Abbrechen oder Ergebnis abwarten.");
            return;
        }
        if (!keyStore.isConfigured(this)) {
            output.setText("KI ist ausgeschaltet. Erst KI EINRICHTEN und Kostenfreigabe bestätigen.");
            return;
        }
        final String apiKey;
        try {
            apiKey = keyStore.load(this);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            output.setText("API-Schlüssel kann nicht entschlüsselt werden. KI neu einrichten.");
            return;
        }
        if (apiKey == null) {
            output.setText("KI-Zugang nicht eingerichtet.");
            return;
        }
        final JarvisAiClient.CancelToken token = new JarvisAiClient.CancelToken();
        final long generation = ++requestGeneration;
        final List<String[]> context = new ArrayList<>(sessionTurns);
        currentAiToken = token;
        aiBusy = true;
        output.setText("Eigene KI-Anfrage wird gesendet. Text und kurzer Gesprächskontext gehen an OpenAI.");
        updateStatus();
        currentAiTask = aiExecutor.submit(() -> {
            String answer = null;
            String error = null;
            try {
                answer = aiClient.answer(apiKey, question, context, token);
            } catch (IOException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                error = "KI-Anfrage fehlgeschlagen.";
            }
            final String reply = answer;
            final String problem = error;
            runOnUiThread(() -> {
                if (isDestroyed() || requestGeneration != generation || token.isCanceled()) return;
                currentAiToken = null;
                currentAiTask = null;
                aiBusy = false;
                updateStatus();
                if (reply != null) {
                    sessionTurns.add(new String[] { question, reply });
                    while (sessionTurns.size() > MAX_CONTEXT_TURNS) sessionTurns.remove(0);
                    output.setText(reply);
                    speak(reply);
                } else {
                    output.setText(problem == null ? "Keine KI-Antwort empfangen." : problem);
                }
            });
        });
    }

    private void openAiSetup() {
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int margin = (int) (getResources().getDisplayMetrics().density * 20);
        layout.setPadding(margin, margin / 2, margin, margin / 2);

        TextView explanation = new TextView(this);
        explanation.setText("Optionaler persönlicher OpenAI-API-Zugang. "
                + "API-Nutzung kann unabhängig von ChatGPT Plus Geld kosten. "
                + "JARVIS setzt derzeit KEIN hartes Kostenlimit: Limits im OpenAI-Platform-Konto festlegen. "
                + "Nur ein Tipp auf KI FRAGEN überträgt die Eingabe und bis zu vier Gesprächsrunden an OpenAI. "
                + "Keine automatische Verbindung zu diesem Chat oder dessen Erinnerung. "
                + "Ein lokaler Geräteschlüssel ist kein Ersatz für serverseitige Schlüsselverwaltung.");
        layout.addView(explanation);

        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Persönlicher API-Schlüssel");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImportantForAutofill(EditText.IMPORTANT_FOR_AUTOFILL_NO);
        layout.addView(field);

        CheckBox agree = new CheckBox(this);
        agree.setText("Ich aktiviere API-Nutzung mit möglichen zusätzlichen Kosten und Datenübertragung.");
        agree.setChecked(false);
        layout.addView(agree);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(keyStore.isConfigured(this) ? "JARVIS KI neu einrichten" : "JARVIS KI einrichten")
                .setView(layout)
                .setNegativeButton("Abbrechen", (dialog, which) -> { })
                .setPositiveButton("KOSTENPFLICHTIGE KI AKTIVIEREN", null);
        if (keyStore.isConfigured(this)) {
            builder.setNeutralButton("KI DEAKTIVIEREN UND SCHLÜSSEL LÖSCHEN",
                    (dialog, which) -> {
                        cancelAiRequest();
                        sessionTurns.clear();
                        keyStore.disableAndClear(this);
                        output.setText("Eigener API-Zugang deaktiviert und verschlüsselter Schlüssel entfernt.");
                        updateStatus();
                    });
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(unused -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (!agree.isChecked()) {
                        output.setText("Keine Freigabe erteilt. KI bleibt unverändert.");
                        return;
                    }
                    try {
                        keyStore.saveAndEnable(this, field.getText().toString());
                        sessionTurns.clear();
                        output.setText("Separate KI aktiviert. Erst KI FRAGEN sendet deine Frage; "
                                + "die Verbindung ist noch nicht auf deinem S24 getestet.");
                        updateStatus();
                        dialog.dismiss();
                    } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
                        output.setText("Schlüssel konnte nicht sicher gespeichert werden.");
                    }
                }));
        dialog.show();
    }

    private void cancelAiRequest() {
        requestGeneration++;
        if (currentAiToken != null) currentAiToken.cancel();
        if (currentAiTask != null) currentAiTask.cancel(true);
        currentAiToken = null;
        currentAiTask = null;
        aiBusy = false;
        updateStatus();
    }

    private void speak(String text) {
        if (!speechMuted && ttsReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response");
        }
    }

    private void updateStatus() {
        if (systemStatus == null || muteButton == null) return;
        String aiMode = aiBusy ? "KI ANFRAGE LÄUFT"
                : (keyStore.isConfigured(this) ? "KI OPT-IN" : "KI AUS");
        systemStatus.setText("LOKAL BEREIT · " + aiMode + " · "
                + (voiceInput != null && voiceInput.isListening()
                    ? speechState
                    : (speechMuted ? "STUMM" : (ttsReady ? "SPRACHE AKTIV" : "SPRACHE WIRD GELADEN"))));
        muteButton.setText(speechMuted ? "LAUT" : "STUMM");
        if (voiceButton != null) {
            voiceButton.setText(voiceInput != null && voiceInput.isListening()
                    ? "ZUHÖREN ABBRECHEN" : "SPRECHEN");
        }
        if (aiConfigButton != null) {
            aiConfigButton.setText(keyStore.isConfigured(this) ? "KI VERWALTEN" : "KI EINRICHTEN");
        }
        if (aiSendButton != null) {
            aiSendButton.setEnabled(!aiBusy);
        }
    }

    @Override public boolean isMuted() { return speechMuted; }

    @Override
    public void setMuted(boolean muted) {
        speechMuted = muted;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(MUTED, muted).apply();
        if (muted && tts != null) tts.stop();
        updateStatus();
    }

    @Override
    public void cancelCurrentTask() {
        if (tts != null) tts.stop();
        if (voiceInput != null) voiceInput.cancel();
        cancelAiRequest();
    }

    @Override
    public String currentStatus() {
        return "JARVIS ist lokal bereit. Sprachausgabe "
                + (speechMuted ? "stumm. " : (ttsReady ? "aktiv. " : "nicht verfügbar. "))
                + (keyStore.isConfigured(this)
                    ? "Eigene kostenpflichtige KI-Verbindung aktiviert. "
                    : "KI nicht eingerichtet. ")
                + "Kein laufendes ChatGPT oder TFT erforderlich.";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (voiceInput != null) voiceInput.onPermissionResult(requestCode, grantResults);
    }

    @Override
    protected void onStop() {
        if (voiceInput != null && voiceInput.isListening()) voiceInput.cancel();
        cancelAiRequest(); // No background API actions in this preview.
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelCurrentTask();
        aiExecutor.shutdownNow();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
