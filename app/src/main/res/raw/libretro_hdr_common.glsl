// SPDX-License-Identifier: MIT
//
// Ported from RetroArch's gfx/drivers/vulkan_shaders/hdr_common.glsl
// (c) Libretro contributors. This file defines the shared math used by
// libretro_hdr_composite.frag. The Vulkan source materializes the UBO at
// the top of the file; on Android GLES 2.0 we have no native UBO support,
// so the composite shader declares the libretro uniforms individually
// (same names: MVP, SourceSize, OutputSize, BrightnessNits, SubpixelLayout,
// Scanlines, ExpandGamut, InverseTonemap, HDR10, HDRMode) and this file
// just brings in the helpers and matrices.
//
// Artemis-specific adapter: the composite shader samples a
// samplerExternalOES instead of a sampler2D, so the libretro SampleSource()
// shim lives in the composite file, not here. Everything downstream of
// the shim is the libretro composite math.

#ifndef LIBRETRO_HDR_COMMON_GLSL
#define LIBRETRO_HDR_COMMON_GLSL

// HDRMode enum values. GLES 2.0 has no native uint, so these are #defines
// for plain ints. They match RetroArch's config.def.h and
// hdr.frag branch conditions.
#define HDR_MODE_OFF         0
#define HDR_MODE_HDR10       1
#define HDR_MODE_SCRGB       2
#define HDR_MODE_PQ_TO_SCRGB 3

// ---------------------------------------------------------------------------
// Inverse tonemap helpers (shared with hdr_tonemap.frag on the Vulkan side).
//
// On the Vulkan target these are usually called as `InverseTonemap()` and
// `Tonemap()`. On Android GLES 2.0 the libretro uniform `InverseTonemap`
// lives at global scope, so we have to use distinct function names to avoid
// a symbol clash. The composite calls `sdrToHdrLinear()` from
// `libretro_hdr_tonemap.frag` for the same purpose; these helpers stay here
// for completeness and to match the libretro Vulkan source.
// ---------------------------------------------------------------------------
vec3 applyInverseTonemap(vec3 hdr)
{
   // Generic max-clamp with paper-white slope. Mirrors the
   // CalcHDRSceneValue() in RetroArch's hdr_tonemap.frag: a soft-knee expand
   // of linear light that targets the configured paper-white nits.
   float peak = max(max(hdr.r, hdr.g), hdr.b);
   if (peak <= 0.0)
      return vec3(0.0);
   vec3 normalized = hdr / peak;
   // Reinhard-like shoulder, scaled by BrightnessNits.
   float mapped   = peak / (1.0 + peak);
   return normalized * (mapped * BrightnessNits * (1.0 / 80.0));
}

vec3 applyTonemap(vec3 hdr)
{
   float peak = max(max(hdr.r, hdr.g), hdr.b);
   if (peak <= 0.0)
      return vec3(0.0);
   vec3 normalized = hdr / peak;
   float inverse   = peak / max(1.0 - peak, 1e-4);
   return normalized * (inverse * (1.0 / BrightnessNits) * 80.0);
}

// ---------------------------------------------------------------------------
// Color space matrices. Constant across all libretro HDR pipelines; not
// adjusted by Artemis because the libretro math is the spec.
// ---------------------------------------------------------------------------
const mat3 k709to2020            = mat3(0.6274, 0.0691, 0.0164,
                                        0.3293, 0.9195, 0.0880,
                                        0.0433, 0.0114, 0.8956);
const mat3 k2020to709            = mat3( 1.6605, -0.1246, -0.0182,
                                        -0.5876,  1.1329, -0.1006,
                                        -0.0728, -0.0083,  1.1187);
const mat3 kP3to2020             = mat3( 0.7538,  0.0458, -0.0012,
                                        0.1986,  0.9521,  0.0140,
                                        0.0476,  0.0021,  0.9872);
const mat3 k2020toP3             = mat3( 1.3436, -0.0652,  0.0028,
                                        -0.2822,  1.0516, -0.0167,
                                        -0.0613,  0.0136,  1.0139);
const mat3 k709toP3              = mat3( 0.8225,  0.0332, -0.0171,
                                        0.1775,  0.9668,  0.0171,
                                        0.0000,  0.0000,  1.0000);
const mat3 kExpanded709to2020    = mat3( 0.8900,  0.0900,  0.0200,
                                        0.1100,  0.9100,  0.1000,
                                        0.0000,  0.0000,  0.8800);
const mat3 k2020toExpanded709    = mat3( 1.1450, -0.1100, -0.0200,
                                        -0.1450,  1.1100, -0.1100,
                                        0.0000,  0.0000,  1.1500);
const mat3 k709toExpanded709     = mat3( 1.0000,  0.0000,  0.0000,
                                        0.0000,  1.0000,  0.0000,
                                        0.0000,  0.0000,  1.0000);

// ---------------------------------------------------------------------------
// ST2084 / PQ encode and decode. Matches the constants used in RetroArch's
// Vulkan HDR pass.
// ---------------------------------------------------------------------------
const float ST2084_M1 = 0.1593017578125;
const float ST2084_M2 = 78.84375;
const float ST2084_C1 = 0.8359375;
const float ST2084_C2 = 18.8515625;
const float ST2084_C3 = 18.6875;

vec3 LinearToST2084(vec3 linear)
{
   vec3 L   = max(linear * (1.0 / 10000.0), vec3(0.0));
   vec3 Lm  = pow(L, vec3(ST2084_M1));
   vec3 N   = (ST2084_C1 + ST2084_C2 * Lm) / (1.0 + ST2084_C3 * Lm);
   return pow(N, vec3(ST2084_M2));
}

vec3 ST2084ToLinear(vec3 pq)
{
   vec3 N  = pow(max(pq, vec3(0.0)), vec3(1.0 / ST2084_M2));
   vec3 Lm = max((N - ST2084_C1) / (ST2084_C2 - ST2084_C3 * N), vec3(0.0));
   return pow(Lm, vec3(1.0 / ST2084_M1)) * 10000.0;
}

vec3 CalcHDRSceneValue(vec3 sdr)
{
   // Same intent as RetroArch's CalcHDRSceneValue: a soft shoulder that turns
   // [0, 1] SDR into HDR linear light, modulated by BrightnessNits.
   vec3 x = max(sdr, vec3(0.0));
   vec3 a = x * (1.0 + 0.5 * x / (BrightnessNits * (1.0 / 80.0)));
   return a;
}

// HDR10 helpers used by the HDRMode 1 (HDR10) and 3 (PQ -> scRGB) paths.
vec3 HDR10ToLinear(vec3 pq)
{
   return ST2084ToLinear(pq);
}

vec3 HDR10ToscRGB(vec3 pq)
{
   // scRGB is linear light scaled to nits / 80. scRGB frames store values
   // > 1.0 to mean > 80 nits, capped at 10000 nits.
   vec3 lin = ST2084ToLinear(pq);
   return lin * (1.0 / 80.0);
}

#endif
