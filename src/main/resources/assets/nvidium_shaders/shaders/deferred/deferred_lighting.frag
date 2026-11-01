#version 460 core

// ============================================================================
// Nvidium Shader Pipeline (NSP) - Phase 3: Deferred Lighting Pass
// Evaluates Cook-Torrance GGX direct sunlight, 3-cascade PCF shadows,
// inverse-square torch lighting, sky irradiance, and atmospheric sky gradient.
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

layout(binding = 0) uniform sampler2D u_AlbedoRoughness;
layout(binding = 1) uniform sampler2D u_NormalMetallic;
layout(binding = 2) uniform sampler2D u_LightEmission;
layout(binding = 3) uniform sampler2D u_Depth;
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outHdrColor;

const float PI = 3.14159265359;
const float GOLDEN_ANGLE = 2.399963229728653;

// ----------------------------------------------------------------------------
// 4.1 World Position Reconstruction from Depth
// ----------------------------------------------------------------------------
vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}

// ----------------------------------------------------------------------------
// 4.2 Interleaved Gradient Noise (IGN)
// ----------------------------------------------------------------------------
float interleavedGradientNoise(vec2 pixelCoord) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    float frameOffset = float(u_FrameIndex % 64) * 5.588238;
    return fract(magic.z * fract(dot(pixelCoord + frameOffset, magic.xy)));
}

// ----------------------------------------------------------------------------
// 4.3 Cascaded Shadow Map Coordinate Selection & Vogel Disk PCF
// ----------------------------------------------------------------------------
int selectCascade(float viewDist) {
    if (viewDist < u_CascadeSplits.x) return 0;
    if (viewDist < u_CascadeSplits.y) return 1;
    return 2;
}

vec3 getShadowCoords(vec3 worldPos, int cascadeIdx) {
    vec4 shadowClip = u_CascadeViewProj[cascadeIdx] * vec4(worldPos, 1.0);
    vec3 shadowNdc = shadowClip.xyz / shadowClip.w;
    return shadowNdc * 0.5 + 0.5;
}

float sampleCascadePCF(vec3 worldPos, vec3 N, vec3 L, int cascadeIdx, float ign) {
    float cascadeScale = (cascadeIdx == 0) ? 1.0 : ((cascadeIdx == 1) ? 2.5 : 6.0);
    float cosTheta = clamp(dot(N, L), 0.0, 1.0);
    float normalBias = mix(0.06, 0.012, cosTheta) * cascadeScale;
    vec3 biasedPos = worldPos + N * normalBias;

    vec3 shadowCoord = getShadowCoords(biasedPos, cascadeIdx);
    if (shadowCoord.x <= 0.001 || shadowCoord.x >= 0.999 ||
        shadowCoord.y <= 0.001 || shadowCoord.y >= 0.999 ||
        shadowCoord.z <= 0.0   || shadowCoord.z >= 1.0) {
        return 1.0;
    }

    float depthBias = mix(0.0012, 0.0002, cosTheta) * cascadeScale;
    float compareDepth = clamp(shadowCoord.z - depthBias, 0.0, 1.0);

    // 16-tap rotated Vogel disk PCF in 2048x2048 shadow atlas
    float filterRadius = (2.2 / 2048.0) / sqrt(cascadeScale);
    float baseAngle = ign * 2.0 * PI;
    float shadowAcc = 0.0;
    const int PCF_SAMPLES = 16;

    for (int i = 0; i < PCF_SAMPLES; ++i) {
        float r = sqrt((float(i) + 0.5) / float(PCF_SAMPLES)) * filterRadius;
        float theta = float(i) * GOLDEN_ANGLE + baseAngle;
        vec2 offset = vec2(cos(theta), sin(theta)) * r;
        shadowAcc += texture(
            u_ShadowMap,
            vec4(shadowCoord.xy + offset, float(cascadeIdx), compareDepth)
        );
    }
    return shadowAcc / float(PCF_SAMPLES);
}

float evaluateCascadedShadow(vec3 worldPos, vec3 N, vec3 L, float viewDist) {
    if (viewDist >= u_CascadeSplits.z) {
        return 1.0;
    }

    int cascadeIdx = selectCascade(viewDist);
    float ign = interleavedGradientNoise(gl_FragCoord.xy);
    float shadow = sampleCascadePCF(worldPos, N, L, cascadeIdx, ign);

    // Smooth transition across cascade boundaries
    float splitDist = (cascadeIdx == 0) ? u_CascadeSplits.x :
                      ((cascadeIdx == 1) ? u_CascadeSplits.y : u_CascadeSplits.z);
    float transitionZone = splitDist * 0.15;
    float blendFactor = smoothstep(splitDist - transitionZone, splitDist, viewDist);

    if (blendFactor > 0.001 && cascadeIdx < 2) {
        float nextShadow = sampleCascadePCF(worldPos, N, L, cascadeIdx + 1, ign);
        shadow = mix(shadow, nextShadow, blendFactor);
    }

    // Distance fade near outer cascade edge
    float edgeFade = smoothstep(u_CascadeSplits.z * 0.9, u_CascadeSplits.z, viewDist);
    return mix(shadow, 1.0, edgeFade);
}

// ----------------------------------------------------------------------------
// Cook-Torrance GGX Microfacet BRDF
// ----------------------------------------------------------------------------
float distributionGGX(vec3 N, vec3 H, float roughness) {
    float a  = roughness * roughness;
    float a2 = a * a;
    float NdotH  = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;

    float denom = NdotH2 * (a2 - 1.0) + 1.0;
    return a2 / max(PI * denom * denom, 1e-6);
}

float geometrySchlickGGX(float NdotV, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return NdotV / max(NdotV * (1.0 - k) + k, 1e-6);
}

float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);
    return geometrySchlickGGX(NdotV, roughness) * geometrySchlickGGX(NdotL, roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// ----------------------------------------------------------------------------
// Atmospheric Sky Model
// ----------------------------------------------------------------------------
vec3 computeAtmosphericSky(vec3 rayDir, vec3 sunDir) {
    float elevation = clamp(rayDir.y, -1.0, 1.0);
    float sunElevation = clamp(sunDir.y, -1.0, 1.0);

    float dayFactor = smoothstep(-0.15, 0.25, sunElevation);
    float sunsetFactor = smoothstep(-0.12, 0.05, sunElevation) * (1.0 - smoothstep(0.05, 0.35, sunElevation));

    vec3 zenithDay    = vec3(0.11, 0.32, 0.85) * max(u_AmbientColor.rgb * 1.4, vec3(0.25));
    vec3 horizonDay   = vec3(0.52, 0.72, 0.98) * max(u_SunColor.rgb, vec3(0.4));
    vec3 zenithNight  = vec3(0.008, 0.014, 0.038);
    vec3 horizonNight = vec3(0.025, 0.042, 0.085);
    vec3 sunsetTint   = vec3(1.35, 0.42, 0.12);

    vec3 zenith  = mix(zenithNight, zenithDay, dayFactor);
    vec3 horizon = mix(horizonNight, horizonDay, dayFactor);
    horizon = mix(horizon, sunsetTint, sunsetFactor * 0.75);

    float horizonBlend = pow(1.0 - max(elevation, 0.0), 3.2);
    vec3 sky = mix(zenith, horizon, horizonBlend);

    // Below-horizon ground bounce
    if (elevation < 0.0) {
        vec3 nadirColor = zenith * 0.25;
        sky = mix(horizon, nadirColor, smoothstep(0.0, -0.4, elevation));
    }

    // Mie forward-scattering solar aureole & sun disk
    float cosAngle = clamp(dot(rayDir, sunDir), 0.0, 1.0);
    float mieHalo = pow(cosAngle, 8.0) * 0.35 + pow(cosAngle, 64.0) * 1.2;
    float sunDisk = smoothstep(0.9994, 0.99985, cosAngle) * 18.0;
    vec3 sunRadiance = u_SunColor.rgb * max(u_SunColor.a, 1.0);

    sky += sunRadiance * (mieHalo + sunDisk) * smoothstep(-0.08, 0.05, sunElevation);
    return max(sky, vec3(0.0));
}

void main() {
    float depth = texture(u_Depth, v_Uv).r;
    vec3 worldPos = reconstructWorldPos(v_Uv, depth);
    vec3 cameraToFrag = worldPos - u_CameraPos.xyz;
    float viewDist = length(cameraToFrag);
    vec3 V = (viewDist > 1e-5) ? (-cameraToFrag / viewDist) : vec3(0.0, 1.0, 0.0);
    vec3 L = normalize(u_SunDirection.xyz);

    // Requirement 2: Sky pixels (depth >= 0.999999)
    if (depth >= 0.999999) {
        vec3 rayDir = normalize(cameraToFrag);
        outHdrColor = vec4(computeAtmosphericSky(rayDir, L), 1.0);
        return;
    }

    vec4 albedoRoughness = texture(u_AlbedoRoughness, v_Uv);
    vec4 normalMetallic  = texture(u_NormalMetallic, v_Uv);
    vec4 lightEmission   = texture(u_LightEmission, v_Uv);

    vec3  albedo    = albedoRoughness.rgb;
    float roughness = clamp(albedoRoughness.a, 0.04, 1.0);
    float metallic  = clamp(normalMetallic.a, 0.0, 1.0);

    // Requirement 3: Unlit fallback check
    if (length(lightEmission.rg) < 0.001 && length(normalMetallic.xyz) < 0.01) {
        outHdrColor = vec4(albedo, 1.0);
        return;
    }

    // Requirement 4: Decode normal or reconstruct geometric normal from screen-space derivatives
    vec3 rawNormal = normalMetallic.xyz;
    vec3 N;
    if (length(rawNormal) < 0.01) {
        vec3 dpdx = dFdx(worldPos);
        vec3 dpdy = dFdy(worldPos);
        N = normalize(cross(dpdx, dpdy));
    } else {
        vec3 decoded = rawNormal * 2.0 - 1.0;
        if (length(decoded) < 0.01) {
            vec3 dpdx = dFdx(worldPos);
            vec3 dpdy = dFdy(worldPos);
            N = normalize(cross(dpdx, dpdy));
        } else {
            N = normalize(decoded);
        }
    }

    // Requirement 5: Cook-Torrance GGX Direct Sunlight
    vec3 H = normalize(V + L);
    vec3 F0 = mix(vec3(0.04), albedo, metallic);

    float NDF = distributionGGX(N, H, roughness);
    float G   = geometrySmith(N, V, L, roughness);
    vec3  F   = fresnelSchlick(max(dot(H, V), 0.0), F0);

    vec3 numerator    = NDF * G * F;
    float denominator = 4.0 * max(dot(N, V), 0.0) * max(dot(N, L), 0.0) + 1e-4;
    vec3 specular     = numerator / denominator;

    vec3 kS = F;
    vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);
    float NdotL = max(dot(N, L), 0.0);

    // Requirement 6: Sample Cascaded Shadow Map with PCF
    float shadowVisibility = (NdotL > 0.0001)
        ? evaluateCascadedShadow(worldPos, N, L, viewDist)
        : 0.0;

    float sunIntensity = max(u_SunColor.a, 1.0);
    vec3 sunRadiance   = u_SunColor.rgb * sunIntensity;
    vec3 directLight   = (kD * albedo / PI + specular) * sunRadiance * NdotL * shadowVisibility;

    // Requirement 7: Torch lighting with inverse-square falloff & material emission
    float blockLightNorm = clamp(lightEmission.r, 0.0, 1.0);
    float torchDist      = max(1.0 - blockLightNorm, 0.0) * 15.0;
    float invSqAtten     = 1.0 / (1.0 + 0.32 * torchDist + 0.24 * torchDist * torchDist);
    float torchFlicker   = 1.0 + 0.025 * sin(u_Time * 7.3 + worldPos.x * 1.7 + worldPos.z * 2.3);
    vec3  torchColor     = vec3(1.0, 0.58, 0.24) * 3.4 * torchFlicker;
    vec3  torchLighting  = albedo * torchColor * invSqAtten * smoothstep(0.0, 0.08, blockLightNorm);

    float emissionFactor   = clamp(lightEmission.b, 0.0, 1.0);
    vec3  emissiveRadiance = albedo * emissionFactor * 6.5;

    // Requirement 8: Sky ambient irradiance scaled by lightEmission.g (sky light)
    float skyLightNorm = clamp(lightEmission.g, 0.0, 1.0);
    float skyOcclusion = mix(0.035, 1.0, skyLightNorm * skyLightNorm);
    float hemiFactor   = N.y * 0.5 + 0.5;
    vec3  skyIrradiance = mix(u_AmbientColor.rgb * 0.35, u_AmbientColor.rgb * 1.15, hemiFactor);
    vec3  ambientLight  = kD * albedo * skyIrradiance * skyOcclusion;

    vec3 hdrColor = directLight + ambientLight + torchLighting + emissiveRadiance;

    // Requirement 9: Distance fog blend (u_FogStart, u_FogEnd, u_FogColor)
    float fogSpan   = max(u_FogEnd - u_FogStart, 1e-3);
    float fogFactor = clamp((viewDist - u_FogStart) / fogSpan, 0.0, 1.0);
    fogFactor = fogFactor * fogFactor * (3.0 - 2.0 * fogFactor);
    vec3 fogTarget  = mix(u_FogColor.rgb, computeAtmosphericSky(-V, L), 0.35);
    hdrColor = mix(hdrColor, fogTarget, fogFactor * clamp(u_FogColor.a, 0.0, 1.0));

    // Requirement 10: Output linear HDR color
    outHdrColor = vec4(max(hdrColor, vec3(0.0)), 1.0);
}
