#version 460 core
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require
#extension GL_NV_fragment_shader_barycentric : require

#import <nvidium:occlusion/scene.glsl>
#import <nvidium:terrain/vertex_format.glsl>

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"
#include "../common/materials.glsl"
#include "../common/pbr_brdf.glsl"
#include "../common/shadows.glsl"
#include "../common/atmosphere.glsl"
#include "../common/post_tonemap.glsl"

layout(location = 0) out vec4 outFragColor;

layout(binding = 0) uniform sampler2D tex_diffuse;
layout(binding = 1) uniform sampler2D tex_light;
layout(binding = 2) uniform sampler2D tex_normal;
layout(binding = 3) uniform sampler2D tex_specular;
layout(binding = 4) uniform sampler2D tex_scene_color; // Feedback-loop-free opaque color copy (with mips)
layout(binding = 5) uniform sampler2D tex_scene_depth; // Feedback-loop-free opaque depth copy
layout(binding = 6) uniform sampler2DArray tex_shadow_map;

layout(location = 5) in NvidiumVertexData {
    vec2 v_uv;
    vec3 v_color;
    vec2 v_lightUV;
} nv_in;

float sampleWaterWaveHeight(vec2 p, float t) {
    // Domain-warped multi-directional FBM wave surface (free of periodic grid polka-dots)
    vec2 flowA = p * 0.62 + vec2(t * 0.42, -t * 0.29);
    vec2 flowB = vec2(p.x * 0.48 - p.y * 0.52, p.x * 0.52 + p.y * 0.48) + vec2(-t * 0.34, t * 0.46);
    vec2 warp = vec2(noise2D(flowA * 0.85) - 0.5, noise2D(flowB * 0.85) - 0.5) * 0.55;

    float hA = fbm2D(flowA + warp);
    float hB = fbm2D(flowB - warp * 0.8);
    float capillary = (noise2D(p * 2.85 + vec2(t * 0.95, t * 0.72)) - 0.5) * 0.16;
    return (hA * 0.55 + hB * 0.45) + capillary;
}

/**
 * Domain-warped FBM Water Surface Normal (organic at both grazing and top-down angles).
 */
vec3 computeWaterWaveNormal(vec3 worldPos, vec3 geomNormal, float waveAmpOpt, out float outCrestFoam) {
    float t = u_Time.x;
    vec2 p = worldPos.xz;
    const float eps = 0.14;

    float hC = sampleWaterWaveHeight(p, t);
    float hX = sampleWaterWaveHeight(p + vec2(eps, 0.0), t);
    float hZ = sampleWaterWaveHeight(p + vec2(0.0, eps), t);

    float dHx = ((hX - hC) / eps) * 0.065 * waveAmpOpt;
    float dHz = ((hZ - hC) / eps) * 0.065 * waveAmpOpt;

    outCrestFoam = 0.0;

    if (abs(geomNormal.y) > 0.5) {
        return normalize(vec3(-dHx, 1.0, -dHz));
    }
    return normalize(geomNormal + vec3(-dHx, 0.0, -dHz) * 0.5);
}

float sampleSeabedCaustics(vec3 worldPos, float timeSec) {
    vec2 p1 = worldPos.xz * 0.75 + vec2(timeSec * 0.48, -timeSec * 0.35);
    vec2 p2 = worldPos.xz * 0.98 + vec2(-timeSec * 0.41, timeSec * 0.52);
    float n1 = abs(noise2D(p1 * 2.2) - 0.5);
    float n2 = abs(noise2D(p2 * 2.5) - 0.5);
    float c = clamp(1.0 - (n1 + n2) * 3.4, 0.0, 1.0);
    return pow(c, 2.2) * 1.75;
}

vec4 traceTranslucentSSR(vec3 camRelPos, vec3 R) {
    if (u_ShadowParams.z < 0.5) {
        return vec4(0.0);
    }
    float jitter = interleavedGradientNoise(gl_FragCoord.xy, 0.0);
    vec3 rayPos = camRelPos + R * (0.25 + jitter * 0.20);
    vec3 stepVec = R * 0.55;

    for (int i = 0; i < 12; i++) {
        rayPos += stepVec;
        stepVec *= 1.18;

        vec3 uvDepth = projectCameraRelativeToUVDepth(rayPos);
        if (uvDepth.x <= 0.01 || uvDepth.x >= 0.99 || uvDepth.y <= 0.01 || uvDepth.y >= 0.99 || uvDepth.z <= 0.0 || uvDepth.z >= 1.0) {
            break;
        }
        float sceneDepth = textureLod(tex_scene_depth, uvDepth.xy, 0.0).r;
        if (sceneDepth >= 0.9999) continue;

        vec3 sceneRelPos = reconstructCameraRelativePos(uvDepth.xy, sceneDepth);
        float diff = length(rayPos) - length(sceneRelPos);
        if (diff > 0.0 && diff < length(stepVec) * 1.9) {
            vec3 hitSrgb = textureLod(tex_scene_color, uvDepth.xy, 0.0).rgb;
            vec2 edgeFade = smoothstep(vec2(0.0), vec2(0.12), uvDepth.xy) * (1.0 - smoothstep(vec2(0.88), vec2(1.0), uvDepth.xy));
            return vec4(srgbToLinear(hitSrgb), edgeFade.x * edgeFade.y);
        }
    }
    return vec4(0.0);
}

void main() {
    uint quadId = uint(gl_PrimitiveID) >> 4;
    bool triangle0 = ((uint(gl_PrimitiveID) >> 3) & 1u) == 0u;
    uvec3 triIndices = triangle0 ? uvec3(0u, 1u, 2u) : uvec3(2u, 3u, 0u);

    Vertex V0 = terrainData[(quadId << 2) + triIndices.x];
    Vertex V1 = terrainData[(quadId << 2) + triIndices.y];
    Vertex V2 = terrainData[(quadId << 2) + triIndices.z];

    vec2 uv = nv_in.v_uv;
    vec4 texSample = texture(tex_diffuse, uv, 0.0);
    if (texSample.a < 0.02) {
        discard;
    }

    vec3 pos0 = decodeVertexPosition(V0);
    vec3 pos1 = decodeVertexPosition(V1);
    vec3 pos2 = decodeVertexPosition(V2);
    vec3 geomNormal = cross(pos1 - pos0, pos2 - pos0);
    float geomLen = length(geomNormal);
    geomNormal = (geomLen > 1.0e-6) ? (geomNormal / geomLen) : vec3(0.0, 1.0, 0.0);
    if (!gl_FrontFacing) {
        geomNormal = -geomNormal;
    }

    vec3 rawVCol = nv_in.v_color;
    float maxChannel = max(rawVCol.r, max(rawVCol.g, rawVCol.b));
    vec3 biomeTint = (maxChannel > 1.0e-4) ? (rawVCol / maxChannel) : vec3(1.0);

    vec2 lightUV = nv_in.v_lightUV;
    float blockLight = clamp((lightUV.x - 0.03125) / 0.9375, 0.0, 1.0);
    float skyLight   = clamp((lightUV.y - 0.03125) / 0.9375, 0.0, 1.0);

    vec2 screenUV = gl_FragCoord.xy * u_ScreenSize.zw;
    vec3 waterRelPos = reconstructCameraRelativePos(screenUV, gl_FragCoord.z);
    vec3 worldPos = waterRelPos + u_CameraPos.xyz;
    float surfaceDist = length(waterRelPos);
    vec3 V = normalize(waterRelPos); // Ray from camera to surface
    vec3 viewToCam = -V;

    uint materialId = decodeVertexMaterial(V0);
    int toneOp = int(round(u_PostParams.w));
    float exposure = u_PostParams.x;

    // =========================================================================
    // PATH A: PHYSICAL WATER SURFACE (MAT_WATER)
    // =========================================================================
    if (materialId == MAT_WATER) {
        float crestFoam = 0.0;
        vec3 N = computeWaterWaveNormal(worldPos, geomNormal, OPT_WATER_WAVE_HEIGHT, crestFoam);

        // Refracted screen UV & depth-aware clamping
        vec2 refrOffset = N.xz * (0.032 * OPT_WATER_WAVE_HEIGHT) / max(1.0 + surfaceDist * 0.08, 1.0);
        vec2 refrUV = clamp(screenUV + refrOffset, vec2(0.002), vec2(0.998));

        float opaqueDepth = textureLod(tex_scene_depth, refrUV, 0.0).r;
        vec3 opaqueRelPos = reconstructCameraRelativePos(refrUV, opaqueDepth);
        float opaqueDist = length(opaqueRelPos);

        // Prevent foreground objects from leaking into water refraction
        if (opaqueDist < surfaceDist) {
            refrUV = screenUV;
            opaqueDepth = textureLod(tex_scene_depth, screenUV, 0.0).r;
            opaqueRelPos = reconstructCameraRelativePos(screenUV, opaqueDepth);
            opaqueDist = length(opaqueRelPos);
        }

        float waterDepth = (opaqueDepth >= 0.9999) ? 48.0 : max(0.0, opaqueDist - surfaceDist);
        vec3 refractedLinear = srgbToLinear(textureLod(tex_scene_color, refrUV, 0.0).rgb) / max(exposure, 0.25);

        // Cascaded shadow lookup on water surface
        int dummyCasc = 0;
        float shadowVis = evaluateCascadedShadow(
                tex_shadow_map,
                waterRelPos,
                geomNormal,
                normalize(u_ShadowLightDir.xyz),
                OPT_SHADOW_SOFTNESS,
                gl_FragCoord.xy,
                dummyCasc
        );

        // Underwater Seabed Caustics on refracted floor
        if (OPT_WATER_CAUSTICS > 0.5 && opaqueDepth < 0.9999 && waterDepth < 28.0) {
            vec3 seabedWorldPos = opaqueRelPos + u_CameraPos.xyz;
            float caustics = sampleSeabedCaustics(seabedWorldPos, u_Time.x);
            float depthFade = exp(-waterDepth * 0.14);
            refractedLinear += refractedLinear * u_SunColor.rgb * caustics * shadowVis * depthFade * 0.75;
        }

        // Beer-Lambert Spectral Water Absorption + Deep Water In-Scattering
        float clarity = max(0.2, OPT_WATER_CLARITY);
        vec3 sigma = vec3(0.38, 0.11, 0.055) / clarity;
        vec3 transmittance = exp(-sigma * waterDepth);

        vec3 waterBiomeTint = mix(vec3(0.04, 0.26, 0.36), srgbToLinear(biomeTint) * 0.42, 0.55);
        vec3 sunDir = normalize(u_SunDirection.xyz);
        vec3 moonDir = normalize(u_MoonDirection.xyz);
        vec3 ambientScatter = waterBiomeTint * (u_SkyColor.rgb * skyLight * 0.85 + u_SunColor.rgb * u_SunColor.w * shadowVis * 0.22 + u_BlockLightColor.rgb * pow(blockLight, 2.0));
        vec3 underwaterBody = mix(ambientScatter, refractedLinear, transmittance);

        // Schlick Fresnel (F0 = 0.02 for water)
        float NoV = clamp(dot(N, viewToCam), 0.001, 1.0);
        float fresnel = 0.02 + 0.98 * pow(1.0 - NoV, 5.0);

        // Sky + Cloud Reflection + Screen-Space Reflection (SSR)
        vec3 R = reflect(V, N);
        R.y = max(R.y, 0.02);
        vec3 skyRefl = evaluatePhysicalSky(normalize(R), 1.0, OPT_CLOUD_COVERAGE, OPT_ATMOS_HAZINESS) * clamp(skyLight * skyLight, 0.08, 1.0);
        vec4 ssr = traceTranslucentSSR(waterRelPos, R);
        vec3 reflLinear = mix(skyRefl, ssr.rgb / max(exposure, 0.25), ssr.a);

        // Direct Sun & Moon Specular Glitter on Water Waves
        vec3 H_sun = normalize(viewToCam + sunDir);
        float D_sun = D_GGX(clamp(dot(N, H_sun), 0.0, 1.0), 0.032);
        vec3 sunSpec = u_SunColor.rgb * u_SunColor.w * D_sun * shadowVis * clamp(dot(N, sunDir), 0.0, 1.0) * 0.35;

        vec3 H_moon = normalize(viewToCam + moonDir);
        float D_moon = D_GGX(clamp(dot(N, H_moon), 0.0, 1.0), 0.038);
        vec3 moonSpec = u_MoonColor.rgb * u_MoonColor.w * D_moon * shadowVis * clamp(dot(N, moonDir), 0.0, 1.0) * 0.35;

        vec3 surfaceLinear = mix(underwaterBody, reflLinear, fresnel) + sunSpec + moonSpec;

        // Shoreline Contact Foam & Wave Crest Foam
        float vertDist = abs(opaqueRelPos.y - waterRelPos.y);
        float shoreRaw = clamp(1.0 - waterDepth / 0.72, 0.0, 1.0) * clamp(1.0 - vertDist / 0.45, 0.0, 1.0);
        float foamPattern = fbm2D(worldPos.xz * 3.8 + vec2(u_Time.x * 0.45, -u_Time.x * 0.35));
        float shoreFoam = smoothstep(0.32, 0.78, shoreRaw * (0.65 + 0.65 * foamPattern));
        float totalFoam = clamp(shoreFoam + crestFoam * 0.35 * smoothstep(0.2, 1.0, skyLight), 0.0, 0.92);

        if (totalFoam > 0.01) {
            vec3 foamLit = (u_SkyColor.rgb * skyLight + u_SunColor.rgb * u_SunColor.w * shadowVis * 0.42 + u_BlockLightColor.rgb * blockLight) * 0.85;
            surfaceLinear = mix(surfaceLinear, foamLit, totalFoam);
        }

        // Distance aerial perspective (disk-free so Sun/Moon never bleed through distant water)
        float fogStart = max(32.0, u_FogParams.x * 0.85);
        float fogEnd = max(fogStart + 48.0, u_FogParams.y * 1.15);
        float distHaze = smoothstep(fogStart, fogEnd, surfaceDist) * 0.85;
        vec3 horizonSky = evaluateAtmosphericHazeSky(normalize(vec3(V.x, max(V.y, 0.04), V.z)), OPT_ATMOS_HAZINESS);
        surfaceLinear = mix(surfaceLinear, horizonSky, distHaze);

        vec3 tonemapped = applySelectedTonemap(applyColorGrading(surfaceLinear * exposure, u_PostParams.z), toneOp);
        outFragColor = vec4(linearToSrgb(tonemapped), 1.0);
        return;
    }

    // =========================================================================
    // PATH B: DIELECTRIC TRANSLUCENTS (STAINED/CLEAR GLASS, ICE, SLIME, HONEY, PORTAL)
    // =========================================================================
    vec3 N = geomNormal;
    float roughness = (materialId == MAT_ICE || materialId == MAT_SLIME_HONEY) ? 0.16 : 0.04;
    float mipBlur = (materialId == MAT_ICE) ? 1.6 : ((materialId == MAT_SLIME_HONEY) ? 2.4 : 0.0);

    vec2 refrUV = clamp(screenUV + N.xz * 0.008, vec2(0.002), vec2(0.998));
    vec3 bgLinear = srgbToLinear(textureLod(tex_scene_color, refrUV, mipBlur).rgb) / max(exposure, 0.25);

    vec3 tintLinear = srgbToLinear(texSample.rgb * biomeTint);
    float alpha = clamp(texSample.a, 0.08, 0.96);

    float NoV = clamp(dot(N, viewToCam), 0.001, 1.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - NoV, 5.0);

    vec3 R = reflect(V, N);
    vec3 skyRefl = evaluatePhysicalSky(normalize(vec3(R.x, max(R.y, 0.02), R.z)), 1.0, OPT_CLOUD_COVERAGE, OPT_ATMOS_HAZINESS) * skyLight;
    vec3 sunDir = normalize(u_SunDirection.xyz);
    vec3 H_sun = normalize(viewToCam + sunDir);
    float spec = D_GGX(clamp(dot(N, H_sun), 0.0, 1.0), max(0.04, roughness * roughness)) * clamp(dot(N, sunDir), 0.0, 1.0);
    vec3 specGlow = u_SunColor.rgb * u_SunColor.w * spec * 0.25;

    vec3 litTint = tintLinear * (u_SkyColor.rgb * skyLight * 0.9 + u_BlockLightColor.rgb * pow(blockLight, 2.0) * 1.3 + vec3(0.12));
    if (materialId == MAT_PORTAL) {
        litTint = tintLinear * 3.2 * OPT_EMISSIVE_STRENGTH;
    }

    // Filter background light through stained glass / ice tint + add Fresnel reflection
    vec3 transmitted = bgLinear * mix(vec3(1.0), tintLinear * 1.35, alpha * 0.78);
    vec3 combinedLinear = mix(transmitted, litTint, alpha * 0.55) + (skyRefl * fresnel + specGlow) * (1.0 - roughness * 0.5);

    vec3 tonemapped = applySelectedTonemap(applyColorGrading(combinedLinear * exposure, u_PostParams.z), toneOp);
    outFragColor = vec4(linearToSrgb(tonemapped), 1.0);
}
