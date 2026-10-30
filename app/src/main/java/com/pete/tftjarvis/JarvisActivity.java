package com.pete.tftjarvis;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.pete.tftjarvis.core.JarvisCoreEngine;
import com.pete.tftjarvis.core.LocalControlCapability;

import java.util.Locale;

/**
 * Independent Jarvis home. Starts without screen capture, API credentials,
 * microphone permission or a TFT match. Local commands do not use the network.
 */
public final class JarvisActivity extends Activity implements LocalControlCapability.Controls {
    private static final String PREFS = "jarvis_core_preferences";
    private static final String MUTED = "speech_muted";

    private EditText commandInput;
    private TextView output;
    private TextView systemStatus;
    private Button muteButton;
    private Button voiceButton;
    private JarvisSpeechInput voiceInput;
    private String speechState = "BEREIT";
    private JarvisCoreEngine core;
    private TextToSpeech tts;
    private boolean speechMuted;
    private boolean ttsReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jarvis);

        commandInput = findViewById(R.id.jarvisCommand);
        output = findViewById(R.id.jarvisOutput);
        systemStatus = findViewById(R.id.jarvisStatus);
        muteButton = findViewById(R.id.jarvisMuteButton);
        voiceButton = findViewById(R.id.jarvisVoiceButton);
        voiceInput = new JarvisSpeechInput(this, new JarvisSpeechInput.Callbacks() {
            @Override public void onSpeechState(String state) {
                speechState = state;
                updateStatus();
            }

            @Override public void onRecognizedText(String text) {
                commandInput.setText(text);
                runCommand(text);
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

        speechMuted = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(MUTED, false);
        core = new JarvisCoreEngine();
        core.register(new LocalControlCapability(this));

        findViewById(R.id.jarvisExecuteButton).setOnClickListener(v -> {
            runCommand(commandInput.getText().toString());
            commandInput.setText("");
        });
        findViewById(R.id.jarvisHelpButton).setOnClickListener(v -> runCommand("Hilfe"));
        findViewById(R.id.jarvisStatusButton).setOnClickListener(v -> runCommand("Status"));
        muteButton.setOnClickListener(v -> runCommand(speechMuted ? "Laut" : "Stumm"));

        commandInput.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                runCommand(commandInput.getText().toString());
                commandInput.setText("");
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

        output.setText("Jarvis bereit. Gib einen Befehl ein oder tippe auf Hilfe.");
    }

    private void runCommand(String command) {
        JarvisCoreEngine.Result result = core.handle(command);
        output.setText(result.message);
        updateStatus();

        // A cancellation command must not immediately restart speech.
        String normalized = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
        if (!speechMuted && ttsReady
                && !normalized.equals("abbrechen")
                && !normalized.equals("jarvis abbrechen")
                && result.outcome == JarvisCoreEngine.Outcome.DONE) {
            tts.speak(result.message, TextToSpeech.QUEUE_FLUSH, null, "jarvis-core-response");
        }
    }

    private void updateStatus() {
        if (systemStatus == null || muteButton == null) return;
        systemStatus.setText("LOKAL BEREIT  ·  "
                + (voiceInput != null && voiceInput.isListening()
                    ? speechState
                    : (speechMuted ? "STUMM" : (ttsReady ? "SPRACHE AKTIV" : "SPRACHE WIRD GELADEN"))));
        muteButton.setText(speechMuted ? "LAUT" : "STUMM");
        if (voiceButton != null) {
            voiceButton.setText(voiceInput != null && voiceInput.isListening()
                    ? "ZUHÖREN ABBRECHEN" : "SPRECHEN");
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
        // P0 supports local commands and speech, not external running jobs.
        if (tts != null) tts.stop();
        if (voiceInput != null) voiceInput.cancel();
    }

    @Override
    public String currentStatus() {
        return "Jarvis ist lokal bereit. Sprachausgabe "
                + (speechMuted ? "stumm." : (ttsReady ? "aktiv." : "noch nicht verfügbar."))
                + " Kein TFT und keine Cloud erforderlich.";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (voiceInput != null) voiceInput.onPermissionResult(requestCode, grantResults);
    }

    @Override
    protected void onDestroy() {
        if (voiceInput != null) voiceInput.cancel();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
