package com.limelight.binding.video;

import com.limelight.preferences.PreferenceConfiguration;

/**
 * Single source of truth for derived HDR/BFI brightness state.
 *
 * <p>Both the post-process renderer and calibration UI call
 * {@link #resolve} so they always agree on emitted brightness,
 * duty cycle, and whether BFI is active.
 */
public final class HdrBfiBrightnessResolver {
    private HdrBfiBrightnessResolver() {}

    /** Resolved HDR/BFI brightness for a single rendering context. */
    public static final class Result {
        public final int emittedNits;
        public final float dutyCycle;
        public final int darkFrames;
        public final boolean bfiActive;

        private Result(int emittedNits, float dutyCycle, int darkFrames, boolean bfiActive) {
            this.emittedNits = emittedNits;
            this.dutyCycle = dutyCycle;
            this.darkFrames = darkFrames;
            this.bfiActive = bfiActive;
        }
    }

    /** Resolve from a PreferenceConfiguration snapshot + cadence. */
    public static Result resolve(
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

    /** Resolve from individual parameters (avoids prefConfig mutation). */
    public static Result resolve(
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
            duty = dutyCycle(true, 1, 1 + safeDarkFrames);
            emitted = emittedWhiteNits(
                    targetPerceivedNits, true, 1, 1 + safeDarkFrames, maxEmittedNits);
        } else {
            duty = 1.0f;
            emitted = targetPerceivedNits;
        }
        return new Result(emitted, duty, safeDarkFrames, bfiActive);
    }

    /** Visible duty cycle for a BFI pattern. 1.0 when off or degenerate. */
    private static float dutyCycle(boolean bfiEnabled, int visibleSlots, int totalSlots) {
        if (!bfiEnabled) return 1.0f;
        if (visibleSlots <= 0 || totalSlots <= 0) return 1.0f;
        return Math.min(1.0f, (float) visibleSlots / (float) totalSlots);
    }

    /**
     * Emitted white nits needed to achieve target perceived brightness
     * under BFI, clamped to maxEmittedWhiteNits.
     */
    private static int emittedWhiteNits(
            int targetPerceivedWhiteNits,
            boolean bfiEnabled,
            int visibleSlots,
            int totalSlots,
            int maxEmittedWhiteNits) {
        float duty = dutyCycle(bfiEnabled, visibleSlots, totalSlots);
        int emitted = Math.round(targetPerceivedWhiteNits / duty);
        return Math.min(emitted, maxEmittedWhiteNits);
    }
}
