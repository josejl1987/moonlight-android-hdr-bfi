package com.limelight.binding.video;

/** Resolved libretro HDR state uploaded to the GLSL shaders. */
public final class LibretroHdrUniforms {
    public static final int HDR_MODE_OFF         = 0;
    public static final int HDR_MODE_HDR10       = 1;
    public static final int HDR_MODE_SCRGB       = 2;
    public static final int HDR_MODE_PQ_TO_SCRGB = 3;

    public static final int GAMUT_ACCURATE = 0;  // Rec.709 -> Rec.2020 (proper)
    public static final int GAMUT_EXPANDED = 1;  // Expanded709 -> Rec.2020
    public static final int GAMUT_WIDE     = 2;  // P3 -> Rec.2020
    public static final int GAMUT_SUPER    = 3;  // passthrough (max boost)

    public static final int SUBPIXEL_RGB   = 0;
    public static final int SUBPIXEL_RBG   = 1;
    public static final int SUBPIXEL_BGR   = 2;

    public float[] mvp = identity4x4();

    public float sourceWidth;
    public float sourceHeight;
    public float outputWidth;
    public float outputHeight;

    public float brightnessNits   = 200.0f;
    public int   subpixelLayout   = SUBPIXEL_RGB;
    public float scanlines        = 0.0f;
    public int   expandGamut      = GAMUT_ACCURATE;
    public float inverseTonemap   = 0.0f;
    public float hdr10            = 0.0f;
    public int   hdrMode          = HDR_MODE_OFF;

    public static float[] identity4x4() {
        return new float[] {
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f,
        };
    }
}
