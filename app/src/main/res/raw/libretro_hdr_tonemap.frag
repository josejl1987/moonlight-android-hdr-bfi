// SPDX-License-Identifier: MIT
//
// Ported from RetroArch's gfx/drivers/vulkan_shaders/hdr_tonemap.frag
// (c) Libretro contributors.
//
// This is a direct GLES 2.0 port of the Vulkan tonemap/readback shader.
// The only adaptations are:
//   1. The Vulkan UBO block becomes individual uniforms.
//   2. uint types become int (GLES 2.0 has no uint).
//   3. texture(Source, ...) becomes texture2D(Source, ...) for OES video.
//   4. gl_FragColor is used instead of out vec4 FragColor.
//   5. varying is used instead of in/out.

precision highp float;

varying vec2 vTexCoord;

uniform sampler2D Source;

#include "libretro_hdr_common.glsl"

/* sRGB electrical-optical transfer function (linear -> sRGB encoded bytes).
 * Needed because the readback render target is B8G8R8A8_UNORM (linear),
 * so we must apply the sRGB OETF ourselves for the saved PNG to look
 * correct in any standard sRGB viewer. */
vec3 LinearToSRGB(vec3 c)
{
   vec3 clamped = clamp(c, vec3(0.0), vec3(1.0));
   vec3 lo = clamped * 12.92;
   vec3 hi = 1.055 * pow(clamped, vec3(1.0 / 2.4)) - 0.055;
   bvec3 cutoff = lessThanEqual(clamped, vec3(0.0031308));
   return mix(hi, lo, vec3(cutoff));
}

/* Mode tag passed from vulkan_run_hdr_pipeline() for the readback pipeline:
 *   1 = backbuffer is HDR10 PQ (RGB10A2, BT.2020, ST.2084 encoded)
 *   2 = backbuffer is scRGB   (FP16, BT.709 linear, 1.0 == 80 nits)
 * Any other value falls through to a passthrough path (e.g. if the
 * backbuffer is already SDR). */

void main()
{
   vec4 source = texture2D(Source, vTexCoord);
   vec3 sdr_linear;

   if (HDRMode == 1)
   {
      /* HDR10 PQ: decode PQ -> linear BT.709, then inverse-of-inverse-tonemap
       * back down to SDR linear using the same paper_white the forward pass used. */
      vec3 hdr_linear = HDR10ToLinear(source.rgb);
      sdr_linear      = Tonemap(hdr_linear,
                                BrightnessNits,
                                BrightnessNits);
   }
   else if (HDRMode == 2)
   {
      /* scRGB: already linear BT.709, but scaled such that 1.0 = 80 nits
       * and the SDR UI sits at paper_white_nits. Undo that scaling so SDR
       * paper white maps back to 1.0. scRGB can carry negative / >1 values
       * for out-of-gamut / super-white content; LinearToSRGB will clamp. */
      sdr_linear = source.rgb * (kscRGBWhiteNits / BrightnessNits);
   }
   else
   {
      /* Passthrough: backbuffer is already SDR linear (shouldn't happen on
       * the HDR readback path, but keeps the shader well-defined). */
      sdr_linear = source.rgb;
   }

   /* B8G8R8A8_UNORM target — apply sRGB OETF so the PNG looks right. */
   gl_FragColor = vec4(LinearToSRGB(sdr_linear), 1.0);
}
