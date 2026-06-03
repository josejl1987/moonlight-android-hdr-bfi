package com.limelight.binding.video;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BfiSchedulerTest {
    @Test
    public void canEnableMatchesDisplayRefreshForMultipleDarkFrames() {
        BfiScheduler scheduler = new BfiScheduler();
        scheduler.configure(true, 1);

        assertTrue(scheduler.canEnable(60f, 120f));
        assertFalse(scheduler.canEnable(60f, 180f));

        scheduler.configure(true, 2);
        assertTrue(scheduler.canEnable(60f, 180f));
        assertFalse(scheduler.canEnable(60f, 120f));
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
}
