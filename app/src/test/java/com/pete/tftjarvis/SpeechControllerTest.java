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

        @Override
        public int setLanguage(Locale loc) {
            this.lastLanguageSet = loc;
            return languageResult;
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

    private static class FakeContext extends android.content.ContextWrapper {
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
}
