#ifndef NVIDIUM_POST_TONEMAP_GLSL
#define NVIDIUM_POST_TONEMAP_GLSL

vec3 srgbToLinear(vec3 srgb) {
    return pow(max(srgb, vec3(0.0)), vec3(2.2));
}

vec3 linearToSrgb(vec3 lin) {
    return pow(max(lin, vec3(0.0)), vec3(1.0 / 2.2));
}

/**
 * AgX Default Contrast Approximation (Troy Sobotka / Blender 4.0) with Punchy Look.
 */
vec3 agxDefaultContrastApprox(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return + 15.5     * x4 * x2
           - 40.14    * x4 * x
           + 31.96    * x4
           - 6.868    * x2 * x
           + 0.4298   * x2
           + 0.1191   * x
           - 0.00232;
}

vec3 tonemapAgX(vec3 color) {
    const mat3 agxMat = mat3(
        0.842479062253094,  0.0423282422610123, 0.0423756549057051,
        0.0784335999999992, 0.878468636469772,  0.0784336,
        0.0792237451477643, 0.0791661274605434, 0.879142973793104
    );
    const mat3 agxMatInv = mat3(
         1.19687900512017,   -0.0528968517574562, -0.0529716355144438,
        -0.0980208811401368,  1.15190312990417,   -0.0980434501171241,
        -0.0990297440797205, -0.0989611768448433,  1.15107367264116
    );
    const float minEv = -12.47393;
    const float maxEv = 4.026069;

    vec3 val = agxMat * max(color, vec3(1.0e-6));
    val = clamp(log2(val), minEv, maxEv);
    val = (val - minEv) / (maxEv - minEv);
    val = agxDefaultContrastApprox(val);

    // Subtle punchy look (slope/power/saturation)
    float luma = dot(val, vec3(0.2126, 0.7152, 0.0722));
    val = luma + (val - luma) * 1.12;
    val = pow(max(val, vec3(0.0)), vec3(1.08));

    val = agxMatInv * val;
    return clamp(pow(max(val, vec3(0.0)), vec3(2.2)), 0.0, 1.0);
}

/**
 * Stephen Hill Fitted ACES RRT + ODT.
 */
vec3 tonemapACES(vec3 color) {
    const mat3 acesInput = mat3(
        0.59719, 0.07600, 0.02840,
        0.35458, 0.90834, 0.13383,
        0.04823, 0.01566, 0.83777
    );
    const mat3 acesOutput = mat3(
         1.60475, -0.10208, -0.00327,
        -0.53108,  1.10813, -0.07276,
        -0.07367, -0.00605,  1.07602
    );
    vec3 v = acesInput * color;
    vec3 a = v * (v + 0.0245786) - 0.000090537;
    vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    return clamp(acesOutput * (a / b), 0.0, 1.0);
}

/**
 * Khronos PBR Neutral Tonemapper.
 */
vec3 tonemapKhronosNeutral(vec3 color) {
    const float startCompression = 0.8 - 0.04;
    const float desaturation = 0.15;

    float x = min(color.r, min(color.g, color.b));
    float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
    color -= offset;

    float peak = max(color.r, max(color.g, color.b));
    if (peak < startCompression) return color;

    const float d = 1.0 - startCompression;
    float newPeak = 1.0 - d * d / (peak + d - startCompression);
    color *= newPeak / peak;

    float g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0);
    return mix(color, vec3(newPeak), g);
}

vec3 applySelectedTonemap(vec3 hdrLinear, int operatorId) {
    if (operatorId == 0) return tonemapAgX(hdrLinear);
    if (operatorId == 1) return tonemapACES(hdrLinear);
    return tonemapKhronosNeutral(hdrLinear);
}

vec3 applyColorGrading(vec3 linearColor, float saturation) {
    float luma = dot(linearColor, vec3(0.2126, 0.7152, 0.0722));
    // Natural vibrance boost on muted tones + user saturation control
    float maxC = max(linearColor.r, max(linearColor.g, linearColor.b));
    float minC = min(linearColor.r, min(linearColor.g, linearColor.b));
    float sat = maxC - minC;
    float vibrance = (1.0 - sat) * 0.08;
    return max(vec3(0.0), mix(vec3(luma), linearColor, saturation + vibrance));
}

#endif
