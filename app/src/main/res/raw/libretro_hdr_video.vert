// SPDX-License-Identifier: MIT
//
// Ported from RetroArch's gfx/drivers/vulkan_shaders/hdr.vert
// (c) Libretro contributors.
//
// This is a direct GLES 2.0 port of the Vulkan vertex shader.
// The only adaptations are:
//   1. attribute is used instead of layout(location = ...).
//   2. varying is used instead of out.

attribute vec4 aPosition;
attribute vec2 aTexCoord;

varying vec2 vTexCoord;

void main()
{
   gl_Position = aPosition;
   vTexCoord   = aTexCoord;
}
