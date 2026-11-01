#version 460 core

// ============================================================================
// Nvidium Shader Pipeline (NSP) - Phase 6: Post-Process, Bloom & Tonemapping
// Soft-knee highlight extraction, 4-octave radial bloom, exposure scaling,
// ACES Filmic tonemapping, and linear-to-sRGB gamma conversion.
// ============================================================================

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
const float BLOOM_INTENSITY = 0.18;
const float EXPOSURE        = 1.08;

// ----------------------------------------------------------------------------
// Requirement 2: Extract bright highlights exceeding threshold 1.1 with smooth knee 0.5
// ----------------------------------------------------------------------------
vec3 extractBrightHighlights(vec3 color) {
    float brightness = max(color.r, max(color.g, color.b));
    float soft = brightness - BLOOM_THRESHOLD + BLOOM_KNEE;
    soft = clamp(soft, 0.0, 2.0 * BLOOM_KNEE);
    soft = (soft * soft) / (4.0 * BLOOM_KNEE + 1e-5);
    float contribution = max(soft, brightness - BLOOM_THRESHOLD) / max(brightness, 1e-5);
    return max(color * contribution, vec3(0.0));
}

// ----------------------------------------------------------------------------
// Requirement 3: Multi-octave radial blur (radii 1.5, 3.5, 7.5, 14.0 texels)
// ----------------------------------------------------------------------------
vec3 sampleBloomOctave(vec2 uv, vec2 texelSize, float radiusTexels) {
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

    vec2 stepUv = texelSize * radiusTexels;
    vec3 acc = extractBrightHighlights(texture(u_HdrColorTexture, uv).rgb) * 0.20;

    for (int i = 0; i < 8; ++i) {
        vec2 sampleUv = clamp(uv + dirs[i] * stepUv, vec2(0.001), vec2(0.999));
        acc += extractBrightHighlights(texture(u_HdrColorTexture, sampleUv).rgb) * 0.10;
    }
    return acc;
}

vec3 computeMultiOctaveBloom(vec2 uv) {
    vec2 texelSize = 1.0 / max(u_ScreenSize, vec2(1.0));
    vec3 octave0 = sampleBloomOctave(uv, texelSize, 1.5);
    vec3 octave1 = sampleBloomOctave(uv, texelSize, 3.5);
    vec3 octave2 = sampleBloomOctave(uv, texelSize, 7.5);
    vec3 octave3 = sampleBloomOctave(uv, texelSize, 14.0);

    return octave0 * 0.35 + octave1 * 0.30 + octave2 * 0.20 + octave3 * 0.15;
}

// ----------------------------------------------------------------------------
// Requirement 5: ACES Filmic Tonemapping Curve
// ----------------------------------------------------------------------------
vec3 acesFilmic(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    // Requirement 1: Sample u_HdrColorTexture at v_Uv
    vec3 hdrColor = texture(u_HdrColorTexture, v_Uv).rgb;

    // Requirement 2 & 3: Highlight extraction & 4-octave radial bloom
    vec3 bloom = computeMultiOctaveBloom(v_Uv);
    hdrColor += bloom * BLOOM_INTENSITY;

    // Requirement 4: Exposure scaling
    vec3 exposed = hdrColor * EXPOSURE;

    // Requirement 5: ACES Filmic tonemapping
    vec3 tonemapped = acesFilmic(exposed);

    // Requirement 6: Convert output to sRGB gamma (pow 1.0 / 2.2)
    vec3 srgbColor = pow(max(tonemapped, vec3(0.0)), vec3(1.0 / 2.2));

    // Requirement 7: Output RGBA in outSrgbColor
    outSrgbColor = vec4(srgbColor, 1.0);
}
