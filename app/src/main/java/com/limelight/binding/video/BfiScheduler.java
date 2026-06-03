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

    // Frame-arrival stall detection. Written from the render thread, read from
    // any thread (volatile). The renderer is expected to call
    // recordFrameArrival() on every successful updateTexImage() and
    // evaluateStall() on every vsync callback. The 2-vsync-interval threshold
    // is the renderer's responsibility to compute; we only carry the boolean.
    private volatile long lastFrameArrivalNs;
    private volatile boolean frameStalled;

    /**
     * Record that a frame was just consumed from the decoder. Resets the
     * stall flag. Caller passes a monotonic clock in nanoseconds
     * (e.g. {@code System.nanoTime()}).
     */
    public void recordFrameArrival(long nowNs) {
        this.lastFrameArrivalNs = nowNs;
        this.frameStalled = false;
    }

    /**
     * Compute whether the stream appears stalled given the supplied "now"
     * timestamp and a stall timeout. Short-circuits to {@code false} until
     * at least one frame has been recorded (so a freshly created renderer
     * does not report a spurious stall).
     */
    public void evaluateStall(long nowNs, long timeoutNs) {
        long last = this.lastFrameArrivalNs;
        this.frameStalled = (last > 0L) && (nowNs - last) > timeoutNs;
    }

    /**
     * Returns {@code true} if the last {@link #evaluateStall} call decided
     * the stream was stalled. Returns {@code false} on a fresh scheduler or
     * after the most recent arrival.
     */
    public boolean isFrameStalled() {
        return frameStalled;
    }

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
