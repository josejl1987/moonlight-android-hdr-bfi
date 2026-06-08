package com.limelight.binding.video;

/**
 * BFI brightness compensation: scale visible-frame luminance so the
 * perceived (time-integrated) brightness equals the target, compensating
 * for the black (or dimmed) intervals that BFI inserts.
 *
 * <p><b>Model</b></p>
 *
 * The eye integrates luminance over a complete BFI cycle.  The cycle
 * occupies {@code totalSlots} output-refresh slots per source frame;
 * the image is visible for {@code visibleSlots} of those slots and
 * black/dim for the remainder.  To preserve perceived brightness the
 * visible phase must emit proportionally more light:
 *
 * <pre>{@code
 *   dutyCycle = visibleSlots / totalSlots
 *   emittedWhiteNits = targetPerceivedWhiteNits / dutyCycle
 * }</pre>
 *
 * <p>This maps to the model used by RetroTINK-4K (Strobe/Blur):</p>
 * <pre>{@code
 *   totalSlots   = displayHz / sourceFps            (RT4K output slots)
 *   visibleSlots = blur duration in output slots    (RT4K Blur)
 * }</pre>
 *
 * <p>For the simple hard-BFI case (one visible frame followed by
 * {@code darkFrames} black frames):</p>
 * <pre>{@code
 *   totalSlots   = 1 + darkFrames
 *   visibleSlots = 1
 * }</pre>
 *
 * <p><b>Safety</b></p>
 *
 * A real panel may not reach the computed emitted nits (ABL, tone
 * mapping, peak brightness limit).  The caller supplies a
 * {@code maxEmittedWhiteNits} clamp that caps the result.
 *
 * @see BfiScheduler
 */
public final class BfiBrightnessCompensation {
    private BfiBrightnessCompensation() {}

    /**
     * Returns the visible duty cycle for a BFI pattern.
     *
     * @param bfiEnabled   whether BFI is active
     * @param visibleSlots number of output-refresh slots the image is visible (≥&nbsp;1)
     * @param totalSlots   total output-refresh slots per source frame (≥&nbsp;visibleSlots)
     * @return duty cycle in (0..1], or 1.0 when BFI is off or parameters are degenerate
     */
    public static float dutyCycle(boolean bfiEnabled, int visibleSlots, int totalSlots) {
        if (!bfiEnabled) return 1.0f;
        if (visibleSlots <= 0 || totalSlots <= 0) return 1.0f;
        return Math.min(1.0f, (float) visibleSlots / (float) totalSlots);
    }

    /**
     * Computes the emitted (visible-frame) white nits needed to achieve the
     * target perceived brightness under BFI, clamped to a safe maximum.
     *
     * @param targetPerceivedWhiteNits desired perceived SDR paper white (nits, ≥&nbsp;1)
     * @param bfiEnabled               whether BFI is active
     * @param visibleSlots             number of visible output-refresh slots (≥&nbsp;1)
     * @param totalSlots               total output-refresh slots per source frame
     * @param maxEmittedWhiteNits      safety clamp (nits, ≥&nbsp;targetPerceivedWhiteNits)
     * @return emitted white nits for the visible frame, ∈&nbsp;[target, maxEmittedWhiteNits]
     */
    public static int emittedWhiteNits(
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
