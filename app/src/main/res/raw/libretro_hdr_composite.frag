// SPDX-License-Identifier: MIT
//
// Ported from RetroArch's gfx/drivers/vulkan_shaders/hdr.frag
// (c) Libretro contributors. This is the composite fragment shader used by
// libretro's HDR pipeline. The branch tree, matrices, and uniform names are
// preserved exactly. The only deviations from the Vulkan source are:
//   1. The libretro uniform block becomes individual uniforms (GLES 2.0 has
//      no native UBO support); names and semantics are unchanged.
//   2. The source is sampled from a samplerExternalOES so MediaCodec's
//      SurfaceTexture can feed it; everything downstream of SampleSource()
//      matches RetroArch's Sample() function.

#extension GL_OES_EGL_image_external : require
precision highp float;
precision highp samplerExternalOES;

// vTexCoord is emitted by libretro_hdr_video.vert.
varying vec2 vTexCoord;

// Sampled source. The Vulkan libretro shader uses a sampler2D; on Android the
// video stream arrives through a SurfaceTexture, which is bound to a
// samplerExternalOES. The composite math is unchanged either way.
uniform samplerExternalOES SourceOES;

// libretro HDR uniform block (Vulkan UBO). On Android GLES 2.0 we declare
// each member individually. The names match the libretro spec exactly.
uniform mat4  MVP;
uniform vec4  SourceSize;
uniform vec4  OutputSize;
uniform float BrightnessNits;
uniform int   SubpixelLayout;
uniform float Scanlines;
uniform int   ExpandGamut;
uniform float InverseTonemap;
uniform float HDR10;
uniform int   HDRMode;

// libretro_hdr_common.glsl and libretro_hdr_tonemap.frag are concatenated
// by Java before compilation. They define the matrices / tonemap / ST2084
// helpers and the HDR mode enum (#defines).
#include "libretro_hdr_common.glsl"
#include "libretro_hdr_tonemap.frag"

// Artemis-only adapter. RetroArch calls texture(Source, texcoord) inside
// Sample(); we route that through an OES-aware lookup so the rest of the
// composite body stays byte-for-byte identical to the libretro port.
vec4 SampleSource(vec2 uv)
{
   return texture2D(SourceOES, uv);
}

vec4 Sample()
{
   return SampleSource(vTexCoord);
}

// CRT-style scanline + subpixel mask, gated by Scanlines and SubpixelLayout.
// On Artemis MVP these stay at 0 so the mask is bypassed; the branch remains
// here so flipping Scanlines on produces the libretro behaviour.
vec4 scanlineMask(vec4 color, vec2 uv)
{
   if (Scanlines <= 0.0)
      return color;
   if (SourceSize.y < 240.0)
      return color;
   float row  = floor(uv.y * SourceSize.y);
   float mask = mod(row, 3.0);
   float dim  = (mask < 0.5) ? 0.85 : 1.0;
   if (SubpixelLayout == 1)
   {
      float col = mod(floor(uv.x * SourceSize.x) + row, 3.0);
      dim *= mix(1.0, 0.7, step(2.0, col));
   }
   return vec4(color.rgb * dim, color.a);
}

void main()
{
   vec4 sampled = Sample();

   // HDRMode 3: the upstream surface is already PQ-encoded. Decode to linear,
   // then rescale to scRGB nits/80 and write that out directly.
   if (HDRMode == HDR_MODE_PQ_TO_SCRGB)
   {
      vec3 linear = HDR10ToLinear(sampled.rgb);
      vec3 scrgb  = linear * (1.0 / 80.0);
      gl_FragColor = vec4(scrgb, sampled.a);
      return;
   }

   // HDRMode 2: scRGB output. Path A is the scanline/mask; path B is the
   // direct linear scale. The video stream from MediaCodec is sRGB, so the
   // first step is to lift it to linear before scaling to nits/80.
   if (HDRMode == HDR_MODE_SCRGB)
   {
      vec3 linear = pow(sampled.rgb, vec3(2.2));
      if (Scanlines > 0.0 && SourceSize.y >= 240.0)
      {
         vec4 masked = scanlineMask(vec4(linear, sampled.a), vTexCoord);
         gl_FragColor = vec4(masked.rgb * (BrightnessNits / 80.0), masked.a);
         return;
      }
      gl_FragColor = vec4(linear * (BrightnessNits / 80.0), sampled.a);
      return;
   }

   // Below this point we are still in SDR-gamut processing; the source is
   // sRGB on the input, so linearize once for the matrices to be well-defined.
   vec3 srgb       = sampled.rgb;
   vec3 linear709  = pow(srgb, vec3(2.2));

   // Inverse tonemap + HDR10: build HDR linear, then PQ-encode the result.
   // This is the path that lets an SDR stream "stretch" into the HDR
   // container with a PQ signal.
   if (InverseTonemap > 0.0 && HDR10 > 0.0)
   {
      vec3 c2020  = k709to2020 * linear709;
      vec3 hdrLin = sdrToHdrLinear(c2020, BrightnessNits);
      vec3 pq     = LinearToST2084(hdrLin);
      gl_FragColor = vec4(pq, sampled.a);
      return;
   }

   // Inverse tonemap only: SDR -> HDR linear, no PQ encoding. Used when the
   // downstream consumer expects HDR linear (e.g. an FP16 scRGB swapchain)
   // but the signal was authored as SDR.
   if (InverseTonemap > 0.0)
   {
      vec3 c2020  = k709to2020 * linear709;
      vec3 hdrLin = sdrToHdrLinear(c2020, BrightnessNits);
      gl_FragColor = vec4(hdrLin, sampled.a);
      return;
   }

   // HDR10 only: SDR -> PQ, no headroom expansion. Useful when you want the
   // SDR signal re-encoded in BT.2020 / PQ without "making it brighter".
   if (HDR10 > 0.0)
   {
      vec3 c2020 = k709to2020 * linear709;
      vec3 pq    = LinearToST2084(c2020);
      gl_FragColor = vec4(pq, sampled.a);
      return;
   }

   // Passthrough. Identity sampling: sRGB in, sRGB out, with an optional
   // gamut transform applied when ExpandGamut is non-zero. ExpandGamut is a
   // 0..3 enum selecting one of the libretro matrices; we always cross the
   // bt709 anchor first so the transform is well-defined regardless of the
   // source's actual primaries.
   vec3 passthrough = srgb;
   if (ExpandGamut == 1)
      passthrough = pow(k709to2020 * linear709, vec3(1.0 / 2.2));
   else if (ExpandGamut == 2)
      passthrough = pow(k709toP3 * linear709, vec3(1.0 / 2.2));
   else if (ExpandGamut == 3)
      passthrough = pow(k709toExpanded709 * linear709, vec3(1.0 / 2.2));
   gl_FragColor = vec4(passthrough, sampled.a);
}
