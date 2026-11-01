#version 460 core

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outWater;

#include <nvidium_shaders:common/scene_uniforms.glsl>

layout(binding = 0) uniform sampler2D u_OpaqueSceneColor;
layout(binding = 1) uniform sampler2D u_SceneDepth;

// Water optical properties
const vec3 WATER_ABSORPTION = vec3(0.15, 0.04, 0.01); // Strong red/green absorption -> deep blue/teal
const float FRESNEL_F0 = 0.02; // Water index of refraction ~1.33

// Simple Screen-Space Reflections (SSR)
vec3 calculateSSR(vec3 worldPos, vec3 normal, vec3 viewDir) {
    vec3 reflectDir = reflect(-viewDir, normal);
    vec3 stepRay = reflectDir * 0.5;
    vec3 currentPos = worldPos + stepRay;

    for (int i = 0; i < 16; i++) {
        vec4 projPos = u_ViewProjMatrix * vec4(currentPos, 1.0);
        vec2 sampleUv = projPos.xy / projPos.w * 0.5 + 0.5;

        if (sampleUv.x < 0.0 || sampleUv.x > 1.0 || sampleUv.y < 0.0 || sampleUv.y > 1.0) break;

        float sampleDepth = texture(u_SceneDepth, sampleUv).r;
        vec4 sampleWorldH = u_InvViewProjMatrix * vec4(sampleUv * 2.0 - 1.0, sampleDepth * 2.0 - 1.0, 1.0);
        vec3 hitWorldPos = sampleWorldH.xyz / sampleWorldH.w;

        if (length(currentPos - u_CameraPos.xyz) > length(hitWorldPos - u_CameraPos.xyz)) {
            // Hit detected!
            return texture(u_OpaqueSceneColor, sampleUv).rgb;
        }
        currentPos += stepRay;
    }
    // Fallback to sky color
    return u_AmbientColor.rgb * 1.5;
}

void main() {
    // Water surface normal with animated micro-waves
    vec2 waveUv = v_Uv * 8.0 + vec2(u_Time * 0.05, u_Time * 0.03);
    vec3 normal = normalize(vec3(
        sin(waveUv.x * 6.28) * 0.08,
        1.0,
        cos(waveUv.y * 6.28) * 0.08
    ));

    // Reconstruct world position
    float depth = texture(u_SceneDepth, v_Uv).r;
    vec4 clip = vec4(v_Uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    vec3 worldPos = worldPosH.xyz / worldPosH.w;
    vec3 viewDir = normalize(u_CameraPos.xyz - worldPos);

    // Fresnel reflectance
    float NdotV = max(dot(normal, viewDir), 0.0);
    float fresnel = FRESNEL_F0 + (1.0 - FRESNEL_F0) * pow(1.0 - NdotV, 5.0);

    // 1. Refraction & Beer-Lambert absorption
    vec3 refractedColor = texture(u_OpaqueSceneColor, v_Uv + normal.xz * 0.015).rgb;
    float waterDepth = 2.5; // Estimated optical depth
    refractedColor *= exp(-WATER_ABSORPTION * waterDepth);

    // 2. Screen-Space Reflection
    vec3 reflectedColor = calculateSSR(worldPos, normal, viewDir);

    // Blend reflection and refraction
    vec3 finalWater = mix(refractedColor, reflectedColor, fresnel);
    outWater = vec4(finalWater, 0.9);
}
