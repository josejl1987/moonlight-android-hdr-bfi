package com.limelight.binding.video;

public final class BfiScheduler {
    private boolean enabled;
    private int darkFrames = 1;
    private int phase;

    public boolean canEnable(float streamFps, float displayHz, int darkFrames) {
        if (streamFps <= 0f || displayHz <= 0f) {
            return false;
        }

        int clampedDarkFrames = Math.max(1, darkFrames);
        float requiredHz = streamFps * (1f + clampedDarkFrames);
        return Math.abs(displayHz - requiredHz) <= 3f;
    }

    public void configure(boolean enabled, int darkFrames) {
        this.enabled = enabled;
        this.darkFrames = Math.max(1, darkFrames);
        this.phase = 0;
    }

    public boolean nextIsBlack() {
        if (!enabled) {
            return false;
        }

        boolean black = phase != 0;
        phase = (phase + 1) % (1 + darkFrames);
        return black;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getDarkFrames() {
        return darkFrames;
    }
}
