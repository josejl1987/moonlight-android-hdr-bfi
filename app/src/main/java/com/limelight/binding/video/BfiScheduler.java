package com.limelight.binding.video;

/**
 * BFI scheduler following RetroArch's {@code video_black_frame_insertion} +
 * {@code video_bfi_dark_frames} pair.
 *
 * <p>The cadence is one visible refresh followed by {@code darkFrames} black
 * refreshes, so the full cycle length is {@code 1 + darkFrames}. On 120 Hz
 * with one dark frame that gives visible / black / visible / black. The
 * BFI brightness-compensation trick lives in the renderer, not here: the
 * renderer keeps the shader uniform raw and only adjusts the reported visible
 * brightness for status/logging.</p>
 */
public final class BfiScheduler {
    private boolean enabled;
    private int darkFrames = 1;
    private int phase = 0;

    public boolean canEnable(float streamFps, float displayHz, int darkFrames) {
        if (streamFps <= 0.0f || displayHz <= 0.0f) {
            return false;
        }
        int clampedDarkFrames = Math.max(1, darkFrames);
        float requiredHz = streamFps * (1.0f + clampedDarkFrames);
        return Math.abs(displayHz - requiredHz) <= 3.0f;
    }

    public void configure(boolean enabled, int darkFrames) {
        this.enabled = enabled;
        this.darkFrames = Math.max(1, darkFrames);
        this.phase = 0;
        if (!enabled) {
            phase = 0;
        }
    }

    public boolean nextIsBlack() {
        if (!enabled) {
            return false;
        }
        boolean black = phase != 0;
        phase = (phase + 1) % (1 + darkFrames);
        return black;
    }

    public void resetToVisiblePhase() {
        phase = 0;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getDarkFrames() {
        return darkFrames;
    }
}
