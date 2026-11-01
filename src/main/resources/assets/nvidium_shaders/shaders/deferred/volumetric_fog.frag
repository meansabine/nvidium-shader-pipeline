#version 460 core

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outVolumetric;

#include <nvidium_shaders:common/scene_uniforms.glsl>

layout(binding = 0) uniform sampler2D u_DepthTexture;
layout(binding = 1) uniform sampler2DArrayShadow u_ShadowMap;

const int NUM_STEPS = 16;
const float G_SCATTER = 0.65; // Mie scattering forward-anisotropy

// Henyey-Greenstein phase function for forward light scattering
float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * 3.14159265 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

void main() {
    float depth = texture(u_DepthTexture, v_Uv).r;
    vec4 clip = vec4(v_Uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    vec3 targetPos = worldPosH.xyz / worldPosH.w;

    vec3 rayStart = u_CameraPos.xyz;
    vec3 rayDir = targetPos - rayStart;
    float maxDist = min(length(rayDir), 128.0); // Clamp raymarching distance
    rayDir = normalize(rayDir);

    float stepSize = maxDist / float(NUM_STEPS);
    vec3 currentPos = rayStart + rayDir * (stepSize * 0.5);

    vec3 sunDir = normalize(u_SunDirection.xyz);
    float cosTheta = dot(rayDir, sunDir);
    float phase = henyeyGreenstein(cosTheta, G_SCATTER);

    vec3 accumulatedLight = vec3(0.0);

    for (int i = 0; i < NUM_STEPS; i++) {
        // Cascade 1 or 2 lookup
        vec4 shadowClip = u_CascadeViewProj[1] * vec4(currentPos, 1.0);
        vec3 shadowCoord = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;

        if (shadowCoord.x >= 0.0 && shadowCoord.x <= 1.0 &&
            shadowCoord.y >= 0.0 && shadowCoord.y <= 1.0 &&
            shadowCoord.z <= 1.0) {
            float inShadow = texture(u_ShadowMap, vec4(shadowCoord.xy, 1.0, shadowCoord.z - 0.002));
            accumulatedLight += u_SunColor.rgb * inShadow * phase * stepSize * 0.015;
        }

        currentPos += rayDir * stepSize;
    }

    outVolumetric = vec4(accumulatedLight, 1.0);
}
