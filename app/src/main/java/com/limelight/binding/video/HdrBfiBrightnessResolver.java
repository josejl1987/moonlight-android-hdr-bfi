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
     * Resolve the effective HDR/BFI brightness from raw settings and
     * current stream/display cadence.
     *
     * @param prefs     resolved preference snapshot
     * @param streamFps stream frame rate (Hz), 0 if unknown
     * @param displayHz display refresh rate (Hz), 0 if unknown
     * @return resolved brightness state
     */
    public static ResolvedHdrBfiBrightness resolve(
            PreferenceConfiguration prefs,
            float streamFps,
            float displayHz) {

        int darkFrames = BfiScheduler.sanitizeDarkFrames(prefs.videoBfiDarkFrames);
        boolean bfiIntent = prefs.videoBlackFrameInsertion;
        boolean bfiActive = bfiIntent
                && BfiScheduler.canEnable(streamFps, displayHz, darkFrames);
        boolean hdrIntent = prefs.videoHdrMode != PreferenceConfiguration.VIDEO_HDR_OFF;

        int target = prefs.videoHdrPaperWhiteNits;
        int max = prefs.videoHdrMaxEmittedWhiteNits;

        // Compensation only takes effect when HDR is on and BFI actually activates.
        int emitted;
        float duty;
        if (hdrIntent && bfiActive) {
            duty = BfiBrightnessCompensation.dutyCycle(true, 1, 1 + darkFrames);
            emitted = BfiBrightnessCompensation.emittedWhiteNits(
                    target, true, 1, 1 + darkFrames, max);
        } else {
            duty = 1.0f;
            emitted = target;
        }

        return new ResolvedHdrBfiBrightness(
                target, max, emitted, duty, darkFrames, bfiActive, hdrIntent);
    }
}
