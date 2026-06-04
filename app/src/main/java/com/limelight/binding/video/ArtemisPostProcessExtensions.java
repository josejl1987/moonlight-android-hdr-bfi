package com.limelight.binding.video;

/**
 * Artemis-specific post-process extensions layered on top of the libretro HDR
 * pipeline. These are NOT part of the RetroArch/libretro behavior — they are
 * client-side additions that modify the libretro state before it is uploaded
 * to the shader.
 *
 * <p>Specifically:</p>
 * <ul>
 *   <li>{@code bfiBrightnessCompensation} — multiplies the visible-phase
 *       {@code BrightnessNits} uniform by {@code 1 + darkFrames} (or
 *       {@code min(1 + darkFrames, 1.6)} for conservative mode) so BFI doesn't
 *       appear dimmer. Libretro itself never mutates BrightnessNits this way.</li>
 *   <li>{@code forcePostProcess} — the renderer mode preference (Off / Auto /
 *       Force). The libretro pipeline doesn't gate on a user "force enable".</li>
 *   <li>{@code androidEglFallback} — set when the requested EGL mode (scRGB or
 *       HDR10) was unavailable and the renderer fell back to Display P3 or
 *       SDR. Recorded here so the log can make the fallback explicit.</li>
 * </ul>
 *
 * <p>The renderer keeps the libretro struct ({@link LibretroHdrUniforms}) and
 * the Artemis extension layer as two distinct fields, and the BrightnessNits
 * uniform upload uses the computed {@link #visibleBrightnessNits} (libretro
 * value after Artemis compensation). Logs distinguish the two layers.</p>
 */
public final class ArtemisPostProcessExtensions {
    public int bfiBrightnessCompensation = 0;
    public int forcePostProcess = 0;
    public boolean androidEglFallback = false;

    public float visibleBrightnessNits = 200.0f;
}
