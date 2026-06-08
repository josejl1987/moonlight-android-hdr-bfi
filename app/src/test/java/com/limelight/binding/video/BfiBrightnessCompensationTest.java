package com.limelight.binding.video;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link BfiBrightnessCompensation}.
 *
 * <p>These tests verify the first-order energy model:
 * <pre>{@code
 *   emittedWhiteNits = targetPerceivedWhiteNits
 *                      / (visibleSlots / totalSlots)
 * }</pre>
 *
 * clamped to {@code maxEmittedWhiteNits}.
 */
public class BfiBrightnessCompensationTest {

    // ---- dutyCycle ----

    @Test
    public void dutyCycle_isFullWhenBfiOff() {
        assertEquals(1.0f, BfiBrightnessCompensation.dutyCycle(false, 1, 2), 0f);
        assertEquals(1.0f, BfiBrightnessCompensation.dutyCycle(false, 3, 1), 0f);
    }

    @Test
    public void dutyCycle_oneVisibleOneDark() {
        assertEquals(0.5f, BfiBrightnessCompensation.dutyCycle(true, 1, 2), 0f);
    }

    @Test
    public void dutyCycle_oneVisibleTwoDark() {
        assertEquals(1f / 3f, BfiBrightnessCompensation.dutyCycle(true, 1, 3), 1e-6f);
    }

    @Test
    public void dutyCycle_twoVisibleFourTotal() {
        // RT4K-like Blur = 2, output slots = 4
        assertEquals(0.5f, BfiBrightnessCompensation.dutyCycle(true, 2, 4), 0f);
    }

    @Test
    public void dutyCycle_ignoresDegenerateParams() {
        assertEquals(1.0f, BfiBrightnessCompensation.dutyCycle(true, 0, 2), 0f);
        assertEquals(1.0f, BfiBrightnessCompensation.dutyCycle(true, 1, 0), 0f);
    }

    // ---- emittedWhiteNits ----

    @Test
    public void bfiOff_doesNotBoost() {
        int result = BfiBrightnessCompensation.emittedWhiteNits(200, false, 1, 2, 1000);
        assertEquals(200, result);
    }

    @Test
    public void oneDarkFrame_doublesVisibleWhite() {
        int result = BfiBrightnessCompensation.emittedWhiteNits(200, true, 1, 2, 1000);
        assertEquals(400, result);
    }

    @Test
    public void twoDarkFrames_triplesVisibleWhite() {
        int result = BfiBrightnessCompensation.emittedWhiteNits(200, true, 1, 3, 1000);
        assertEquals(600, result);
    }

    @Test
    public void clamp_limitsBoost() {
        int result = BfiBrightnessCompensation.emittedWhiteNits(300, true, 1, 2, 500);
        assertEquals(500, result);
    }

    @Test
    public void clamp_belowTarget_reportsMaxNotTarget() {
        // When max < target, result is the clamp, not the target.
        int result = BfiBrightnessCompensation.emittedWhiteNits(400, false, 1, 1, 300);
        assertEquals(300, result);
    }

    @Test
    public void emittedWithRoundedDivision() {
        // 200 / (1/3) = 600 (exact), but test an inexact case
        int result = BfiBrightnessCompensation.emittedWhiteNits(250, true, 1, 3, 2000);
        assertEquals(750, result);  // 250 / (1/3) = 750
    }

    @Test
    public void emittedAtMaxBoundary() {
        int result = BfiBrightnessCompensation.emittedWhiteNits(1000, false, 1, 1, 1000);
        assertEquals(1000, result);
    }

    @Test
    public void emittedWithZeroDutyProtection() {
        // When visibleSlots == 0 (degenerate), dutyCycle returns 1.0, so no boost
        int result = BfiBrightnessCompensation.emittedWhiteNits(200, true, 0, 2, 1000);
        assertEquals(200, result);
    }
}
