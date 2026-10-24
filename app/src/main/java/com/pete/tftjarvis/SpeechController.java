package com.pete.tftjarvis;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class SpeechController {

    public enum EngineState {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        UNSUPPORTED_LOCALE,
        ERROR
    }

    public interface TtsEngine {
        int setLanguage(Locale loc);
        int setVoice(Voice voice);
        int setSpeechRate(float rate);
        int setPitch(float pitch);
        List<TextToSpeech.EngineInfo> getEngines();
        Set<Voice> getVoices();
        Voice getVoice();
        int speak(CharSequence text, int queueMode, Bundle params, String utteranceId);
        int stop();
        void shutdown();
        void setUtteranceProgressListener(UtteranceProgressListener listener);
    }

    public interface OnInitCallback {
        void onInit(EngineState state);
    }

    public static class SpeechResult {
        public final boolean queued;
        public final String state;
        public final String reason;

        public SpeechResult(boolean queued, String state, String reason) {
            this.queued = queued;
            this.state = state;
            this.reason = reason;
        }
    }

    public static final String STATE_MUTED = "muted";
    public static final String STATE_POLICY_FILTERED = "policy_filtered";
    public static final String STATE_TTS_INITIALIZING = "tts_initializing";
    public static final String STATE_TTS_UNSUPPORTED_LOCALE = "tts_unsupported_locale";
    public static final String STATE_TTS_QUEUED = "tts_queued";
    public static final String STATE_TTS_STARTED = "tts_started";
    public static final String STATE_TTS_DONE = "tts_done";
    public static final String STATE_TTS_ERROR = "tts_error";
    public static final String STATE_AUDIO_ROUTE_OR_VOLUME_UNKNOWN = "audio_route_or_volume_unknown";

    private final Context context;
    private final TtsEngine ttsEngine;
    private final AtomicLong utteranceSequence = new AtomicLong(0L);

    private EngineState engineState = EngineState.UNINITIALIZED;
    private String lastEventState = STATE_TTS_INITIALIZING;
    private String lastEventReason = "TTS uninitialisiert";

    private String activeUtteranceId = "";
    private String activeKey = "";

    private String lastSpokenKey = "";
    private long lastSpokenAt = 0L;

    private String currentEnginePackage = "";
    private String currentVoiceName = "";
    private float currentSpeechRate = 1.0f;
    private float currentSpeechPitch = 1.0f;
    private OnInitCallback initCallback;
    private boolean disposed;
    private Voice defaultVoice;

    public SpeechController(Context context) {
        this(context, null, null);
    }

    public SpeechController(Context context, OnInitCallback callback) {
        this(context, null, callback);
    }

    public SpeechController(Context context, TtsEngine customEngine) {
        this(context, customEngine, null);
    }

    public SpeechController(Context context, TtsEngine customEngine, OnInitCallback callback) {
        this.context = context.getApplicationContext();
        this.initCallback = callback;
        this.currentEnginePackage = ConfigStore.loadTtsEnginePackage(this.context);
        this.currentVoiceName = ConfigStore.loadTtsVoiceName(this.context);
        this.currentSpeechRate = ConfigStore.loadSpeechRate(this.context);
        this.currentSpeechPitch = ConfigStore.loadSpeechPitch(this.context);

        if (customEngine != null) {
            this.ttsEngine = customEngine;
        } else {
            this.ttsEngine = new AndroidTtsEngine(this.context, this.currentEnginePackage);
        }
        initTts();
    }

    public synchronized void setOnInitCallback(OnInitCallback callback) {
        this.initCallback = callback;
        if (engineState == EngineState.READY || engineState == EngineState.ERROR || engineState == EngineState.UNSUPPORTED_LOCALE) {
            if (initCallback != null) {
                initCallback.onInit(engineState);
            }
        }
    }

    private synchronized void initTts() {
        if (engineState == EngineState.INITIALIZING || engineState == EngineState.READY) {
            return;
        }
        engineState = EngineState.INITIALIZING;
        updateEventState(STATE_TTS_INITIALIZING, "TTS Initialisierung gestartet");

        if (ttsEngine instanceof AndroidTtsEngine) {
            ((AndroidTtsEngine) ttsEngine).init((status) -> onTtsInitComplete(status));
        } else {
            onTtsInitComplete(TextToSpeech.SUCCESS);
        }
    }

    public synchronized void onTtsInitComplete(int status) {
        if (disposed) return;
        if (status == TextToSpeech.SUCCESS) {
            int result = ttsEngine.setLanguage(Locale.GERMAN);
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engineState = EngineState.UNSUPPORTED_LOCALE;
                updateEventState(STATE_TTS_UNSUPPORTED_LOCALE, "Deutsch (DE) wird vom TTS-Modul nicht unterstützt");
            } else {
                engineState = EngineState.READY;
                defaultVoice = ttsEngine.getVoice();
                applySpeechParameters();
                ttsEngine.setUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        handleUtteranceStart(utteranceId);
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        handleUtteranceDone(utteranceId);
                    }

                    @Override
                    public void onError(String utteranceId) {
                        handleUtteranceError(utteranceId);
                    }

                    @Override
                    public void onError(String utteranceId, int errorCode) {
                        handleUtteranceError(utteranceId);
                    }

                    @Override
                    public void onStop(String utteranceId, boolean interrupted) {
                        handleUtteranceStop(utteranceId);
                    }
                });
                updateEventState(STATE_TTS_DONE, "TTS bereit für Sprachausgabe");
            }
        } else {
            // Fallback: If init with custom engine package failed, retry with default engine package if available
            if (currentEnginePackage != null && !currentEnginePackage.isEmpty() && ttsEngine instanceof AndroidTtsEngine) {
                currentEnginePackage = "";
                ConfigStore.saveTtsEnginePackage(context, "");
                ((AndroidTtsEngine) ttsEngine).initWithDefaultEngine((fallbackStatus) -> onTtsInitComplete(fallbackStatus));
                return;
            }
            engineState = EngineState.ERROR;
            updateEventState(STATE_TTS_ERROR, "TTS-Initialisierung fehlgeschlagen (Status: " + status + ")");
        }

        if (initCallback != null) {
            initCallback.onInit(engineState);
        }
    }

    private synchronized void applySpeechParameters() {
        if (engineState != EngineState.READY) return;

        ttsEngine.setSpeechRate(currentSpeechRate);
        ttsEngine.setPitch(currentSpeechPitch);

        if (currentVoiceName != null && !currentVoiceName.isEmpty()) {
            Voice targetVoice = findVoiceByName(currentVoiceName);
            if (targetVoice != null) {
                ttsEngine.setVoice(targetVoice);
            }
        } else if (defaultVoice != null) {
            ttsEngine.setVoice(defaultVoice);
        }
    }

    public synchronized Voice findVoiceByName(String voiceName) {
        if (voiceName == null || voiceName.isEmpty()) return null;
        Set<Voice> voices = ttsEngine.getVoices();
        if (voices == null) return null;
        for (Voice v : voices) {
            if (v != null && voiceName.equals(v.getName())) {
                return v;
            }
        }
        return null;
    }

    public synchronized List<Voice> getAvailableGermanVoices() {
        List<Voice> germanVoices = new ArrayList<>();
        if (engineState != EngineState.READY) return germanVoices;
        Set<Voice> voices = ttsEngine.getVoices();
        if (voices != null) {
            for (Voice v : voices) {
                if (v != null && v.getLocale() != null && "de".equalsIgnoreCase(v.getLocale().getLanguage())) {
                    germanVoices.add(v);
                }
            }
        }
        return germanVoices;
    }

    public synchronized List<TextToSpeech.EngineInfo> getAvailableEngines() {
        List<TextToSpeech.EngineInfo> engines = ttsEngine.getEngines();
        return engines != null ? engines : Collections.emptyList();
    }

    public synchronized void setVoiceName(String voiceName) {
        this.currentVoiceName = voiceName != null ? voiceName.trim() : "";
        ConfigStore.saveTtsVoiceName(context, this.currentVoiceName);
        applySpeechParameters();
    }

    public synchronized String getVoiceName() {
        Voice activeVoice = ttsEngine.getVoice();
        return activeVoice != null ? activeVoice.getName() : currentVoiceName;
    }

    public synchronized void setSpeechRate(float rate) {
        this.currentSpeechRate = Math.max(0.5f, Math.min(2.0f, rate));
        ConfigStore.saveSpeechRate(context, this.currentSpeechRate);
        applySpeechParameters();
    }

    public synchronized float getSpeechRate() {
        return currentSpeechRate;
    }

    public synchronized void setSpeechPitch(float pitch) {
        this.currentSpeechPitch = Math.max(0.5f, Math.min(2.0f, pitch));
        ConfigStore.saveSpeechPitch(context, this.currentSpeechPitch);
        applySpeechParameters();
    }

    public synchronized float getSpeechPitch() {
        return currentSpeechPitch;
    }

    public synchronized void resetAudioParameters() {
        setSpeechRate(1.0f);
        setSpeechPitch(1.0f);
        setVoiceName("");
    }

    public synchronized SpeechResult speakCall(String phase, String call, String priority,
                                               String confidence, boolean manualRequest, boolean speechMuted) {
        if (speechMuted) {
            updateEventState(STATE_MUTED, "Sprachausgabe in Einstellungen stummgeschaltet");
            return new SpeechResult(false, STATE_MUTED, lastEventReason);
        }

        if (call == null || call.trim().isEmpty()) {
            updateEventState(STATE_POLICY_FILTERED, "Kein Text zum Sprechen vorhanden");
            return new SpeechResult(false, STATE_POLICY_FILTERED, lastEventReason);
        }

        String normalizedCall = call.toLowerCase(Locale.GERMAN)
                .replaceAll("[^a-z0-9äöüß ]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalizedCall.isEmpty()
                || normalizedCall.contains("nicht sicher erkennbar")
                || normalizedCall.contains("warte auf planungsphase")
                || "VOR_MATCH".equals(phase)
                || "ERGEBNIS".equals(phase)
                || "INFO".equals(priority)
                || "NIEDRIG".equals(confidence)) {
            updateEventState(STATE_POLICY_FILTERED, "Absichtlich gefiltert wegen Phase (" + phase
                    + "), Priorität (" + priority + ") oder Konfidenz (" + confidence + ")");
            return new SpeechResult(false, STATE_POLICY_FILTERED, lastEventReason);
        }

        boolean eligible;
        if (manualRequest) {
            eligible = ("KRITISCH".equals(priority) || "WICHTIG".equals(priority))
                    && ("HOCH".equals(confidence) || "MITTEL".equals(confidence));
        } else {
            eligible = "KRITISCH".equals(priority) && "HOCH".equals(confidence);
        }

        if (!eligible) {
            updateEventState(STATE_POLICY_FILTERED, "Absichtlich gefiltert (Modus: "
                    + (manualRequest ? "Manuell" : "Automatisch")
                    + ", Priorität: " + priority + ", Konfidenz: " + confidence + ")");
            return new SpeechResult(false, STATE_POLICY_FILTERED, lastEventReason);
        }

        long now = System.currentTimeMillis();
        String key = phase + "|" + normalizedCall;
        if (key.equals(lastSpokenKey) && now - lastSpokenAt < 30_000L) {
            updateEventState(STATE_POLICY_FILTERED, "Duplikat innerhalb von 30 Sekunden unterdrückt");
            return new SpeechResult(false, STATE_POLICY_FILTERED, lastEventReason);
        }

        if (engineState == EngineState.UNINITIALIZED || engineState == EngineState.INITIALIZING) {
            updateEventState(STATE_TTS_INITIALIZING, "TTS noch nicht bereit; Hinweis nicht gesprochen, bitte erneut prüfen");
            return new SpeechResult(false, STATE_TTS_INITIALIZING, lastEventReason);
        }

        if (engineState == EngineState.UNSUPPORTED_LOCALE) {
            updateEventState(STATE_TTS_UNSUPPORTED_LOCALE, "TTS unterstützt deutsche Sprache nicht");
            return new SpeechResult(false, STATE_TTS_UNSUPPORTED_LOCALE, lastEventReason);
        }

        if (engineState == EngineState.ERROR) {
            updateEventState(STATE_TTS_ERROR, "TTS befindet sich im Fehlerzustand");
            return new SpeechResult(false, STATE_TTS_ERROR, lastEventReason);
        }

        return executeSpeak(key, call, now);
    }

    public synchronized SpeechResult speakTestAnnouncement(boolean speechMuted) {
        return speakTestPhrase("JARVIS bereit. Artefakt erkannt.", speechMuted);
    }

    public synchronized SpeechResult speakTestPhrase(String testCall, boolean speechMuted) {
        if (speechMuted) {
            updateEventState(STATE_MUTED, "Sprachausgabe stumm geschaltet");
            return new SpeechResult(false, STATE_MUTED, lastEventReason);
        }

        if (engineState == EngineState.UNINITIALIZED || engineState == EngineState.INITIALIZING) {
            updateEventState(STATE_TTS_INITIALIZING, "TTS wird initialisiert");
            return new SpeechResult(false, STATE_TTS_INITIALIZING, lastEventReason);
        }

        if (engineState == EngineState.UNSUPPORTED_LOCALE) {
            updateEventState(STATE_TTS_UNSUPPORTED_LOCALE, "Deutsch wird vom TTS-Modul nicht unterstützt");
            return new SpeechResult(false, STATE_TTS_UNSUPPORTED_LOCALE, lastEventReason);
        }

        if (engineState == EngineState.ERROR) {
            updateEventState(STATE_TTS_ERROR, "TTS-Fehler aufgetreten");
            return new SpeechResult(false, STATE_TTS_ERROR, lastEventReason);
        }

        if (testCall == null || testCall.trim().isEmpty()) {
            testCall = "JARVIS bereit. Artefakt erkannt.";
        }
        String testKey = "TEST|" + System.currentTimeMillis();
        return executeSpeak(testKey, testCall, System.currentTimeMillis());
    }

    private synchronized SpeechResult executeSpeak(String key, String callText, long now) {
        int musicVolume = getMusicVolume();
        boolean volumeZero = (musicVolume == 0);

        String utteranceId = "tft-call-" + utteranceSequence.incrementAndGet();
        activeUtteranceId = utteranceId;
        activeKey = key;

        int speakResult = ttsEngine.speak(callText, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (speakResult == TextToSpeech.SUCCESS) {
            lastSpokenKey = key;
            lastSpokenAt = now;
            if (volumeZero) {
                updateEventState(STATE_AUDIO_ROUTE_OR_VOLUME_UNKNOWN, "Sprachausgabe eingereiht, aber Medienlautstärke ist 0");
                return new SpeechResult(true, STATE_AUDIO_ROUTE_OR_VOLUME_UNKNOWN, lastEventReason);
            } else {
                updateEventState(STATE_TTS_QUEUED, "Sprachausgabe erfolgreich an TTS übergeben");
                return new SpeechResult(true, STATE_TTS_QUEUED, lastEventReason);
            }
        } else {
            activeUtteranceId = "";
            activeKey = "";
            clearSpokenKeyIfMatches(key);
            updateEventState(STATE_TTS_ERROR, "tts.speak() meldete Fehler (Code: " + speakResult + ")");
            return new SpeechResult(false, STATE_TTS_ERROR, lastEventReason);
        }
    }

    private synchronized void handleUtteranceStart(String utteranceId) {
        if (utteranceId != null && utteranceId.equals(activeUtteranceId)) {
            updateEventState(STATE_TTS_STARTED, "Wiedergabe gestartet");
        }
    }

    private synchronized void handleUtteranceDone(String utteranceId) {
        if (utteranceId != null && utteranceId.equals(activeUtteranceId)) {
            updateEventState(STATE_TTS_DONE, "Wiedergabe abgeschlossen");
            activeUtteranceId = "";
            activeKey = "";
        }
    }

    private synchronized void handleUtteranceError(String utteranceId) {
        if (utteranceId != null && utteranceId.equals(activeUtteranceId)) {
            clearSpokenKeyIfMatches(activeKey);
            activeUtteranceId = "";
            activeKey = "";
            updateEventState(STATE_TTS_ERROR, "TTS-Wiedergabefehler bei Utterance " + utteranceId);
        }
    }

    private synchronized void handleUtteranceStop(String utteranceId) {
        if (utteranceId != null && utteranceId.equals(activeUtteranceId)) {
            activeUtteranceId = "";
            activeKey = "";
            updateEventState(STATE_TTS_DONE, "Wiedergabe abgebrochen/gestoppt");
        }
    }

    private synchronized void clearSpokenKeyIfMatches(String key) {
        if (key != null && key.equals(lastSpokenKey)) {
            lastSpokenKey = "";
            lastSpokenAt = 0L;
        }
    }

    public synchronized void stop() {
        if (ttsEngine != null) {
            try {
                ttsEngine.stop();
            } catch (Throwable ignored) {}
        }
        activeUtteranceId = "";
        activeKey = "";
    }

    public synchronized void resetMemory() {
        stop();
        lastSpokenKey = "";
        lastSpokenAt = 0L;
    }

    public synchronized void shutdown() {
        disposed = true;
        initCallback = null;
        stop();
        if (ttsEngine != null) {
            try {
                ttsEngine.shutdown();
            } catch (Throwable ignored) {}
        }
        engineState = EngineState.UNINITIALIZED;
    }

    private synchronized void updateEventState(String state, String reason) {
        this.lastEventState = state;
        this.lastEventReason = reason;
    }

    public synchronized EngineState getEngineState() {
        return engineState;
    }

    public synchronized String getLastEventState() {
        return lastEventState;
    }

    public synchronized String getLastEventReason() {
        return lastEventReason;
    }

    public synchronized String getStatusSummary(boolean speechMuted) {
        int vol = getMusicVolume();
        StringBuilder sb = new StringBuilder();
        sb.append("Sprache: ").append(speechMuted ? "STUMM" : "AN");
        sb.append(" | Engine: ").append(engineState.name());
        sb.append(" | Event: ").append(lastEventState);
        if (vol >= 0) {
            sb.append(" | Vol: ").append(vol);
        }
        if (lastEventReason != null && !lastEventReason.isEmpty()) {
            sb.append(" (").append(lastEventReason).append(")");
        }
        return sb.toString();
    }

    private int getMusicVolume() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                return am.getStreamVolume(AudioManager.STREAM_MUSIC);
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static class AndroidTtsEngine implements TtsEngine {
        private final Context context;
        private final String enginePackage;
        private TextToSpeech tts;

        AndroidTtsEngine(Context context, String enginePackage) {
            this.context = context;
            this.enginePackage = enginePackage;
        }

        void init(TextToSpeech.OnInitListener listener) {
            if (enginePackage != null && !enginePackage.trim().isEmpty()) {
                tts = new TextToSpeech(context, listener, enginePackage.trim());
            } else {
                tts = new TextToSpeech(context, listener);
            }
        }

        void initWithDefaultEngine(TextToSpeech.OnInitListener listener) {
            if (tts != null) {
                try { tts.shutdown(); } catch (Throwable ignored) {}
            }
            tts = new TextToSpeech(context, listener);
        }

        @Override
        public int setLanguage(Locale loc) {
            return tts != null ? tts.setLanguage(loc) : TextToSpeech.LANG_NOT_SUPPORTED;
        }

        @Override
        public int setVoice(Voice voice) {
            return tts != null ? tts.setVoice(voice) : TextToSpeech.ERROR;
        }

        @Override
        public int setSpeechRate(float rate) {
            return tts != null ? tts.setSpeechRate(rate) : TextToSpeech.ERROR;
        }

        @Override
        public int setPitch(float pitch) {
            return tts != null ? tts.setPitch(pitch) : TextToSpeech.ERROR;
        }

        @Override
        public List<TextToSpeech.EngineInfo> getEngines() {
            return tts != null ? tts.getEngines() : Collections.emptyList();
        }

        @Override
        public Set<Voice> getVoices() {
            return tts != null ? tts.getVoices() : Collections.emptySet();
        }

        @Override
        public Voice getVoice() {
            return tts != null ? tts.getVoice() : null;
        }

        @Override
        public int speak(CharSequence text, int queueMode, Bundle params, String utteranceId) {
            return tts != null ? tts.speak(text, queueMode, params, utteranceId) : TextToSpeech.ERROR;
        }

        @Override
        public int stop() {
            return tts != null ? tts.stop() : TextToSpeech.ERROR;
        }

        @Override
        public void shutdown() {
            if (tts != null) {
                tts.shutdown();
                tts = null;
            }
        }

        @Override
        public void setUtteranceProgressListener(UtteranceProgressListener listener) {
            if (tts != null) {
                tts.setOnUtteranceProgressListener(listener);
            }
        }
    }
}
