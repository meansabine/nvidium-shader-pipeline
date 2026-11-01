#version 460 core

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outFinalColor;

#include <nvidium_shaders:common/scene_uniforms.glsl>

layout(binding = 0) uniform sampler2D u_HdrColorTexture;

// ACES Filmic Tone Mapping Curve (Narkowicz 2015 approximation)
vec3 acesFilm(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

// Exposure compensation
const float EXPOSURE = 1.1;

void main() {
    vec3 hdrColor = texture(u_HdrColorTexture, v_Uv).rgb;

    // 1. Exposure Adjustment
    hdrColor *= EXPOSURE;

    // 2. ACES Filmic Tone Mapping
    vec3 mappedColor = acesFilm(hdrColor);

    // 3. Contrast & Vibrance Enhancements
    mappedColor = pow(mappedColor, vec3(1.05)); // Subtle contrast lift
    float luma = dot(mappedColor, vec3(0.2126, 0.7152, 0.0722));
    mappedColor = mix(vec3(luma), mappedColor, 1.15); // Slight saturation boost

    // 4. Gamma Correction (Linear -> sRGB)
    vec3 srgbColor = pow(mappedColor, vec3(1.0 / 2.2));

    outFinalColor = vec4(srgbColor, 1.0);
}
