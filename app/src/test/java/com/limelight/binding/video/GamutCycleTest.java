package com.limelight.binding.video;

import org.junit.Test;

import static com.limelight.binding.video.LibretroHdrUniforms.GAMUT_ACCURATE;
import static com.limelight.binding.video.LibretroHdrUniforms.GAMUT_EXPANDED;
import static com.limelight.binding.video.LibretroHdrUniforms.GAMUT_SUPER;
import static com.limelight.binding.video.LibretroHdrUniforms.GAMUT_WIDE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class GamutCycleTest {
    @Test
    public void nextAdvancesAccurateThroughExpandedAndWide() {
        assertEquals(GAMUT_EXPANDED, GamutCycle.next(GAMUT_ACCURATE));
        assertEquals(GAMUT_WIDE,     GamutCycle.next(GAMUT_EXPANDED));
    }

    @Test
    public void nextWrapsSuperBackToAccurate() {
        assertEquals(GAMUT_ACCURATE, GamutCycle.next(GAMUT_SUPER));
    }

    @Test
    public void nameReturnsNonEmptyLabelForEveryKnownValue() {
        assertEquals("Accurate", GamutCycle.name(GAMUT_ACCURATE));
        assertEquals("Expanded", GamutCycle.name(GAMUT_EXPANDED));
        assertEquals("Wide",     GamutCycle.name(GAMUT_WIDE));
        assertEquals("Super",    GamutCycle.name(GAMUT_SUPER));
    }

    @Test
    public void nameReturnsUnknownForUnrecognisedValue() {
        assertEquals("Unknown", GamutCycle.name(99));
        // Sanity: ensure we did not accidentally return an empty string.
        assertFalse("Unknown".isEmpty());
    }
}
