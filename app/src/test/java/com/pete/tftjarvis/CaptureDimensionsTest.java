package com.pete.tftjarvis;

import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureDimensionsTest {
    @Test public void noResizeWhenCaptureMatchesSurface() {
        assertFalse(CaptureDimensions.shouldResize(3088, 1440, 3088, 1440));
    }

    @Test public void rotatingLandscapeToPortraitRequiresSurfaceResize() {
        assertTrue(CaptureDimensions.shouldResize(3088, 1440, 1440, 3088));
    }

    @Test public void sharingAppWindowRequiresSurfaceResize() {
        assertTrue(CaptureDimensions.shouldResize(3088, 1440, 1080, 1920));
    }

    @Test public void rejectInvalidAndPathologicalSizesBeforeAllocation() {
        assertFalse(CaptureDimensions.shouldResize(3088, 1440, 0, 1440));
        assertFalse(CaptureDimensions.shouldResize(3088, 1440, 100, -1));
        assertFalse(CaptureDimensions.shouldResize(3088, 1440, 16384, 16384));
        assertFalse(CaptureDimensions.shouldResize(3088, 1440, 1, 1));
    }

    @Test public void onlyLandscapeScreensMayUseTftHudCalibration() {
        assertTrue(CaptureDimensions.isCalibratedLandscape(3088, 1440));
        assertTrue(CaptureDimensions.isCalibratedLandscape(1920, 1080));
        assertFalse(CaptureDimensions.isCalibratedLandscape(1440, 3088));
        assertFalse(CaptureDimensions.isCalibratedLandscape(1080, 1920));
        assertFalse(CaptureDimensions.isCalibratedLandscape(0, 1440));
    }

    @Test public void acceptValidScreenAndNarrowWindow() {
        assertTrue(CaptureDimensions.valid(3088, 1440));
        assertTrue(CaptureDimensions.valid(400, 900));
    }
}
