/*
 * Client HDR composite inspired by RetroArch's Vulkan/Metal HDR pipeline.
 * The implementation reuses the same conceptual stages:
 * SDR linearization, gamut transform, inverse tonemap, HDR10/scRGB output,
 * and SDR readback tonemap. No RetroArch shader source is vendored here.
 */
#extension GL_OES_EGL_image_external : require
precision mediump float;
precision mediump samplerExternalOES;

varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;

uniform float uPaperWhiteNits;
uniform float uPeakNits;
uniform float uInverseTonemapStrength;
uniform int uExpandGamut;
uniform float uBfiActive;

vec3 srgbToLinear(vec3 c) {
    bvec3 cutoff = lessThanEqual(c, vec3(0.04045));
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(hi, lo, vec3(cutoff));
}

vec3 gamut709To2020(vec3 c) {
    mat3 m = mat3(
         0.627404, 0.069097, 0.016392,
         0.329282, 0.919540, 0.088013,
         0.043314, 0.011363, 0.895595
    );
    return c * m;
}

vec3 gamutExpanded(vec3 c) {
    float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
    vec3 expanded = (c - luma) * 1.3 + luma;
    return max(expanded, vec3(0.0));
}

vec3 gamutWide(vec3 c) {
    float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
    vec3 expanded = (c - luma) * 1.6 + luma;
    return max(expanded, vec3(0.0));
}

vec3 gamutSuper(vec3 c) {
    float luma = 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b;
    vec3 expanded = (c - luma) * 2.2 + luma;
    return max(expanded, vec3(0.0));
}

vec3 applyGamut(vec3 c, int mode) {
    if (mode == 1) return gamutExpanded(c);
    if (mode == 2) return gamutWide(c);
    if (mode == 3) return gamutSuper(c);
    return gamut709To2020(c);
}

vec3 inverseTonemap(vec3 sdrLinear, float peakNits, float paperWhiteNits, float strength) {
    float inputVal = max(max(sdrLinear.r, sdrLinear.g), sdrLinear.b);

    if (inputVal < 0.0001)
        return sdrLinear;

    float peakRatio = max(peakNits / paperWhiteNits, 1.0);
    float denominator = 1.0 - inputVal * (1.0 - (1.0 / peakRatio));
    float mapped = inputVal / max(denominator, 0.0001);

    vec3 boosted = sdrLinear * (mapped / inputVal);

    return mix(sdrLinear, boosted, clamp(strength, 0.0, 1.0));
}

void main() {
    vec4 sampled = texture2D(uTexture, vTexCoord);

    vec3 linear709 = srgbToLinear(sampled.rgb);

    vec3 gamutAdjusted = applyGamut(linear709, uExpandGamut);

    vec3 hdrLinear = inverseTonemap(gamutAdjusted, uPeakNits, uPaperWhiteNits, uInverseTonemapStrength);

    vec3 outLinear709 = hdrLinear;
    outLinear709 *= uPaperWhiteNits / 80.0;

    gl_FragColor = vec4(outLinear709, 1.0);
}
