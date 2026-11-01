#version 460 core

layout(std140, binding = 3) uniform SceneUniforms {
    mat4  u_ViewMatrix;
    mat4  u_ProjMatrix;
    mat4  u_ViewProjMatrix;
    mat4  u_InvViewProjMatrix;
    mat4  u_CascadeViewProj[3];
    vec4  u_CascadeSplits;
    vec4  u_SunDirection;
    vec4  u_SunColor;
    vec4  u_AmbientColor;
    vec4  u_CameraPos;
    vec2  u_ScreenSize;
    vec2  _pad0;
    float u_Time;
    int   u_FrameIndex;
    float u_FogStart;
    float u_FogEnd;
    vec4  u_FogColor;
};

layout(binding = 0) uniform sampler2D u_HdrColorTexture;

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outSrgbColor;

const float BLOOM_THRESHOLD = 1.1;
const float BLOOM_KNEE      = 0.5;
const float BLOOM_INTENSITY = 0.16;
const float EXPOSURE        = 1.05;

vec3 extractBrightHighlight(vec3 color) {
    float brightness = max(max(color.r, color.g), color.b);
    float soft = brightness - BLOOM_THRESHOLD + BLOOM_KNEE;
    soft = clamp(soft, 0.0, 2.0 * BLOOM_KNEE);
    soft = (soft * soft) / (4.0 * BLOOM_KNEE + 0.00001);
    float contribution = max(soft, brightness - BLOOM_THRESHOLD) / max(brightness, 0.00001);
    return color * max(contribution, 0.0);
}

vec3 acesFilm(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec2 texelSize = 1.0 / u_ScreenSize;
    vec3 hdrColor = texture(u_HdrColorTexture, v_Uv).rgb;

    const float radii[4] = float[4](1.5, 3.5, 7.5, 14.0);
    const float octaveWeights[4] = float[4](0.40, 0.30, 0.18, 0.12);
    const vec2 dirs[8] = vec2[8](
        vec2( 1.0,  0.0),
        vec2(-1.0,  0.0),
        vec2( 0.0,  1.0),
        vec2( 0.0, -1.0),
        vec2( 0.70710678,  0.70710678),
        vec2(-0.70710678,  0.70710678),
        vec2( 0.70710678, -0.70710678),
        vec2(-0.70710678, -0.70710678)
    );

    vec3 bloomAccum = vec3(0.0);
    for (int oct = 0; oct < 4; ++oct) {
        float r = radii[oct];
        vec3 octaveSum = vec3(0.0);
        for (int d = 0; d < 8; ++d) {
            vec2 sampleUv = clamp(v_Uv + dirs[d] * texelSize * r, vec2(0.001), vec2(0.999));
            vec3 sampleCol = texture(u_HdrColorTexture, sampleUv).rgb;
            octaveSum += extractBrightHighlight(sampleCol);
        }
        bloomAccum += (octaveSum / 8.0) * octaveWeights[oct];
    }

    vec3 combinedHdr = (hdrColor + bloomAccum * BLOOM_INTENSITY) * EXPOSURE;
    vec3 ldrColor = acesFilm(combinedHdr);

    ldrColor = smoothstep(vec3(0.0), vec3(1.0), ldrColor) * 0.15 + ldrColor * 0.85;
    float luma = dot(ldrColor, vec3(0.2126, 0.7152, 0.0722));
    float maxChan = max(max(ldrColor.r, ldrColor.g), ldrColor.b);
    float minChan = min(min(ldrColor.r, ldrColor.g), ldrColor.b);
    float saturation = maxChan - minChan;
    float vibrance = 0.18 * (1.0 - saturation);
    ldrColor = clamp(mix(vec3(luma), ldrColor, 1.0 + vibrance), 0.0, 1.0);

    vec3 srgbColor = pow(ldrColor, vec3(1.0 / 2.2));
    outSrgbColor = vec4(srgbColor, 1.0);
}
