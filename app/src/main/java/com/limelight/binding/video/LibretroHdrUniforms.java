package com.limelight.binding.video;

/**
 * Uniform block shape for the libretro HDR composite pipeline.
 *
 * <p>This class mirrors the UBO defined in
 * {@code res/raw/libretro_hdr_common.glsl}, which is in turn a port of
 * RetroArch's {@code gfx/drivers/vulkan_shaders/hdr_common.glsl}. The field
 * names are intentionally short to keep call sites readable; the renderer is
 * responsible for mapping them onto the matching GLSL uniforms.</p>
 *
 * <p>Defaults follow RetroArch's own defaults as documented in
 * {@code config.def.h} for {@code video_hdr_mode}, {@code video_hdr_paper_white_nits},
 * and {@code video_hdr_expand_gamut}. Scanlines defaults to {@code 0} on
 * Artemis because the CRT-mask branch is not yet wired up; flip it on once
 * a deliberate CRT-shader path is introduced.</p>
 */
public final class LibretroHdrUniforms {
    public static final int HDR_MODE_OFF         = 0;
    public static final int HDR_MODE_HDR10       = 1;
    public static final int HDR_MODE_SCRGB       = 2;
    public static final int HDR_MODE_PQ_TO_SCRGB = 3;

    public static final int GAMUT_ACCURATE = 0;
    public static final int GAMUT_BT2020   = 1;
    public static final int GAMUT_P3       = 2;
    public static final int GAMUT_EXPANDED = 3;

    public static final int SUBPIXEL_RGB   = 0;
    public static final int SUBPIXEL_BGR   = 1;

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
