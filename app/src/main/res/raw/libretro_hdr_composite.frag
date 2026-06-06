#version 300 es
// SPDX-License-Identifier: MIT
//
// Ported from RetroArch's gfx/drivers/vulkan_shaders/hdr.frag
// GLES 3.0 adaptation: Vulkan UBO block -> individual uniforms.

precision highp float;

in vec2 vTexCoord;
out vec4 FragColor;

uniform sampler2D Source;

#include "libretro_hdr_common.glsl"

const vec4 kDefaultColor = vec4(1.0);

vec3 Sample(vec2 texcoord)
{
   vec4 sdr = texture(Source, texcoord);
   return pow(abs(sdr.rgb), vec3(2.4));
}

vec4 Sample(vec4 colour, vec2 texcoord)
{
   vec4 sdr = colour * texture(Source, texcoord);
   vec3 sdr_linear = pow(abs(sdr.rgb), vec3(2.4));
   return vec4(sdr_linear, sdr.a);
}

vec3 To2020(const vec3 sdr_linear)
{
   vec3 result;

   if(ExpandGamut == 0)
   {
      result = sdr_linear * k709to2020;
   }
   else if(ExpandGamut == 1)
   {
      result = sdr_linear * kExpanded709to2020;
   }
   else if(ExpandGamut == 2)
   {
      result = sdr_linear * kP3to2020;
   }
   else
   {
      result = sdr_linear;
   }

   return max(result, vec3(0.0));
}

vec4 To2020(const vec4 sdr_linear)
{
   return vec4(To2020(sdr_linear.rgb), sdr_linear.a);
}

vec3 HDR(const vec3 sdr_linear)
{
   return ApplyInverseTonemap(sdr_linear, BrightnessNits, BrightnessNits);
}

vec4 HDR(const vec4 sdr_linear)
{
   return vec4(HDR(sdr_linear.rgb), sdr_linear.a);
}

vec3 ApplyHDR10(const vec3 hdr_linear)
{
   vec3 pq_input = hdr_linear * vec3(BrightnessNits / kMaxNitsFor2084);
   return LinearToST2084(max(pq_input, vec3(0.0)));
}

vec4 ApplyHDR10(const vec4 hdr_linear)
{
   return vec4(ApplyHDR10(hdr_linear.rgb), hdr_linear.a);
}

void main()
{
   if(HDRMode == 2)
   {
      vec4 linear = To2020(Sample(kDefaultColor, vTexCoord));
      linear.rgb = linear.rgb * k2020to709;
      linear.rgb *= BrightnessNits / kscRGBWhiteNits;
      FragColor = linear;
   }
   else if((InverseTonemap > 0.0) && (HDR10 > 0.0))
   {
      FragColor = ApplyHDR10(HDR(To2020(Sample(kDefaultColor, vTexCoord))));
   }
   else if(InverseTonemap > 0.0)
   {
      FragColor = HDR(To2020(Sample(kDefaultColor, vTexCoord)));
   }
   else if(HDR10 > 0.0)
   {
      FragColor = ApplyHDR10(To2020(Sample(kDefaultColor, vTexCoord)));
   }
   else
   {
      FragColor = texture(Source, vTexCoord);
   }
}
