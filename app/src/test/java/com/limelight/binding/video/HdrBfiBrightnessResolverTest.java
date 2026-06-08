package com.limelight.binding.video;

import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HdrBfiBrightnessResolverTest {

    private PreferenceConfiguration prefsWith(boolean bfi, int darkFrames, int hdrMode) {
        PreferenceConfiguration p = new PreferenceConfiguration();
        p.videoHdrPaperWhiteNits = 200;
        p.videoHdrMaxEmittedWhiteNits = 1000;
        p.videoBlackFrameInsertion = bfi;
        p.videoBfiDarkFrames = darkFrames;
        p.videoHdrMode = hdrMode;
        return p;
    }

    private static void assertResolved(
            HdrBfiBrightnessResolver.Result r, int emitted, float duty, boolean bfi) {
        assertEquals(emitted, r.emittedNits);
        assertEquals(duty, r.dutyCycle, 1e-6f);
        assertEquals(bfi, r.bfiActive);
    }

    // ---- HDR off → no compensation, regardless of BFI ----

    @Test
    public void hdrOff_bfiValid_emitsTarget() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_OFF);
        HdrBfiBrightnessResolver.Result r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertResolved(r, 200, 1.0f, true);
    }

    // ---- HDR on + BFI invalid cadence → no compensation ----

    @Test
    public void hdrOn_bfiInvalidCadence_emitsTarget() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_SCRGB);
        p.videoBfiDarkFrames = 2;
        HdrBfiBrightnessResolver.Result r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertResolved(r, 200, 1.0f, false);
    }

    // ---- HDR off + BFI off → trivial pass-through ----

    @Test
    public void hdrOff_bfiOff_emitsTarget() {
        PreferenceConfiguration p = prefsWith(false, 1, PreferenceConfiguration.VIDEO_HDR_OFF);
        HdrBfiBrightnessResolver.Result r = HdrBfiBrightnessResolver.resolve(p, 0f, 0f);

        assertResolved(r, 200, 1.0f, false);
    }

    // ---- HDR on + BFI valid 60→120 / 1 dark → double emitted ----

    @Test
    public void hdrOn_bfi60to120_emits400() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_SCRGB);
        HdrBfiBrightnessResolver.Result r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertResolved(r, 400, 0.5f, true);
    }

    // ---- HDR on + BFI valid 60→180 / 2 dark → triple emitted ----

    @Test
    public void hdrOn_bfi60to180_2dark_emits600() {
        PreferenceConfiguration p = prefsWith(true, 2, PreferenceConfiguration.VIDEO_HDR_HDR10);
        HdrBfiBrightnessResolver.Result r = HdrBfiBrightnessResolver.resolve(p, 60f, 180f);

        assertResolved(r, 600, 1f / 3f, true);
    }

    // ---- clamp kicks in when max < computed emitted ----

    @Test
    public void clamp_limitsEmitted() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_HDR10);
        p.videoHdrMaxEmittedWhiteNits = 300;
        HdrBfiBrightnessResolver.Result r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertEquals(300, r.emittedNits);
    }

    // ---- unknown cadence (0) → BFI cannot activate ----

    @Test
    public void unknownCadence_bfiNotActive() {
        PreferenceConfiguration p = prefsWith(true, 1, PreferenceConfiguration.VIDEO_HDR_SCRGB);
        HdrBfiBrightnessResolver.Result r = HdrBfiBrightnessResolver.resolve(p, 0f, 0f);

        assertResolved(r, 200, 1.0f, false);
    }

    // ---- BFI enabled but darkFrames=0 gets sanitized to 1 ----

    @Test
    public void zeroDarkFrames_sanitized() {
        PreferenceConfiguration p = prefsWith(true, 0, PreferenceConfiguration.VIDEO_HDR_SCRGB);
        HdrBfiBrightnessResolver.Result r = HdrBfiBrightnessResolver.resolve(p, 60f, 120f);

        assertEquals(1, r.darkFrames);
        assertResolved(r, 400, 0.5f, true);
    }
}
