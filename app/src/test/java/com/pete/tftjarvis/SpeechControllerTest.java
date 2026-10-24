package com.pete.tftjarvis;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

public class SpeechControllerTest {

    private static class FakeTtsEngine implements SpeechController.TtsEngine {
        int languageResult = TextToSpeech.LANG_AVAILABLE;
        int speakResult = TextToSpeech.SUCCESS;
        int stopResult = TextToSpeech.SUCCESS;
        boolean shutdownCalled = false;

        Locale lastLanguageSet = null;
        String lastSpokenText = null;
        String lastUtteranceId = null;
        UtteranceProgressListener listener = null;

        android.speech.tts.Voice activeVoice = null;
        float lastRate = 1.0f;
        float lastPitch = 1.0f;
        java.util.Set<android.speech.tts.Voice> voicesSet = new java.util.HashSet<>();
        java.util.List<TextToSpeech.EngineInfo> enginesList = new java.util.ArrayList<>();

        @Override
        public int setLanguage(Locale loc) {
            this.lastLanguageSet = loc;
            return languageResult;
        }

        @Override
        public int setVoice(android.speech.tts.Voice voice) {
            this.activeVoice = voice;
            return TextToSpeech.SUCCESS;
        }

        @Override
        public int setSpeechRate(float rate) {
            this.lastRate = rate;
            return TextToSpeech.SUCCESS;
        }

        @Override
        public int setPitch(float pitch) {
            this.lastPitch = pitch;
            return TextToSpeech.SUCCESS;
        }

        @Override
        public java.util.List<TextToSpeech.EngineInfo> getEngines() {
            return enginesList;
        }

        @Override
        public java.util.Set<android.speech.tts.Voice> getVoices() {
            return voicesSet;
        }

        @Override
        public android.speech.tts.Voice getVoice() {
            return activeVoice;
        }

        @Override
        public int speak(CharSequence text, int queueMode, Bundle params, String utteranceId) {
            this.lastSpokenText = text != null ? text.toString() : null;
            this.lastUtteranceId = utteranceId;
            return speakResult;
        }

        @Override
        public int stop() {
            return stopResult;
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }

        @Override
        public void setUtteranceProgressListener(UtteranceProgressListener listener) {
            this.listener = listener;
        }
    }

    private static class FakeSharedPreferences implements android.content.SharedPreferences {
        private final java.util.Map<String, Object> map = new java.util.HashMap<>();

        @Override
        public java.util.Map<String, ?> getAll() { return map; }

        @Override
        public String getString(String key, String defValue) {
            return map.containsKey(key) ? (String) map.get(key) : defValue;
        }

        @Override
        public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            return map.containsKey(key) ? (Integer) map.get(key) : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            return map.containsKey(key) ? (Long) map.get(key) : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            return map.containsKey(key) ? (Float) map.get(key) : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            return map.containsKey(key) ? (Boolean) map.get(key) : defValue;
        }

        @Override
        public boolean contains(String key) { return map.containsKey(key); }

        @Override
        public Editor edit() {
            return new Editor() {
                @Override public Editor putString(String key, String value) { map.put(key, value); return this; }
                @Override public Editor putStringSet(String key, java.util.Set<String> values) { map.put(key, values); return this; }
                @Override public Editor putInt(String key, int value) { map.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { map.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { map.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { map.put(key, value); return this; }
                @Override public Editor remove(String key) { map.remove(key); return this; }
                @Override public Editor clear() { map.clear(); return this; }
                @Override public boolean commit() { return true; }
                @Override public void apply() {}
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    }

    private static class FakeContext extends android.content.ContextWrapper {
        private final android.content.SharedPreferences prefs = new FakeSharedPreferences();

        FakeContext() {
            super(null);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Object getSystemService(String name) {
            return null;
        }

        @Override
        public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return prefs;
        }
    }

    private FakeContext context;
    private FakeTtsEngine fakeEngine;

    @Before
    public void setUp() {
        context = new FakeContext();
        fakeEngine = new FakeTtsEngine();
    }

    @Test
    public void testInitializationSuccess() {
        SpeechController controller = new SpeechController(context, fakeEngine);
        assertEquals(SpeechController.EngineState.READY, controller.getEngineState());
        assertEquals(Locale.GERMAN, fakeEngine.lastLanguageSet);
        assertEquals(SpeechController.STATE_TTS_DONE, controller.getLastEventState());
    }

    @Test
    public void testInitializationUnsupportedLocale() {
        fakeEngine.languageResult = TextToSpeech.LANG_NOT_SUPPORTED;
        SpeechController controller = new SpeechController(context, fakeEngine);

        assertEquals(SpeechController.EngineState.UNSUPPORTED_LOCALE, controller.getEngineState());
        assertEquals(SpeechController.STATE_TTS_UNSUPPORTED_LOCALE, controller.getLastEventState());

        SpeechController.SpeechResult result = controller.speakCall(
                "PLANUNG", "Kaufe Ashe", "KRITISCH", "HOCH", false, false);
        assertFalse(result.queued);
        assertEquals(SpeechController.STATE_TTS_UNSUPPORTED_LOCALE, result.state);
    }

    @Test
    public void testInitializationFailure() {
        SpeechController controller = new SpeechController(context, fakeEngine);
        controller.onTtsInitComplete(TextToSpeech.ERROR);

        assertEquals(SpeechController.EngineState.ERROR, controller.getEngineState());
        assertEquals(SpeechController.STATE_TTS_ERROR, controller.getLastEventState());

        SpeechController.SpeechResult result = controller.speakCall(
                "PLANUNG", "Kaufe Ashe", "KRITISCH", "HOCH", false, false);
        assertFalse(result.queued);
        assertEquals(SpeechController.STATE_TTS_ERROR, result.state);
    }

    @Test
    public void testPolicyFilteringMuted() {
        SpeechController controller = new SpeechController(context, fakeEngine);
        SpeechController.SpeechResult result = controller.speakCall(
                "PLANUNG", "Kaufe Ashe", "KRITISCH", "HOCH", false, true);

        assertFalse(result.queued);
        assertEquals(SpeechController.STATE_MUTED, result.state);
    }

    @Test
    public void testPolicyFilteringPhaseAndPriority() {
        SpeechController controller = new SpeechController(context, fakeEngine);

        // VOR_MATCH phase filtered
        SpeechController.SpeechResult r1 = controller.speakCall(
                "VOR_MATCH", "Warte auf Start", "KRITISCH", "HOCH", false, false);
        assertFalse(r1.queued);
        assertEquals(SpeechController.STATE_POLICY_FILTERED, r1.state);

        // INFO priority filtered
        SpeechController.SpeechResult r2 = controller.speakCall(
                "PLANUNG", "Level 4 erreicht", "INFO", "HOCH", false, false);
        assertFalse(r2.queued);
        assertEquals(SpeechController.STATE_POLICY_FILTERED, r2.state);

        // Auto request with WICHTIG (auto requires KRITISCH) filtered
        SpeechController.SpeechResult r3 = controller.speakCall(
                "PLANUNG", "Rolle jetzt", "WICHTIG", "HOCH", false, false);
        assertFalse(r3.queued);
        assertEquals(SpeechController.STATE_POLICY_FILTERED, r3.state);

        // Manual request with WICHTIG + HOCH allowed
        SpeechController.SpeechResult r4 = controller.speakCall(
                "PLANUNG", "Rolle jetzt", "WICHTIG", "HOCH", true, false);
        assertTrue(r4.queued);
        assertEquals(SpeechController.STATE_TTS_QUEUED, r4.state);
    }

    @Test
    public void testDeduplicationAndRecoveryOnSpeakError() {
        SpeechController controller = new SpeechController(context, fakeEngine);

        // First attempt succeeds
        SpeechController.SpeechResult r1 = controller.speakCall(
                "PLANUNG", "Kaufe Ashe", "KRITISCH", "HOCH", false, false);
        assertTrue(r1.queued);

        // Immediate duplicate suppressed
        SpeechController.SpeechResult r2 = controller.speakCall(
                "PLANUNG", "Kaufe Ashe", "KRITISCH", "HOCH", false, false);
        assertFalse(r2.queued);
        assertEquals(SpeechController.STATE_POLICY_FILTERED, r2.state);

        // Distinct call allowed
        SpeechController.SpeechResult r3 = controller.speakCall(
                "PLANUNG", "Kaufe Garen", "KRITISCH", "HOCH", false, false);
        assertTrue(r3.queued);

        // Simulate speak failure for a new call
        fakeEngine.speakResult = TextToSpeech.ERROR;
        SpeechController.SpeechResult r4 = controller.speakCall(
                "PLANUNG", "Level up auf 6", "KRITISCH", "HOCH", false, false);
        assertFalse(r4.queued);
        assertEquals(SpeechController.STATE_TTS_ERROR, r4.state);

        // Fix engine failure -> same call should NOT be falsely deduplicated
        fakeEngine.speakResult = TextToSpeech.SUCCESS;
        SpeechController.SpeechResult r5 = controller.speakCall(
                "PLANUNG", "Level up auf 6", "KRITISCH", "HOCH", false, false);
        assertTrue(r5.queued);
    }

    @Test
    public void testUtteranceProgressListenerCallbacks() {
        SpeechController controller = new SpeechController(context, fakeEngine);
        assertNotNull(fakeEngine.listener);

        SpeechController.SpeechResult r1 = controller.speakCall(
                "PLANUNG", "Kaufe Ashe", "KRITISCH", "HOCH", false, false);
        assertTrue(r1.queued);
        String utteranceId = fakeEngine.lastUtteranceId;

        fakeEngine.listener.onStart(utteranceId);
        assertEquals(SpeechController.STATE_TTS_STARTED, controller.getLastEventState());

        fakeEngine.listener.onDone(utteranceId);
        assertEquals(SpeechController.STATE_TTS_DONE, controller.getLastEventState());

        // Test error callback clears dedup key if same utterance errored
        fakeEngine.speakResult = TextToSpeech.SUCCESS;
        SpeechController.SpeechResult r2 = controller.speakCall(
                "PLANUNG", "Kaufe Darius", "KRITISCH", "HOCH", false, false);
        assertTrue(r2.queued);
        String utteranceId2 = fakeEngine.lastUtteranceId;

        fakeEngine.listener.onError(utteranceId2);
        assertEquals(SpeechController.STATE_TTS_ERROR, controller.getLastEventState());

        // Retrying Darius should work now because error cleared the dedup key
        SpeechController.SpeechResult r3 = controller.speakCall(
                "PLANUNG", "Kaufe Darius", "KRITISCH", "HOCH", false, false);
        assertTrue(r3.queued);
    }

    @Test
    public void testTestAnnouncement() {
        SpeechController controller = new SpeechController(context, fakeEngine);

        SpeechController.SpeechResult result = controller.speakTestAnnouncement(false);
        assertTrue(result.queued);
        assertEquals("JARVIS bereit. Artefakt erkannt.", fakeEngine.lastSpokenText);

        // Test custom test phrase
        SpeechController.SpeechResult customResult = controller.speakTestPhrase("JARVIS Sprachausgabe aktiv. Test erfolgreich.", false);
        assertTrue(customResult.queued);
        assertEquals("JARVIS Sprachausgabe aktiv. Test erfolgreich.", fakeEngine.lastSpokenText);

        // Test announcement when muted
        SpeechController.SpeechResult mutedResult = controller.speakTestAnnouncement(true);
        assertFalse(mutedResult.queued);
        assertEquals(SpeechController.STATE_MUTED, mutedResult.state);
    }

    @Test
    public void testResetMemoryAndShutdown() {
        SpeechController controller = new SpeechController(context, fakeEngine);

        controller.speakCall("PLANUNG", "Kaufe Ashe", "KRITISCH", "HOCH", false, false);
        controller.resetMemory();

        // After reset, same call should be allowed again
        SpeechController.SpeechResult r1 = controller.speakCall(
                "PLANUNG", "Kaufe Ashe", "KRITISCH", "HOCH", false, false);
        assertTrue(r1.queued);

        controller.shutdown();
        assertTrue(fakeEngine.shutdownCalled);
        assertEquals(SpeechController.EngineState.UNINITIALIZED, controller.getEngineState());
    }

    @Test
    public void testSpeechRateAndPitchClampingAndReset() {
        SpeechController controller = new SpeechController(context, fakeEngine);

        // Clamping rate
        controller.setSpeechRate(0.2f);
        assertEquals(0.5f, controller.getSpeechRate(), 0.01f);
        assertEquals(0.5f, fakeEngine.lastRate, 0.01f);

        controller.setSpeechRate(3.0f);
        assertEquals(2.0f, controller.getSpeechRate(), 0.01f);
        assertEquals(2.0f, fakeEngine.lastRate, 0.01f);

        // Clamping pitch
        controller.setSpeechPitch(0.1f);
        assertEquals(0.5f, controller.getSpeechPitch(), 0.01f);
        assertEquals(0.5f, fakeEngine.lastPitch, 0.01f);

        controller.setSpeechPitch(2.5f);
        assertEquals(2.0f, controller.getSpeechPitch(), 0.01f);
        assertEquals(2.0f, fakeEngine.lastPitch, 0.01f);

        // Reset
        controller.resetAudioParameters();
        assertEquals(1.0f, controller.getSpeechRate(), 0.01f);
        assertEquals(1.0f, controller.getSpeechPitch(), 0.01f);
        assertEquals("", controller.getVoiceName());
    }

    @Test
    public void testVoiceAndEngineEnumerationWithNulls() {
        SpeechController controller = new SpeechController(context, fakeEngine);

        assertNull(controller.findVoiceByName(null));
        assertNull(controller.findVoiceByName("unknown-voice"));
        assertTrue(controller.getAvailableGermanVoices().isEmpty());
        assertTrue(controller.getAvailableEngines().isEmpty());
    }
    @Test
    public void savedAudioSettingsApplyToSeparateCoachController() {
        SpeechController preview = new SpeechController(context, fakeEngine);
        preview.setSpeechRate(0.85f);
        preview.setSpeechPitch(1.1f);
        FakeTtsEngine coachEngine = new FakeTtsEngine();
        SpeechController coach = new SpeechController(context, coachEngine);
        assertEquals(0.85f, coachEngine.lastRate, 0.001f);
        assertEquals(1.1f, coachEngine.lastPitch, 0.001f);
        assertTrue(coach.speakCall("PLANUNG", "Test", "WICHTIG", "HOCH", true, false).queued);
        assertEquals("Test", coachEngine.lastSpokenText);
    }

    @Test
    public void lateInitializationCannotReviveDisposedController() {
        SpeechController controller = new SpeechController(context, fakeEngine);
        final int[] callbacks = {0};
        controller.setOnInitCallback(state -> callbacks[0]++);
        int beforeShutdown = callbacks[0];
        controller.shutdown();
        controller.onTtsInitComplete(TextToSpeech.SUCCESS);
        assertEquals(beforeShutdown, callbacks[0]);
        assertEquals(SpeechController.EngineState.UNINITIALIZED, controller.getEngineState());
        assertFalse(controller.speakTestAnnouncement(false).queued);
    }
}
