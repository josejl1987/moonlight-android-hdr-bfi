package com.limelight.binding.video;

import com.limelight.binding.video.LibretroHdrUniforms;

/**
 * Pure helper for the live HDR gamut hot-toggle. Wraps a 4-state cycle
 * ({@code ACCURATE -> EXPANDED -> WIDE -> SUPER -> ACCURATE}) so the
 * special-keys menu can cycle through gamut modes without exposing
 * raw enum values to the UI layer.
 *
 * <p>All methods are {@code static} and side-effect free; the class is
 * non-instantiable.</p>
 */
public final class GamutCycle {
    private GamutCycle() {}

    /**
     * Returns the next gamut value, wrapping {@link LibretroHdrUniforms#GAMUT_SUPER}
     * back to {@link LibretroHdrUniforms#GAMUT_ACCURATE}.
     */
    public static int next(int current) {
        int n = current + 1;
        if (n > LibretroHdrUniforms.GAMUT_SUPER) {
            n = LibretroHdrUniforms.GAMUT_ACCURATE;
        }
        return n;
    }

    /**
     * Human-readable label for a gamut value. Returns {@code "Unknown"}
     * for values outside the documented enum range.
     */
    public static String name(int gamut) {
        switch (gamut) {
            case LibretroHdrUniforms.GAMUT_ACCURATE: return "Accurate";
            case LibretroHdrUniforms.GAMUT_EXPANDED: return "Expanded";
            case LibretroHdrUniforms.GAMUT_WIDE:     return "Wide";
            case LibretroHdrUniforms.GAMUT_SUPER:    return "Super";
            default: return "Unknown";
        }
    }
}
