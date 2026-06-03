#version 300 es
// SPDX-License-Identifier: MIT
//
// Ported from RetroArch's gfx/drivers/vulkan_shaders/hdr.vert
// (c) Libretro contributors.
//
// GLES 3.0 adaptation:
//   1. in/out instead of attribute/varying.

in vec4 aPosition;
in vec2 aTexCoord;

out vec2 vTexCoord;

void main()
{
   gl_Position = aPosition;
   vTexCoord   = aTexCoord;
}
