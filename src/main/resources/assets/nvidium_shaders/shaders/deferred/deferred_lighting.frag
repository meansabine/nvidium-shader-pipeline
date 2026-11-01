#version 460 core

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outColor;

// Scene Uniforms & PBR
#include <nvidium_shaders:common/scene_uniforms.glsl>
#include <nvidium_shaders:common/pbr_brdf.glsl>

// G-Buffer samplers
layout(binding = 0) uniform sampler2D u_AlbedoRoughness;
layout(binding = 1) uniform sampler2D u_NormalMetallic;
layout(binding = 2) uniform sampler2D u_LightEmission;
layout(binding = 3) uniform sampler2D u_Depth;

// Shadow Map Array (Hardware PCF Sampler)
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;

// Percentage-Closer Filtering (PCF) shadow evaluation
float sampleShadow(vec3 worldPos, float viewDist) {
    // Determine cascade index
    int cascade = 2;
    if (viewDist < u_CascadeSplits.x) {
        cascade = 0;
    } else if (viewDist < u_CascadeSplits.y) {
        cascade = 1;
    }

    vec4 shadowClip = u_CascadeViewProj[cascade] * vec4(worldPos, 1.0);
    vec3 shadowCoord = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;

    // Check bounds
    if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0 ||
        shadowCoord.y < 0.0 || shadowCoord.y > 1.0 ||
        shadowCoord.z > 1.0) {
        return 1.0;
    }

    // 4-sample jittered PCF
    float shadow = 0.0;
    vec2 texelSize = vec2(1.0 / 2048.0);
    for (int x = -1; x <= 1; x += 2) {
        for (int y = -1; y <= 1; y += 2) {
            vec2 offset = vec2(x, y) * texelSize * 0.75;
            shadow += texture(u_ShadowMap, vec4(shadowCoord.xy + offset, float(cascade), shadowCoord.z - 0.0015));
        }
    }
    return shadow * 0.25;
}

void main() {
    float depth = texture(u_Depth, v_Uv).r;
    if (depth >= 1.0) {
        // Sky or void
        outColor = vec4(u_AmbientColor.rgb * 1.5, 1.0);
        return;
    }

    // 1. Reconstruct world position from depth buffer
    vec4 clip = vec4(v_Uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    vec3 worldPos = worldPosH.xyz / worldPosH.w;

    // 2. Sample G-Buffer attributes
    vec4 albedoRoughness = texture(u_AlbedoRoughness, v_Uv);
    vec4 normalMetallic = texture(u_NormalMetallic, v_Uv);
    vec4 lightEmission = texture(u_LightEmission, v_Uv);

    vec3 albedo = albedoRoughness.rgb;
    float roughness = albedoRoughness.a;
    vec3 normal = normalize(normalMetallic.rgb * 2.0 - 1.0);
    float metallic = normalMetallic.a;
    vec2 lightUv = lightEmission.rg;
    float emissive = lightEmission.b;

    vec3 viewDir = normalize(u_CameraPos.xyz - worldPos);
    float viewDist = length(u_CameraPos.xyz - worldPos);

    // 3. Evaluate Cascaded Sunlight & Shadows
    vec3 sunDir = normalize(u_SunDirection.xyz);
    float shadow = sampleShadow(worldPos, viewDist);
    vec3 directSun = evaluatePbrBRDF(normal, viewDir, sunDir, albedo, roughness, metallic, u_SunColor.rgb * 2.5);
    directSun *= shadow;

    // 4. Evaluate Ambient Skylight & Torch Light
    vec3 skyLight = u_AmbientColor.rgb * lightUv.y * albedo;
    vec3 torchColor = vec3(1.0, 0.6, 0.25) * 2.0;
    vec3 blockLight = torchColor * pow(lightUv.x, 2.2) * albedo;

    // 5. Accumulate Lighting
    vec3 finalColor = directSun + skyLight + blockLight + (albedo * emissive * 4.0);

    // Apply distance fog
    float fogFactor = clamp((viewDist - u_FogStart) / (u_FogEnd - u_FogStart), 0.0, 1.0);
    finalColor = mix(finalColor, u_FogColor.rgb, fogFactor);

    outColor = vec4(finalColor, 1.0);
}
