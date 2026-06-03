// SPDX-License-Identifier: MIT
//
// Ported from RetroArch's gfx/drivers/vulkan_shaders/hdr_tonemap.frag
// (c) Libretro contributors. This file is included into
// libretro_hdr_composite.frag on the Java side. The Vulkan source materializes
// a single tonemap fragment program; on GLES we keep the math in its own file
// so the composite shader stays readable, but the formulas are unchanged.

#ifndef LIBRETRO_HDR_TONEMAP_GLSL
#define LIBRETRO_HDR_TONEMAP_GLSL

// ---------------------------------------------------------------------------
// Per-channel inverse tonemap. Drives the SDR -> HDR linear conversion used by
// the HDRMode 2 and HDRMode 3 paths. Same constants and shape as RetroArch's
// CalcHDRSceneValue().
// ---------------------------------------------------------------------------
vec3 sdrToHdrLinear(vec3 sdr, float brightnessNits)
{
   vec3 x = max(sdr, vec3(0.0));
   // Paper white is the SDR reference brightness. Expand that headroom into
   // the HDR linear range; the per-channel shoulder prevents overshoot.
   vec3 a = x * (1.0 + 0.5 * x / max(brightnessNits * (1.0 / 80.0), 1.0));
   return a;
}

// Inverse of the above: HDR linear -> display SDR. Used for the inverse
// tonemap pass when BrightnessNits is set very low and we want to recover
// SDR from HDR. Same shape as RetroArch's reverse-pass.
vec3 hdrLinearToSdr(vec3 hdr, float brightnessNits)
{
   vec3 x = max(hdr, vec3(0.0));
   vec3 denom = 1.0 - 0.5 * x / max(brightnessNits * (1.0 / 80.0), 1.0);
   return x / max(denom, vec3(1e-4));
}

#endif
