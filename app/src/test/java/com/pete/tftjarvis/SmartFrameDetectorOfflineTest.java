package com.pete.tftjarvis;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Synthetic 24x14 luminance traces simulate screen motion and stable TFT
 * phases entirely offline. They validate trigger mechanics, NOT OCR accuracy
 * or a real device's screen-capture performance.
 */
public class SmartFrameDetectorOfflineTest {
    private static byte[] frame(int brightness) {
        byte[] a = new byte[24 * 14];
        Arrays.fill(a, (byte) brightness);
        return a;
    }

    private static SmartFrameDetector withAnalyzedLobby() {
        SmartFrameDetector detector = new SmartFrameDetector();
        byte[] lobby = frame(50);
        assertFalse(detector.observeFingerprint(lobby, 1000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(lobby, 1600L, "VOR_MATCH"));
        assertTrue(detector.observeFingerprint(lobby, 2200L, "VOR_MATCH"));
        detector.markAnalyzedFingerprint(lobby, 2250L);
        detector.commitAnalyzed(2300L);
        return detector;
    }

    @Test public void stableLobbyTriggersOnceAndNeverSpamsAnIdenticalFrame() {
        SmartFrameDetector detector = withAnalyzedLobby();
        assertFalse(detector.observeFingerprint(frame(50), 9000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(50), 11000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(50), 22000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(50), 40000L, "VOR_MATCH"));
    }

    @Test public void lobbyToStableMatchScreenTriggersNewCloudAnalysis() {
        SmartFrameDetector detector = withAnalyzedLobby();
        assertFalse(detector.observeFingerprint(frame(170), 10000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(170), 10600L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(170), 11200L, "VOR_MATCH"));
        assertTrue(detector.observeFingerprint(frame(170), 11800L, "VOR_MATCH"));
        detector.markAnalyzedFingerprint(frame(170), 11900L);
        detector.commitAnalyzed(12000L);
        assertFalse(detector.observeFingerprint(frame(170), 20000L, "PLANUNG"));
    }

    @Test public void transientAnimationReturningToPreviousFrameIsIgnored() {
        SmartFrameDetector detector = withAnalyzedLobby();
        assertFalse(detector.observeFingerprint(frame(170), 10000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(50), 10600L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(50), 11200L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(50), 12400L, "VOR_MATCH"));
    }

    @Test public void failedInitialAnalysisRetriesWithBackoffThenStops() {
        SmartFrameDetector detector = new SmartFrameDetector();
        byte[] lobby = frame(50);
        assertFalse(detector.observeFingerprint(lobby, 1000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(lobby, 1600L, "VOR_MATCH"));
        assertTrue(detector.observeFingerprint(lobby, 2200L, "VOR_MATCH"));

        detector.markAnalyzedFingerprint(lobby, 2250L);
        detector.discardPendingAnalysis(2400L);
        assertFalse(detector.observeFingerprint(lobby, 2600L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(lobby, 3400L, "VOR_MATCH"));
        assertTrue(detector.observeFingerprint(lobby, 9600L, "VOR_MATCH"));

        detector.markAnalyzedFingerprint(lobby, 9650L);
        detector.discardPendingAnalysis(9700L);
        assertFalse(detector.observeFingerprint(lobby, 10500L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(lobby, 11000L, "VOR_MATCH"));
        assertTrue(detector.observeFingerprint(lobby, 23800L, "VOR_MATCH"));

        detector.markAnalyzedFingerprint(lobby, 23900L);
        detector.discardPendingAnalysis(24000L);
        assertFalse(detector.observeFingerprint(lobby, 25000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(lobby, 35000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(lobby, 37000L, "VOR_MATCH"));

        // Changing the game screen clears the failed-frame retry circuit breaker.
        assertFalse(detector.observeFingerprint(frame(170), 37600L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(170), 38200L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(170), 38800L, "VOR_MATCH"));
        assertTrue(detector.observeFingerprint(frame(170), 39400L, "VOR_MATCH"));
    }

    @Test public void successfulRetryResetsErrorBackoff() {
        SmartFrameDetector detector = withAnalyzedLobby();
        detector.markAnalyzedFingerprint(frame(50), 9000L);
        detector.discardPendingAnalysis(9300L);
        assertFalse(detector.observeFingerprint(frame(50), 9900L, "PLANUNG"));
        assertTrue(detector.observeFingerprint(frame(50), 17000L, "PLANUNG"));
        detector.markAnalyzedFingerprint(frame(50), 17200L);
        detector.commitAnalyzed(17300L);
        assertFalse(detector.observeFingerprint(frame(50), 30000L, "PLANUNG"));
    }

    @Test public void invalidFingerprintCannotBecomeLastAnalyzedState() {
        SmartFrameDetector detector = new SmartFrameDetector();
        assertFalse(detector.observeFingerprint(new byte[]{0}, 1000L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(50), 1600L, "VOR_MATCH"));
        assertFalse(detector.observeFingerprint(frame(50), 2200L, "VOR_MATCH"));
        assertTrue(detector.observeFingerprint(frame(50), 2800L, "VOR_MATCH"));
    }
}
