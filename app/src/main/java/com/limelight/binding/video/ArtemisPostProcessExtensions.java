package com.limelight.binding.video;

/**
 * Artemis-only state kept separate from the libretro HDR uniforms.
 */
public final class ArtemisPostProcessExtensions {
    public int bfiBrightnessCompensation;
    public int forcePostProcess;
    public boolean androidEglFallback;
    public float visibleBrightnessNits = 200.0f;
}
