package com.limelight.binding.video;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BfiSchedulerTest {
    @Test
    public void canEnableMatchesDisplayRefreshForMultipleDarkFrames() {
        BfiScheduler scheduler = new BfiScheduler();
        scheduler.configure(true, 1);

        assertTrue(scheduler.canEnable(60f, 120f, 1));
        assertFalse(scheduler.canEnable(60f, 180f, 1));
        assertTrue(scheduler.canEnable(59.94f, 120f, 1));

        scheduler.configure(true, 2);
        assertTrue(scheduler.canEnable(60f, 180f, 2));
        assertFalse(scheduler.canEnable(60f, 120f, 2));
    }

    @Test
    public void nextIsBlackRepeatsConfiguredDarkFrameCount() {
        BfiScheduler scheduler = new BfiScheduler();
        scheduler.configure(true, 2);

        assertFalse(scheduler.nextIsBlack()); // visible
        assertTrue(scheduler.nextIsBlack());  // black
        assertTrue(scheduler.nextIsBlack());  // black
        assertFalse(scheduler.nextIsBlack()); // visible
    }

    @Test
    public void isFrameStalledIsFalseOnFreshScheduler() {
        BfiScheduler scheduler = new BfiScheduler();
        // No recordFrameArrival() call yet; even after evaluateStall the
        // result should be false because the short-circuit requires
        // lastFrameArrivalNs > 0.
        scheduler.evaluateStall(1_000_000_000L, 16_666_667L);
        assertFalse(scheduler.isFrameStalled());
    }

    @Test
    public void isFrameStalledFlipsTrueAfterGapExceedsTimeout() {
        BfiScheduler scheduler = new BfiScheduler();
        long arrival = 1_000_000_000L;
        long timeout = 16_666_667L; // ~1 vsync at 60 Hz
        scheduler.recordFrameArrival(arrival);
        // Just past the timeout -> stalled
        scheduler.evaluateStall(arrival + timeout + 1L, timeout);
        assertTrue(scheduler.isFrameStalled());
    }

    @Test
    public void isFrameStalledClearsOnNextFrameArrival() {
        BfiScheduler scheduler = new BfiScheduler();
        long arrival1 = 1_000_000_000L;
        long timeout = 16_666_667L;
        scheduler.recordFrameArrival(arrival1);
        scheduler.evaluateStall(arrival1 + timeout + 1L, timeout);
        assertTrue(scheduler.isFrameStalled());

        // A new arrival resets both the timestamp and the boolean.
        long arrival2 = arrival1 + timeout + 100L;
        scheduler.recordFrameArrival(arrival2);
        // evaluateStall at the same instant reports false.
        scheduler.evaluateStall(arrival2, timeout);
        assertFalse(scheduler.isFrameStalled());
    }
}
