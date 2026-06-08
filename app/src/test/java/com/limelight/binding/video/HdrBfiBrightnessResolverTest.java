package com.limelight.binding.video;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link HdrBfiBrightnessResolver}.
 *
 * <p>The resolver is the single source of truth for derived HDR/BFI
 * state — both the renderer and calibration UI depend on it.  These
 * tests verify all key resolution paths.</p>
 */
public class HdrBfiBrightnessResolverTest {

    private PreferenceConfiguration prefsWith(boolean bfi, int darkFrames, int hdrMode) {
        PreferenceConfiguration p = new PreferenceConfiguration();
        p.videoHdrPaperWhiteNits = 200;
        p.videoHdrMaxEmittedWhiteNits = 1000;
        p.videoBlackFrameInsertion = bfi;
        p.videoBfiDarkFrames = darkFrames;
        p.videoHdrMode = hdrMode;
        p.fps = 60;
        return p;
    }

    // ---- HDR off → no compensation, regardless of BFI ----

    @Test
    public void hdrOff_bfiValid_emitsTarget() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_OFF);
        ResolvedHdrBfiBrightness r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertEquals(200, r.emittedNits);
        assertEquals(200, r.targetPerceivedNits);
        assertEquals(1.0f, r.dutyCycle, 0f);
        assertFalse(r.hdrIntent);
        assertTrue(r.bfiActive); // BFI cadence is valid, but no HDR → no compensation
    }

    // ---- HDR on + BFI invalid cadence → no compensation ----

    @Test
    public void hdrOn_bfiInvalidCadence_emitsTarget() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_SCRGB);
        // 60 fps, 2 dark frames → needs 180 Hz, but display is only 120
        p.videoBfiDarkFrames = 2;
        ResolvedHdrBfiBrightness r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertEquals(200, r.emittedNits);
        assertEquals(1.0f, r.dutyCycle, 0f);
        assertFalse(r.bfiActive);
        assertTrue(r.hdrIntent);
    }

    // ---- HDR off + BFI off → trivial pass-through ----

    @Test
    public void hdrOff_bfiOff_emitsTarget() {
        PreferenceConfiguration p = prefsWith(false, 1, PreferenceConfiguration.VIDEO_HDR_OFF);
        ResolvedHdrBfiBrightness r = HdrBfiBrightnessResolver.resolve(p, 0f, 0f);

        assertEquals(200, r.emittedNits);
        assertEquals(1.0f, r.dutyCycle, 0f);
        assertFalse(r.bfiActive);
        assertFalse(r.hdrIntent);
    }

    // ---- HDR on + BFI valid 60→120 / 1 dark → double emitted ----

    @Test
    public void hdrOn_bfi60to120_emits400() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_SCRGB);
        ResolvedHdrBfiBrightness r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertEquals(400, r.emittedNits);
        assertEquals(0.5f, r.dutyCycle, 0f);
        assertTrue(r.bfiActive);
        assertTrue(r.hdrIntent);
    }

    // ---- HDR on + BFI valid 60→180 / 2 dark → triple emitted ----

    @Test
    public void hdrOn_bfi60to180_2dark_emits600() {
        PreferenceConfiguration p = prefsWith(true, 2, PreferenceConfiguration.VIDEO_HDR_HDR10);
        ResolvedHdrBfiBrightness r = HdrBfiBrightnessResolver.resolve(p, 60f, 180f);

        assertEquals(600, r.emittedNits);
        assertEquals(1f / 3f, r.dutyCycle, 1e-6f);
        assertTrue(r.bfiActive);
        assertTrue(r.hdrIntent);
    }

    // ---- clamp kicks in when max < computed emitted ----

    @Test
    public void clamp_limitsEmitted() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_HDR10);
        p.videoHdrMaxEmittedWhiteNits = 300; // clamp at 300, but 200/0.5 = 400
        ResolvedHdrBfiBrightness r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertEquals(300, r.emittedNits);
        assertEquals(300, r.maxEmittedNits);
    }

    // ---- unknown cadence (0) → BFI cannot activate ----

    @Test
    public void unknownCadence_bfiNotActive() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_SCRGB);
        ResolvedHdrBfiBrightness r = HdrBfiBrightnessResolver.resolve(p, 0f, 0f);

        assertFalse(r.bfiActive);
        assertEquals(200, r.emittedNits); // no compensation
    }

    // ---- BFI enabled but darkFrames=0 gets sanitized to 1 ----

    @Test
    public void zeroDarkFrames_sanitized() {
        PreferenceConfiguration p = prefsWith(true, 0, PreferenceConfiguration.VIDEO_HDR_SCRGB);
        ResolvedHdrBfiBrightness r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertEquals(1, r.darkFrames);
        assertTrue(r.bfiActive);
        assertEquals(400, r.emittedNits);
    }
}
