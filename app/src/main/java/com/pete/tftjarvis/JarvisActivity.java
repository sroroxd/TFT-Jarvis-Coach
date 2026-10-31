package com.pete.tftjarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.view.View;
import android.widget.Spinner;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.jarvis.core.JarvisCoreEngine;
import dev.jarvis.core.LocalControlCapability;

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
 * and voice-to-cloud use requires a separate, explicit opt-in.
 */
public final class JarvisActivity extends Activity implements LocalControlCapability.Controls {
    private static final String PREFS = "jarvis_core_preferences";
    private static final String MUTED = "speech_muted";
    private static final String VOICE_QUOTA_UNTIL = "speech_quota_until_wall_ms";
    private static final int MAX_CONTEXT_TURNS = 4;
    private static final String HANDSFREE_PREF = "foreground_handsfree_enabled";
    // New opt-in key: old wakeword-gated authorization MUST NOT carry over to wakewordless mode.
    private static final String VOICE_AI_PREF = "voice_ai_explicit_consent_no_wake_v2";
    private static final long RESTART_DELAY_MS = 850L;

    private final Handler voiceHandler = new Handler(Looper.getMainLooper());
    private final JarvisSpeechStartGuard speechStartGuard = new JarvisSpeechStartGuard();
    private boolean handsFreeEnabled;
    private boolean voiceAiConsent;
    private boolean appVisible;
    private boolean speechInProgress;
    private boolean bargeInCapture;
    private boolean bargeInDetected;
    private String activeSpeechText = "";
    private String activeUtteranceId;
    private long utteranceSequence;
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
    private final JarvisHuggingFaceClient huggingFaceClient = new JarvisHuggingFaceClient();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
    private final List<String[]> sessionTurns = new ArrayList<>();
    private JarvisAiClient.CancelToken currentAiToken;
    private Future<?> currentAiTask;
    private long requestGeneration;
    private boolean aiBusy;

    private final Runnable voiceRestart = () -> {
        if (canListenNow() && voiceInput != null && !voiceInput.isListening()) {
            long now = SystemClock.elapsedRealtime();
            long waitingMs = speechStartGuard.waitBeforeStart(now);
            if (waitingMs > 0L) {
                queueNextListen(waitingMs);
                return;
            }
            // Android SpeechRecognizer is a one-shot service. Throttle all
            // starts including user reactivation to prevent runaway restart loops.
            speechStartGuard.recordStart(now);
            // Provisional barge-in only; no tested acoustic echo cancellation.
            bargeInCapture = speechInProgress;
            bargeInDetected = false;
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
        long blockedUntilWall = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(VOICE_QUOTA_UNTIL, 0L);
        long remainingQuotaMs = Math.max(0L, Math.min(
                JarvisSpeechStartGuard.QUOTA_COOLDOWN_MS,
                blockedUntilWall - System.currentTimeMillis()));
        speechStartGuard.blockFor(SystemClock.elapsedRealtime(), remainingQuotaMs);
        core = new JarvisCoreEngine();
        core.register(new LocalControlCapability(this));

        voiceInput = new JarvisSpeechInput(this, new JarvisSpeechInput.Callbacks() {
            @Override public void onSpeechState(String state) {
                speechState = state;
                updateStatus();
            }

            @Override public void onRecognizedText(String text) {
                boolean capturedDuringTts = bargeInCapture;
                boolean interruptedFromPartial = bargeInDetected;
                bargeInCapture = false;
                bargeInDetected = false;
                // Some Android recognizers provide no partial results at all.
                // Accept a final-only interruption only when markedly different
                // from TTS. Never forward a plausible TTS echo to the cloud.
                boolean echo = JarvisBargeInGate.isLikelyEcho(text, activeSpeechText);
                boolean clearFinalInterruption = capturedDuringTts && !interruptedFromPartial
                        && JarvisBargeInGate.isStrongFinalInterruption(text, activeSpeechText);
                if (capturedDuringTts
                        && (echo || (!interruptedFromPartial && !clearFinalInterruption))) {
                    // The speech service returned a transcript, so do not retain
                    // stale failure counts merely because this was TTS echo.
                    speechErrorStreak = 0;
                    queueNextListen(1200L);
                    return;
                }
                // A final-only interruption must stop the old answer before
                // the next turn is handled. Partial-based interruption has
                // already stopped it in onPartialText.
                if (clearFinalInterruption && speechInProgress) stopSpeechForBargeIn();
                speechErrorStreak = 0;
                commandInput.setText(text);
                if (handsFreeEnabled) {
                    handleHandsFreeUtterance(text);
                } else {
                    runCommand(text);
                }
            }

            @Override public void onPartialText(String text) {
                if (handsFreeEnabled && bargeInCapture && speechInProgress
                        && JarvisBargeInGate.mightBeInterruption(text, activeSpeechText)) {
                    bargeInDetected = true;
                    stopSpeechForBargeIn();
                }
            }

            @Override public void onSpeechError(int errorCode, String description) {
                bargeInCapture = false;
                bargeInDetected = false;
                final String diagnostic = JarvisSpeechRecovery.diagnose(errorCode)
                        + " (Android-Code " + errorCode + ").";
                if (handsFreeEnabled) {
                    if (JarvisSpeechRecovery.mustStop(errorCode)) {
                        if (errorCode == JarvisSpeechRecovery.TOO_MANY_REQUESTS) {
                            speechStartGuard.quotaExceeded(SystemClock.elapsedRealtime());
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putLong(VOICE_QUOTA_UNTIL,
                                            System.currentTimeMillis()
                                            + JarvisSpeechStartGuard.QUOTA_COOLDOWN_MS)
                                    .apply();
                        }
                        stopHandsFree();
                        output.setText("Freisprechen gestoppt: " + diagnostic + " "
                                + JarvisSpeechRecovery.actionableHint(errorCode));
                    } else if (JarvisSpeechRecovery.isSilence(errorCode)) {
                        // A normal pause is not a failure or a reason to disable listening.
                        speechErrorStreak = 0;
                        queueNextListen(JarvisSpeechRecovery.delayMs(errorCode, 0));
                    } else if (++speechErrorStreak >= 4) {
                        stopHandsFree();
                        output.setText("Freisprechen nach mehreren Fehlern pausiert: "
                                + diagnostic + " " + JarvisSpeechRecovery.actionableHint(errorCode));
                    } else {
                        output.setText("Spracherkennung wird erneut versucht: " + diagnostic);
                        queueNextListen(JarvisSpeechRecovery.delayMs(
                                errorCode, speechErrorStreak));
                    }
                } else {
                    output.setText(description + " (Android-Code " + errorCode + ").");
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
                @Override public void onDone(String utteranceId) {
                    voiceHandler.post(() -> finishSpeaking(utteranceId));
                }
                @Override public void onError(String utteranceId) {
                    voiceHandler.post(() -> finishSpeaking(utteranceId));
                }
            });
        }
        output.setText("Bereit. Was kann ich für dich tun?");
    }

    private static String providerLabel(String provider) {
        if (JarvisSecretStore.GEMINI.equals(provider)) return "Google Gemini";
        if (JarvisSecretStore.HUGGING_FACE.equals(provider)) return "Hugging Face";
        return "OpenAI";
    }

    private void showHandsFreeConsent() {
        long quotaUntil = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong(VOICE_QUOTA_UNTIL, 0L);
        long blockedMs = Math.max(0L, Math.min(
                JarvisSpeechStartGuard.QUOTA_COOLDOWN_MS,
                quotaUntil - System.currentTimeMillis()));
        if (blockedMs > 0L) {
            long seconds = (blockedMs + 999L) / 1000L;
            output.setText("Android-Sprachdienst begrenzt neue Starts (Code 10). "
                    + "Erneuter Versuch frühestens in " + seconds
                    + " Sekunden. Texteingabe und KI FRAGEN bleiben verfügbar.");
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 18);
        panel.setPadding(pad, pad, pad, pad);
        TextView explanation = new TextView(this);
        explanation.setText("JARVIS hört nach Freigabe zu, solange diese App sichtbar geöffnet ist. "
                + "Du sprichst frei und ohne Ansprechwort; JARVIS antwortet und hört danach erneut zu. "
                + "Beim Verlassen der App stoppt das Mikrofon. "
                + "Dafür wird die lokale Android-Spracherkennung benötigt; dauerhafte Erkennung "
                + "kann je nach Gerät und Android-Sprachdienst unterbrochen werden. "
                + "Die Unterbrechung laufender Sprachausgabe ist experimentell: "
                + "ohne geprüfte Echounterdrückung ist sie nicht zuverlässig.");
        panel.addView(explanation);

        CheckBox aiPermission = new CheckBox(this);
        aiPermission.setText("Gesprochene Fragen und Gesprächskontext ohne Aktivierungswort an "
                + providerLabel(keyStore.provider(this))
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
        bargeInCapture = false;
        bargeInDetected = false;
        voiceHandler.removeCallbacks(voiceRestart);
        if (voiceInput != null) voiceInput.cancel();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(HANDSFREE_PREF, false)
                .putBoolean(VOICE_AI_PREF, false)
                .apply();
        updateStatus();
    }

    private boolean canListenNow() {
        return handsFreeEnabled && appVisible && !aiBusy
                && (!voiceWaitingForReply || speechInProgress);
    }

    private void queueNextListen(long delayMs) {
        voiceHandler.removeCallbacks(voiceRestart);
        if (canListenNow()) {
            long safeDelayMs = Math.max(Math.max(250L, delayMs),
                    speechStartGuard.waitBeforeStart(SystemClock.elapsedRealtime()));
            voiceHandler.postDelayed(voiceRestart, safeDelayMs);
        }
    }

    private void finishSpeaking(String utteranceId) {
        // Android may deliver stale completion callbacks after TTS.stop().
        if (utteranceId != null && utteranceId.equals(activeUtteranceId)) finishSpeaking();
    }

    private void finishSpeaking() {
        activeUtteranceId = null;
        speechInProgress = false;
        voiceWaitingForReply = false;
        // Stop a recognizer session that mostly heard the tail of our own TTS.
        if (bargeInCapture && voiceInput != null && voiceInput.isListening()) voiceInput.cancel();
        bargeInCapture = false;
        bargeInDetected = false;
        queueNextListen(RESTART_DELAY_MS);
        updateStatus();
    }

    private void stopSpeechForBargeIn() {
        if (!speechInProgress) return;
        activeUtteranceId = null;
        speechInProgress = false;
        voiceWaitingForReply = false;
        if (tts != null) tts.stop();
        output.setText("Unterbrechung erkannt. Ich höre zu.");
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
        if (JarvisBargeInGate.isStopCommand(request)) {
            voiceWaitingForReply = false;
            output.setText("Sprachausgabe gestoppt.");
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
        final String hfModel = JarvisSecretStore.HUGGING_FACE.equals(provider)
                ? keyStore.huggingFaceModel(this) : null;
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
                + providerLabel(provider) + ".");
        updateStatus();
        currentAiTask = aiExecutor.submit(() -> {
            String answer = null;
            String error = null;
            try {
                if (JarvisSecretStore.HUGGING_FACE.equals(provider)) {
                    answer = huggingFaceClient.answer(apiKey, hfModel, question, context, token);
                } else if (JarvisSecretStore.GEMINI.equals(provider)) {
                    answer = geminiClient.answer(apiKey, question, context, token);
                } else {
                    answer = aiClient.answer(apiKey, question, context, token);
                }
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
        explanation.setText("Wähle Gemini, OpenAI oder Hugging Face und verwende "
                + "deinen eigenen API-Schlüssel bzw. Hugging-Face-Token. "
                + "Kostenlose Kontingente können begrenzt oder nicht verfügbar sein. "
                + "Kostenfreiheit ist NICHT garantiert: prüfe deine Abrechnung selbst. "
                + "Es gibt keinen automatischen kostenpflichtigen Fallback und kein "
                + "hartes Euro-Kostenlimit. KI FRAGEN sendet die Eingabe; freie Sprache "
                + "erst nach weiterer Zustimmung. Keine Verbindung zu ChatGPT.");
        layout.addView(explanation);

        Spinner providerPicker = new Spinner(this);
        ArrayAdapter<String> providerOptions = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[] {
                "Google Gemini (Gratis-Kontingent möglich)",
                "OpenAI (mögliche API-Kosten)",
                "Hugging Face (Kontingent prüfen)"});
        providerOptions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerPicker.setAdapter(providerOptions);
        String currentProvider = keyStore.provider(this);
        providerPicker.setSelection(keyStore.isConfigured(this)
                ? (JarvisSecretStore.HUGGING_FACE.equals(currentProvider) ? 2
                    : (JarvisSecretStore.OPENAI.equals(currentProvider) ? 1 : 0)) : 0);
        layout.addView(providerPicker);

        EditText modelField = new EditText(this);
        modelField.setSingleLine(true);
        modelField.setHint("Hugging-Face-Modell: Organisation/Modell");
        modelField.setText(keyStore.huggingFaceModel(this));
        modelField.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(modelField);
        modelField.setVisibility(
                providerPicker.getSelectedItemPosition() == 2 ? View.VISIBLE : View.GONE);
        providerPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                modelField.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

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
                        int selection = providerPicker.getSelectedItemPosition();
                        String selectedProvider = selection == 2
                                ? JarvisSecretStore.HUGGING_FACE
                                : (selection == 0
                                    ? JarvisSecretStore.GEMINI : JarvisSecretStore.OPENAI);
                        String newKey = field.getText().toString().trim();
                        if (newKey.isEmpty()) {
                            output.setText("API-Schlüssel fehlt. Bei Hugging Face zuerst "
                                    + "einen Token auf huggingface.co/settings/tokens erstellen.");
                            return;
                        }
                        if (newKey.length() < 12 || newKey.length() > 1024) {
                            output.setText("API-Schlüssel/Token hat eine ungültige Länge.");
                            return;
                        }
                        if (JarvisSecretStore.HUGGING_FACE.equals(selectedProvider)) {
                            if (!newKey.matches("hf_[A-Za-z0-9_-]{9,253}")) {
                                output.setText("Ungültiger Hugging-Face-Token. "
                                        + "Er muss mit hf_ beginnen.");
                                return;
                            }
                            try {
                                JarvisHuggingFaceClient.normalizeModel(
                                        modelField.getText().toString());
                            } catch (IllegalArgumentException invalidModel) {
                                output.setText("Hugging-Face-Modell-ID ungültig. "
                                        + "Format: Organisation/Modell.");
                                return;
                            }
                        }
                        cancelAiRequest();
                        keyStore.saveAndEnable(this, newKey,
                                selectedProvider, modelField.getText().toString());
                        voiceAiConsent = false;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putBoolean(VOICE_AI_PREF, false).apply();
                        sessionTurns.clear();
                        output.setText("Separate KI aktiviert. Erst KI FRAGEN sendet deine Frage; "
                                + "die Verbindung ist noch nicht auf deinem S24 getestet.");
                        updateStatus();
                        dialog.dismiss();
                    } catch (IllegalArgumentException invalidInput) {
                        output.setText("Token oder Modellangabe ungültig. KI bleibt unverändert.");
                    } catch (GeneralSecurityException | IOException secureStorageError) {
                        output.setText("Verschlüsselte Speicherung fehlgeschlagen. "
                                + "Bitte Android-Keystore und Gerätesperre prüfen.");
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
            bargeInCapture = false;
            bargeInDetected = false;
            voiceWaitingForReply = true;
        }
        if (!speechMuted && ttsReady && tts != null) {
            speechInProgress = true;
            activeSpeechText = text;
            activeUtteranceId = "jarvis-response-" + (++utteranceSequence);
            int status = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeUtteranceId);
            if (status == TextToSpeech.ERROR) finishSpeaking(activeUtteranceId);
            else if (handsFreeEnabled) queueNextListen(280L);
        } else {
            finishSpeaking();
        }
    }

    private void updateStatus() {
        if (systemStatus == null || muteButton == null) return;
        String aiMode = aiBusy ? "KI ANFRAGE LÄUFT"
                : (keyStore.isConfigured(this)
                        ? (JarvisSecretStore.GEMINI.equals(keyStore.provider(this))
                            ? "GEMINI EINGERICHTET"
                            : (JarvisSecretStore.HUGGING_FACE.equals(keyStore.provider(this))
                                ? "HUGGING FACE EINGERICHTET" : "OPENAI EINGERICHTET"))
                        : "KI AUS");
        String voiceMode = handsFreeEnabled
                ? (speechInProgress
                    ? (voiceInput != null && voiceInput.isListening()
                        ? "JARVIS SPRICHT + MIKROFON (TEST)" : "JARVIS SPRICHT")
                    : (voiceInput != null && voiceInput.isListening()
                        ? "MIKROFON AN" : "FREISPRECHEN BEREIT"))
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
        if (muted && tts != null) {
            tts.stop();
            finishSpeaking();
        }
        updateStatus();
    }

    @Override
    public void cancelCurrentTask() {
        if (tts != null) tts.stop();
        activeUtteranceId = null;
        speechInProgress = false;
        bargeInCapture = false;
        bargeInDetected = false;
        voiceWaitingForReply = false;
        if (voiceInput != null) voiceInput.cancel();
        cancelAiRequest();
        queueNextListen(RESTART_DELAY_MS);
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
        activeUtteranceId = null;
        speechInProgress = false;
        bargeInCapture = false;
        bargeInDetected = false;
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
