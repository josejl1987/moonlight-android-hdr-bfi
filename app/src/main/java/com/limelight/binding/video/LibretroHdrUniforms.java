package com.limelight.binding.video;

/**
 * libretro HDR uniform struct — the resolved state uploaded to the GLSL
 * composite and tonemap shaders. Mirrors the UBO defined in
 * {@code res/raw/libretro_hdr_common.glsl}, which is a port of RetroArch's
 * {@code gfx/drivers/vulkan_shaders/hdr_common.glsl}.
 *
 * <p>Field names match the libretro spec exactly so call sites stay readable;
 * the renderer is responsible for mapping them onto the matching GLSL
 * uniforms. Defaults follow RetroArch's own defaults as documented in
 * {@code config.def.h} for {@code video_hdr_mode},
 * {@code video_hdr_paper_white_nits}, and {@code video_hdr_expand_gamut}.</p>
 *
 * <p>This struct holds <em>libretro state only</em>. Artemis-specific
 * post-process extensions (BFI brightness compensation, force post-process
 * enable, EGL fallback tracking) live in
 * {@link ArtemisPostProcessExtensions} so the libretro math stays
 * untouched.</p>
 */
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
