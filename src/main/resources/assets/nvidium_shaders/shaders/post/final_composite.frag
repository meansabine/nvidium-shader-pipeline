#version 460 core

layout(location = 0) in vec2 v_TexCoord;
layout(location = 0) out vec4 outFinalColor;

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"
#include "../common/atmosphere.glsl"
#include "../common/post_tonemap.glsl"

layout(binding = 0) uniform sampler2D sceneColorTex;
layout(binding = 1) uniform sampler2D sceneDepthTex;
layout(binding = 2) uniform sampler2D gNormalMetallic;
layout(binding = 3) uniform sampler2D gLightEmission;

/**
 * Neighborhood-clamped Contrast Adaptive Sharpening (AMD FidelityFX CAS).
 * Strictly clamped to the [min, max] of the 5-tap cross neighborhood so it never
 * creates dark outlines or bright ringing halos at block edges.
 */
vec3 applyNeighborhoodClampedCAS(vec2 uv, vec3 centerCol, float sharpness) {
    if (sharpness <= 0.01) {
        return centerCol;
    }
    vec2 texel = u_ScreenSize.zw;
    vec3 cN = textureLod(sceneColorTex, uv + vec2( 0.0,  texel.y), 0.0).rgb;
    vec3 cS = textureLod(sceneColorTex, uv + vec2( 0.0, -texel.y), 0.0).rgb;
    vec3 cE = textureLod(sceneColorTex, uv + vec2( texel.x, 0.0), 0.0).rgb;
    vec3 cW = textureLod(sceneColorTex, uv + vec2(-texel.x, 0.0), 0.0).rgb;

    vec3 minRGB = min(centerCol, min(min(cN, cS), min(cE, cW)));
    vec3 maxRGB = max(centerCol, max(max(cN, cS), max(cE, cW)));

    vec3 contrast = maxRGB - minRGB;
    float peak = -mix(0.0, 0.16, clamp(sharpness, 0.0, 1.0));
    vec3 weight = sqrt(clamp(1.0 - contrast * 1.25, 0.0, 1.0)) * peak;

    vec3 sharpened = (centerCol + (cN + cS + cE + cW) * weight) / (1.0 + 4.0 * weight);
    return clamp(sharpened, minRGB, maxRGB);
}

void main() {
    vec2 sampleUV = v_TexCoord;
    float submersion = u_CameraPos.w;

    // 1. Camera Submersion Lens Effects (Underwater = 1.0, Lava = 2.0)
    if (submersion > 0.5 && submersion < 1.5) {
        vec2 wave = vec2(
                sin(v_TexCoord.y * 18.0 + u_Time.x * 2.4) + cos(v_TexCoord.x * 14.0 - u_Time.x * 1.8),
                cos(v_TexCoord.x * 16.0 + u_Time.x * 2.1) + sin(v_TexCoord.y * 12.0 + u_Time.x * 1.6)
        ) * 0.0018;
        sampleUV = clamp(v_TexCoord + wave, vec2(0.002), vec2(0.998));
    } else if (submersion > 1.5 && submersion < 2.5) {
        vec2 heat = vec2(
                sin(v_TexCoord.y * 28.0 + u_Time.x * 4.2),
                cos(v_TexCoord.x * 24.0 - u_Time.x * 3.6)
        ) * 0.0032;
        sampleUV = clamp(v_TexCoord + heat, vec2(0.002), vec2(0.998));
    }

    vec3 color = textureLod(sceneColorTex, sampleUV, 0.0).rgb;
    float depth = textureLod(sceneDepthTex, sampleUV, 0.0).r;

    // 2. Entity & Block Entity Atmospheric Integration
    // Non-G-Buffer foreground geometry (entities, block entities) has depth < 0.99999 and gNormal == 0
    if (depth < 0.99995 && u_DebugParams.x < 0.5) {
        vec3 gNorm = textureLod(gNormalMetallic, sampleUV, 0.0).xyz;
        if (dot(gNorm, gNorm) < 0.001) {
            vec3 relPos = reconstructCameraRelativePos(sampleUV, depth);
            float dist = length(relPos);
            float fogStart = max(32.0, u_FogParams.x * 0.85);
            float fogEnd = max(fogStart + 48.0, u_FogParams.y * 1.15);
            float entityHaze = smoothstep(fogStart, fogEnd, dist) * 0.78;
            if (entityHaze > 0.002) {
                vec3 V = normalize(relPos);
                vec3 hazeLinear = evaluateAtmosphericHazeSky(normalize(vec3(V.x, max(V.y, 0.04), V.z)), OPT_ATMOS_HAZINESS);
                int toneOp = int(round(u_PostParams.w));
                vec3 hazeSrgb = linearToSrgb(applySelectedTonemap(applyColorGrading(hazeLinear * u_PostParams.x, u_PostParams.z), toneOp));
                color = mix(color, hazeSrgb, entityHaze);
            }
        }
    }

    // 3. Neighborhood-clamped Contrast Adaptive Sharpening (CAS)
    if (u_DebugParams.x < 0.5) {
        color = applyNeighborhoodClampedCAS(sampleUV, color, u_DebugParams.z);
    }

    // 4. Underwater / Lava Submersion Color Absorption Tint
    if (submersion > 0.5 && submersion < 1.5) {
        vec2 vigUV = v_TexCoord * (1.0 - v_TexCoord.yx);
        float edgeMask = 1.0 - clamp(pow(vigUV.x * vigUV.y * 15.0, 0.22), 0.0, 1.0);
        color = mix(color * vec3(0.76, 0.94, 1.02), vec3(0.03, 0.16, 0.26), edgeMask * 0.42);
    } else if (submersion > 1.5 && submersion < 2.5) {
        color = mix(color * vec3(1.10, 0.55, 0.25), vec3(0.45, 0.10, 0.02), 0.35);
    }

    outFinalColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
