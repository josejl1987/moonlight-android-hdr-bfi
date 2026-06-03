package com.limelight.binding.video;

public final class BfiScheduler {
    private boolean enabled;
    private boolean blackPhase;

    public boolean canEnable(float streamFps, float displayHz) {
        return Math.abs(displayHz - streamFps * 2.0f) <= 3.0f;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            blackPhase = false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean nextIsBlack() {
        if (!enabled) return false;
        blackPhase = !blackPhase;
        return blackPhase;
    }

    public void resetToVisiblePhase() {
        blackPhase = false;
    }
}
