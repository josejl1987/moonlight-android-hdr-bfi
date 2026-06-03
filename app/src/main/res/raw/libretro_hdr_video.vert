// SPDX-License-Identifier: MIT
//
// Ported from RetroArch's gfx/drivers/vulkan_shaders/hdr.frag (vertex half).
// libretro shaders feed the vertex stage with a libretro-standard set of
// attributes (position + texcoord) and write the libretro-standard
// varyings. The Android port keeps the same names so the fragment shaders
// stay byte-for-byte identical to the libretro port.

attribute vec4 aPosition;
attribute vec2 aTexCoord;

varying vec2 vTexCoord;

void main()
{
   gl_Position = aPosition;
   vTexCoord   = aTexCoord;
}
