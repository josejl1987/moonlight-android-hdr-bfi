package com.limelight.binding.video;

/**
 * Fully resolved HDR/BFI brightness state for a single rendering context.
 *
 * <p>Produced by {@link HdrBfiBrightnessResolver} from raw
 * {@link com.limelight.preferences.PreferenceConfiguration} values plus
 * stream/display cadence.  Both the renderer and the calibration UI consume
 * this object so they display and emit the same compensated value.
 */
public final class ResolvedHdrBfiBrightness {
    /** User's target perceived SDR paper white (nits). */
    public final int targetPerceivedNits;
    /** User's safety clamp for visible-frame peak (nits). */
    public final int maxEmittedNits;
    /** Emitted white nits actually uploaded to the shader (compensated or raw). */
    public final int emittedNits;
    /** BFI duty cycle (visibleSlots / totalSlots), or 1.0 if BFI is off. */
    public final float dutyCycle;
    /** Dark-frame count after sanitization. */
    public final int darkFrames;
    /** Whether BFI is actually active (configured + cadence-valid). */
    public final boolean bfiActive;
    /** Whether the user intends HDR output (mode != OFF). */
    public final boolean hdrIntent;

    public ResolvedHdrBfiBrightness(
            int targetPerceivedNits,
            int maxEmittedNits,
            int emittedNits,
            float dutyCycle,
            int darkFrames,
            boolean bfiActive,
            boolean hdrIntent) {
        this.targetPerceivedNits = targetPerceivedNits;
        this.maxEmittedNits = maxEmittedNits;
        this.emittedNits = emittedNits;
        this.dutyCycle = dutyCycle;
        this.darkFrames = darkFrames;
        this.bfiActive = bfiActive;
        this.hdrIntent = hdrIntent;
    }
}
