package com.limelight.binding.video;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * Single source of truth for the derived HDR/BFI brightness state.
 *
 * <p>Both the post-process renderer and the calibration UI call
 * {@link #resolve(PreferenceConfiguration, float, float)} so they
 * always agree on whether BFI is active, what the duty cycle is, and
 * what emitted nits to use.
 */
public final class HdrBfiBrightnessResolver {
    private HdrBfiBrightnessResolver() {}

    /**
     * Resolve the effective HDR/BFI brightness from a {@code PreferenceConfiguration}
     * snapshot and current stream/display cadence.
     */
    public static ResolvedHdrBfiBrightness resolve(
            PreferenceConfiguration prefs,
            float streamFps,
            float displayHz) {
        return resolve(
                prefs.videoBlackFrameInsertion,
                prefs.videoBfiDarkFrames,
                prefs.videoHdrMode,
                prefs.videoHdrPaperWhiteNits,
                prefs.videoHdrMaxEmittedWhiteNits,
                streamFps,
                displayHz);
    }

    /**
     * Resolve the effective HDR/BFI brightness from individual parameters.
     * This overload avoids temporary prefConfig mutation in the calibration UI.
     */
    public static ResolvedHdrBfiBrightness resolve(
            boolean bfiEnabled,
            int darkFrames,
            int hdrMode,
            int targetPerceivedNits,
            int maxEmittedNits,
            float streamFps,
            float displayHz) {

        int safeDarkFrames = BfiScheduler.sanitizeDarkFrames(darkFrames);
        boolean bfiActive = bfiEnabled
                && BfiScheduler.canEnable(streamFps, displayHz, safeDarkFrames);
        boolean hdrIntent = hdrMode != PreferenceConfiguration.VIDEO_HDR_OFF;

        int emitted;
        float duty;
        if (hdrIntent && bfiActive) {
            duty = BfiBrightnessCompensation.dutyCycle(true, 1, 1 + safeDarkFrames);
            emitted = BfiBrightnessCompensation.emittedWhiteNits(
                    targetPerceivedNits, true, 1, 1 + safeDarkFrames, maxEmittedNits);
        } else {
            duty = 1.0f;
            emitted = targetPerceivedNits;
        }

        return new ResolvedHdrBfiBrightness(
                targetPerceivedNits, maxEmittedNits, emitted,
                duty, safeDarkFrames, bfiActive, hdrIntent);
    }
}
