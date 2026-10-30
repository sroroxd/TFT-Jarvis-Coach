package com.pete.tftjarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
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
    private static final String HANDSFREE_PREF = "foreground_handsfree_enabled";
    // New opt-in key: old wakeword-gated authorization MUST NOT carry over to wakewordless mode.
    private static final String VOICE_AI_PREF = "voice_ai_explicit_consent_no_wake_v2";
    private static final long RESTART_DELAY_MS = 850L;

    private final Handler voiceHandler = new Handler(Looper.getMainLooper());
    private boolean handsFreeEnabled;
    private boolean voiceAiConsent;
    private boolean appVisible;
    private boolean speechInProgress;
    private boolean voiceWaitingForReply;
    private int speechErrorStreak;
    private int autoAiRequests;
    private long lastVoiceAiRequestMs;
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
    private final JarvisGeminiClient geminiClient = new JarvisGeminiClient();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final List<String[]> sessionTurns = new ArrayList<>();
    private JarvisAiClient.CancelToken currentAiToken;
    private Future<?> currentAiTask;
    private long requestGeneration;
    private boolean aiBusy;

    private final Runnable voiceRestart = () -> {
        if (handsFreeEnabled && appVisible && !voiceWaitingForReply
                && !speechInProgress && !aiBusy && voiceInput != null
                && !voiceInput.isListening()) {
            voiceInput.startOrCancel();
        }
    };


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
        handsFreeEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(HANDSFREE_PREF, false);
        voiceAiConsent = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(VOICE_AI_PREF, false);
        core = new JarvisCoreEngine();
        core.register(new LocalControlCapability(this));

        voiceInput = new JarvisSpeechInput(this, new JarvisSpeechInput.Callbacks() {
            @Override public void onSpeechState(String state) {
                speechState = state;
                updateStatus();
            }

            @Override public void onRecognizedText(String text) {
                speechErrorStreak = 0;
                commandInput.setText(text);
                if (handsFreeEnabled) {
                    handleHandsFreeUtterance(text);
                } else {
                    runCommand(text);
                }
            }

            @Override public void onSpeechError(String description) {
                if (handsFreeEnabled) {
                    // Android may end a silence-only session without a transcript.
                    // Recover with a delay. Never spin on a missing microphone/engine.
                    if (description.contains("nicht verfügbar")
                            || description.contains("Berechtigung")
                            || description.contains("nicht erlaubt")) {
                        stopHandsFree();
                        output.setText(description);
                    } else if (description.startsWith("Keine Sprache erkannt")) {
                        // Silence is normal, not a fault; keep the foreground loop alive.
                        speechErrorStreak = 0;
                        queueNextListen(850L);
                    } else if (++speechErrorStreak > 5) {
                        stopHandsFree();
                        output.setText("Spracherkennung mehrfach unterbrochen. Freisprechen erneut aktivieren.");
                    } else {
                        queueNextListen(1200L + 650L * speechErrorStreak);
                    }
                } else {
                    output.setText(description);
                }
                updateStatus();
            }
        });
        voiceButton.setOnClickListener(v -> {
            if (handsFreeEnabled) {
                stopHandsFree();
                output.setText("Freisprechen ausgeschaltet.");
            } else {
                showHandsFreeConsent();
            }
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
        if (tts != null) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) { voiceHandler.post(() -> finishSpeaking()); }
                @Override public void onError(String utteranceId) { voiceHandler.post(() -> finishSpeaking()); }
            });
        }
        output.setText("Bereit. Was kann ich für dich tun?");
    }

    private void showHandsFreeConsent() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 18);
        panel.setPadding(pad, pad, pad, pad);
        TextView explanation = new TextView(this);
        explanation.setText("JARVIS hört nach Freigabe zu, solange diese App sichtbar geöffnet ist. "
                + "Du sprichst frei und ohne Ansprechwort; JARVIS antwortet und hört danach erneut zu. "
                + "Beim Verlassen der App stoppt das Mikrofon. "
                + "Dafür wird die lokale Android-Spracherkennung benötigt; dauerhafte Erkennung "
                + "kann je nach Gerät und Android-Sprachdienst unterbrochen werden.");
        panel.addView(explanation);

        CheckBox aiPermission = new CheckBox(this);
        aiPermission.setText("Gesprochene Fragen und Gesprächskontext ohne Aktivierungswort an "
                + (JarvisSecretStore.GEMINI.equals(keyStore.provider(this)) ? "Google Gemini" : "OpenAI")
                + " senden (Kontingent kann kostenlos sein, Kosten sind nicht ausgeschlossen; "
                + "maximal 12 automatische Anfragen pro App-Sitzung).");
        aiPermission.setEnabled(keyStore.isConfigured(this));
        aiPermission.setChecked(false);
        panel.addView(aiPermission);
        if (!keyStore.isConfigured(this)) {
            TextView info = new TextView(this);
            info.setText("KI ist aus. Lokale Sprachbefehle funktionieren ohne API. "
                    + "Für freie Fragen zuerst KI EINRICHTEN.");
            panel.addView(info);
        }

        new AlertDialog.Builder(this)
                .setTitle("Freisprechen aktivieren")
                .setView(panel)
                .setNegativeButton("Abbrechen", (dialog, which) -> { })
                .setPositiveButton("FREISPRECHEN AKTIVIEREN", (dialog, which) -> {
                    handsFreeEnabled = true;
                    voiceAiConsent = aiPermission.isChecked() && keyStore.isConfigured(this);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean(HANDSFREE_PREF, true)
                            .putBoolean(VOICE_AI_PREF, voiceAiConsent)
                            .apply();
                    voiceWaitingForReply = false;
                    speechErrorStreak = 0;
                    if (tts != null) tts.stop();
                    speechInProgress = false;
                    queueNextListen(300L);
                    updateStatus();
                }).show();
    }

    private void stopHandsFree() {
        handsFreeEnabled = false;
        voiceAiConsent = false;
        voiceWaitingForReply = false;
        voiceHandler.removeCallbacks(voiceRestart);
        if (voiceInput != null) voiceInput.cancel();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(HANDSFREE_PREF, false)
                .putBoolean(VOICE_AI_PREF, false)
                .apply();
        updateStatus();
    }

    private void queueNextListen(long delayMs) {
        voiceHandler.removeCallbacks(voiceRestart);
        if (handsFreeEnabled && appVisible && !voiceWaitingForReply
                && !speechInProgress && !aiBusy) {
            voiceHandler.postDelayed(voiceRestart, Math.max(250L, delayMs));
        }
    }

    private void finishSpeaking() {
        speechInProgress = false;
        voiceWaitingForReply = false;
        queueNextListen(RESTART_DELAY_MS);
        updateStatus();
    }

    private void handleHandsFreeUtterance(String recognized) {
        String request = JarvisWakePhrase.freeSpeech(recognized);
        voiceWaitingForReply = true;
        if (request.isEmpty()) {
            voiceWaitingForReply = false;
            queueNextListen(RESTART_DELAY_MS);
            return;
        }
        JarvisCoreEngine.Result local = core.handle(request);
        if (local.outcome != JarvisCoreEngine.Outcome.UNSUPPORTED) {
            runCommand(request);
            return;
        }
        if (!voiceAiConsent || !keyStore.isConfigured(this)) {
            output.setText("Für freie Sprachfragen muss die KI eingerichtet "
                    + "und beim Freisprechen ausdrücklich freigegeben sein.");
            speak("Für freie Fragen aktiviere bitte die KI im Freisprechmodus.");
            return;
        }
        long now = System.currentTimeMillis();
        if (!JarvisWakePhrase.autoAiAllowed(voiceAiConsent, keyStore.isConfigured(this),
                autoAiRequests, now, lastVoiceAiRequestMs)) {
            output.setText(autoAiRequests >= 12
                    ? "Sicherheitsgrenze: maximal 12 automatische KI-Fragen pro App-Sitzung erreicht."
                    : "Bitte einen Moment bis zur nächsten KI-Frage warten.");
            speak("Bitte einen Moment warten.");
            return;
        }
        autoAiRequests++;
        lastVoiceAiRequestMs = now;
        askAi(request);
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
        } else if (handsFreeEnabled) {
            voiceWaitingForReply = false;
            queueNextListen(RESTART_DELAY_MS);
        }
    }

    /** Manual KI FRAGEN, or explicitly opt-in foreground conversational speech. */
    private void askAi() {
        askAi(commandInput.getText().toString().trim());
    }

    private void askAi(String question) {
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
        final String provider = keyStore.provider(this);
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
        output.setText("KI-Anfrage läuft. Frage und kurzer Gesprächskontext gehen an "
                + (JarvisSecretStore.GEMINI.equals(provider) ? "Google Gemini." : "OpenAI."));
        updateStatus();
        currentAiTask = aiExecutor.submit(() -> {
            String answer = null;
            String error = null;
            try {
                answer = JarvisSecretStore.GEMINI.equals(provider)
                        ? geminiClient.answer(apiKey, question, context, token)
                        : aiClient.answer(apiKey, question, context, token);
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
                    String message = problem == null ? "Keine KI-Antwort empfangen." : problem;
                    output.setText(message);
                    if (voiceWaitingForReply) speak(message);
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
        explanation.setText("Wähle Gemini oder OpenAI und verwende deinen eigenen API-Schlüssel. "
                + "Für Gemini kann ein begrenztes kostenloses Kontingent verfügbar sein. "
                + "Das garantiert NICHT, dass deinem Google-Konto keine Kosten entstehen: "
                + "prüfe Abrechnung und Kontingente selbst. Es gibt keine automatische Umschaltung "
                + "auf einen kostenpflichtigen Anbieter. "
                + "JARVIS besitzt kein hartes Euro-Kostenlimit. "
                + "KI FRAGEN sendet die Eingabe; freie Sprache wird erst nach weiterer "
                + "Einwilligung gesendet. Keine Verbindung zu deinem ChatGPT-Konto.");
        layout.addView(explanation);

        Spinner providerPicker = new Spinner(this);
        ArrayAdapter<String> providerOptions = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[] {
                "Google Gemini (Gratis-Kontingent möglich)", "OpenAI (mögliche API-Kosten)"});
        providerOptions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerPicker.setAdapter(providerOptions);
        providerPicker.setSelection(keyStore.isConfigured(this)
                && JarvisSecretStore.OPENAI.equals(keyStore.provider(this)) ? 1 : 0);
        layout.addView(providerPicker);

        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Persönlicher API-Schlüssel");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImportantForAutofill(EditText.IMPORTANT_FOR_AUTOFILL_NO);
        layout.addView(field);

        CheckBox agree = new CheckBox(this);
        agree.setText("Ich erlaube Datenübertragung an den gewählten Anbieter und weiß, "
                + "dass ohne geprüftes kostenloses Kontingent Kosten möglich sind.");
        agree.setChecked(false);
        layout.addView(agree);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(keyStore.isConfigured(this) ? "JARVIS KI neu einrichten" : "JARVIS KI einrichten")
                .setView(layout)
                .setNegativeButton("Abbrechen", (dialog, which) -> { })
                .setPositiveButton("KI-ZUGANG SPEICHERN", null);
        if (keyStore.isConfigured(this)) {
            builder.setNeutralButton("KI DEAKTIVIEREN UND SCHLÜSSEL LÖSCHEN",
                    (dialog, which) -> {
                        cancelAiRequest();
                        sessionTurns.clear();
                        keyStore.disableAndClear(this);
                        voiceAiConsent = false;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putBoolean(VOICE_AI_PREF, false).apply();
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
                        String selectedProvider = providerPicker.getSelectedItemPosition() == 0
                                ? JarvisSecretStore.GEMINI : JarvisSecretStore.OPENAI;
                        keyStore.saveAndEnable(this, field.getText().toString(), selectedProvider);
                        voiceAiConsent = false;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putBoolean(VOICE_AI_PREF, false).apply();
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
        if (handsFreeEnabled) {
            voiceHandler.removeCallbacks(voiceRestart);
            if (voiceInput != null && voiceInput.isListening()) voiceInput.cancel();
            voiceWaitingForReply = true;
        }
        if (!speechMuted && ttsReady && tts != null) {
            speechInProgress = true;
            int status = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-response");
            if (status == TextToSpeech.ERROR) finishSpeaking();
        } else {
            finishSpeaking();
        }
    }

    private void updateStatus() {
        if (systemStatus == null || muteButton == null) return;
        String aiMode = aiBusy ? "KI ANFRAGE LÄUFT"
                : (keyStore.isConfigured(this)
                        ? (JarvisSecretStore.GEMINI.equals(keyStore.provider(this))
                            ? "GEMINI EINGERICHTET" : "OPENAI EINGERICHTET")
                        : "KI AUS");
        String voiceMode = handsFreeEnabled
                ? (voiceInput != null && voiceInput.isListening() ? "MIKROFON AN"
                    : (speechInProgress ? "JARVIS SPRICHT" : "FREISPRECHEN BEREIT"))
                : (speechMuted ? "STUMM" : (ttsReady ? "SPRACHE AKTIV" : "SPRACHE WIRD GELADEN"));
        systemStatus.setText("LOKAL BEREIT · " + aiMode + " · " + voiceMode);
        muteButton.setText(speechMuted ? "LAUT" : "STUMM");
        if (voiceButton != null) {
            voiceButton.setText(handsFreeEnabled ? "FREISPRECHEN AUS" : "FREISPRECHEN AN");
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
        return "JARVIS ist lokal bereit. Freisprechen " + (handsFreeEnabled ? "ein. " : "aus. ")
                + "Sprachausgabe "
                + (speechMuted ? "stumm. " : (ttsReady ? "aktiv. " : "nicht verfügbar. "))
                + (keyStore.isConfigured(this)
                    ? "KI-Dienst eingerichtet; mögliche Kosten hängen vom gewählten Anbieter ab. "
                    : "KI nicht eingerichtet. ")
                + "Kein laufendes ChatGPT oder TFT erforderlich.";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (voiceInput != null) voiceInput.onPermissionResult(requestCode, grantResults, appVisible && handsFreeEnabled);
    }

    @Override
    protected void onResume() {
        super.onResume();
        appVisible = true;
        if (handsFreeEnabled) queueNextListen(500L);
        updateStatus();
    }

    @Override
    protected void onStop() {
        appVisible = false;
        voiceHandler.removeCallbacks(voiceRestart);
        if (voiceInput != null) voiceInput.cancel();
        if (tts != null) tts.stop();
        speechInProgress = false;
        cancelAiRequest(); // No microphone or cloud jobs while backgrounded.
        voiceWaitingForReply = false;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        appVisible = false;
        voiceHandler.removeCallbacks(voiceRestart);
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
