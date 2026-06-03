#extension GL_OES_EGL_image_external : require
precision highp float;
precision highp samplerExternalOES;

varying vec2 vTexCoord;
uniform samplerExternalOES uTexture;

uniform float uPaperWhiteNits;
uniform float uPeakNits;
uniform float uInverseTonemapStrength;
uniform int uOutputMode;

vec3 srgbToLinear(vec3 c) {
    bvec3 cutoff = lessThanEqual(c, vec3(0.04045));
    vec3 lo = c / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(hi, lo, vec3(cutoff));
}

vec3 linearToSrgb(vec3 c) {
    bvec3 cutoff = lessThanEqual(c, vec3(0.0031308));
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(hi, lo, vec3(cutoff));
}

vec3 inverseTonemap(vec3 sdrLinear, float peakNits, float paperWhiteNits, float strength) {
    float inputVal = max(max(sdrLinear.r, sdrLinear.g), sdrLinear.b);
    if (inputVal < 0.0001) return sdrLinear;
    float peakRatio = max(peakNits / paperWhiteNits, 1.0);
    float denominator = 1.0 - inputVal * (1.0 - (1.0 / peakRatio));
    float mapped = inputVal / max(denominator, 0.0001);
    vec3 boosted = sdrLinear * (mapped / inputVal);
    return mix(sdrLinear, boosted, clamp(strength, 0.0, 1.0));
}

void main() {
    vec4 sampled = texture2D(uTexture, vTexCoord);
    vec3 linear709 = srgbToLinear(sampled.rgb);

    if (uOutputMode == 0) {
        gl_FragColor = vec4(linearToSrgb(linear709), 1.0);
        return;
    }

    vec3 boosted709 = inverseTonemap(linear709, uPeakNits, uPaperWhiteNits, uInverseTonemapStrength);
    gl_FragColor = vec4(boosted709 * (uPaperWhiteNits / 80.0), 1.0);
}
