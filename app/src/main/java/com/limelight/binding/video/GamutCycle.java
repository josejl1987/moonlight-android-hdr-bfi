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
     *
     * <p>Uses explicit transitions so reordering or gaps in the enum constants
     * do not silently break the cycle.</p>
     */
    public static int next(int current) {
        switch (current) {
            case LibretroHdrUniforms.GAMUT_ACCURATE: return LibretroHdrUniforms.GAMUT_EXPANDED;
            case LibretroHdrUniforms.GAMUT_EXPANDED: return LibretroHdrUniforms.GAMUT_WIDE;
            case LibretroHdrUniforms.GAMUT_WIDE:     return LibretroHdrUniforms.GAMUT_SUPER;
            case LibretroHdrUniforms.GAMUT_SUPER:    return LibretroHdrUniforms.GAMUT_ACCURATE;
            default: return LibretroHdrUniforms.GAMUT_ACCURATE;
        }
    }

    /**
     * Human-readable label for a gamut value. Returns {@code "Unknown"}
     * for values outside the documented enum range.
     */
    public static String name(int gamut) {
        switch (gamut) {
            case LibretroHdrUniforms.GAMUT_ACCURATE: return "Rec.709 accurate";
            case LibretroHdrUniforms.GAMUT_EXPANDED: return "Rec.709 \u2192 P3 expansion";
            case LibretroHdrUniforms.GAMUT_WIDE:     return "Rec.709 \u2192 BT.2020 expansion";
            case LibretroHdrUniforms.GAMUT_SUPER:    return "Oversaturation debug";
            default: return "Unknown";
        }
    }
}
