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
}
