#version 460 core

// ============================================================================
// Global Scene Uniform Buffer (std140, binding = 3)
// ============================================================================
layout(std140, binding = 3) uniform SceneUniforms {
    mat4  u_ViewMatrix;          // Camera view transform
    mat4  u_ProjMatrix;          // Camera projection transform
    mat4  u_ViewProjMatrix;      // Combined View * Projection
    mat4  u_InvViewProjMatrix;   // Inverse View-Projection (for depth unprojection)
    mat4  u_CascadeViewProj[3];  // Orthographic View-Projection for 3 shadow cascades
    vec4  u_CascadeSplits;       // x=Cascade 0 (16m), y=Cascade 1 (64m), z=Cascade 2 (256m), w=Unused
    vec4  u_SunDirection;        // Normalized vector pointing towards the sun
    vec4  u_SunColor;            // Sun intensity & spectral color (RGB), intensity multiplier (A)
    vec4  u_AmbientColor;        // Sky ambient color (RGB)
    vec4  u_CameraPos;           // World-space camera coordinates (XYZ)
    vec2  u_ScreenSize;          // Display width, height in pixels
    vec2  _pad0;                 // std140 alignment padding
    float u_Time;                // Elapsed game time in seconds
    int   u_FrameIndex;          // Monotonically increasing frame counter
    float u_FogStart;            // Vanilla fog start distance in meters
    float u_FogEnd;              // Vanilla fog end distance in meters
    vec4  u_FogColor;            // Vanilla fog RGBA color
};

// ============================================================================
// G-Buffer & Shadow Map Samplers (Pass 3 Bindings)
// ============================================================================
layout(binding = 0) uniform sampler2D u_AlbedoRoughness;       // RGB: Albedo (sRGB), A: Roughness [0.04, 1.0]
layout(binding = 1) uniform sampler2D u_NormalMetallic;        // RGB: Encoded Normal, A: Metalness [0.0, 1.0]
layout(binding = 2) uniform sampler2D u_LightEmission;         // R: Block Light, G: Sky Light, B: Emission, A: Flag
layout(binding = 3) uniform sampler2D u_Depth;                 // Hardware Scene Depth (0.0 near -> 1.0 far)
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;  // 3-Cascade Shadow Map Array (2048x2048)

// Output HDR Scene Color
layout(location = 0) out vec4 outHdrColor;

const float PI = 3.14159265359;

// 16-tap Poisson Disk for soft cascaded PCF shadows
const vec2 POISSON_DISK[16] = vec2[](
    vec2(-0.94201624, -0.39906216), vec2( 0.94558609, -0.76890725),
    vec2(-0.09418410, -0.92938870), vec2( 0.34495938,  0.29387760),
    vec2(-0.91588581,  0.45771432), vec2(-0.81544232, -0.87912464),
    vec2(-0.38277543,  0.27676845), vec2( 0.97484398,  0.75648379),
    vec2( 0.44323325, -0.97511554), vec2( 0.53742981, -0.47373420),
    vec2(-0.26496911, -0.41893023), vec2( 0.79197514,  0.19090188),
    vec2(-0.24188840,  0.99706507), vec2(-0.81409955,  0.91437590),
    vec2( 0.19984126,  0.78641367), vec2( 0.14383161, -0.14100790)
);

// ============================================================================
// Coordinate & Utility Functions
// ============================================================================

// Interleaved Gradient Noise for temporal/spatial shadow jittering
float interleavedGradientNoise(vec2 pixelCoord, int frame) {
    pixelCoord += float(frame) * 5.588238;
    return fract(52.9829189 * fract(0.06711056 * pixelCoord.x + 0.00583715 * pixelCoord.y));
}

// Recipe A: Reconstruct World-Space Position from Screen UV and Hardware Depth
vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}

// ============================================================================
// Screen-Space Ambient Occlusion (Micro-Horizon Depth AO)
// ============================================================================
float computeSubtleSSAO(vec2 uv, vec3 worldPos, vec3 N, float viewDist) {
    vec2 texel = 1.0 / u_ScreenSize;
    float radius = clamp(2.5 / max(viewDist, 1.0), 1.5, 6.0);
    float noise = interleavedGradientNoise(gl_FragCoord.xy, u_FrameIndex) * 2.0 * PI;
    float s = sin(noise);
    float c = cos(noise);
    mat2 rot = mat2(c, -s, s, c);

    const vec2 dirs[4] = vec2[](
        vec2(1.0, 0.0), vec2(-1.0, 0.0),
        vec2(0.0, 1.0), vec2(0.0, -1.0)
    );

    float occlusion = 0.0;
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUV = uv + (rot * dirs[i]) * texel * radius;
        float sampleDepth = texture(u_Depth, sampleUV).r;
        if (sampleDepth >= 1.0) continue;

        vec3 samplePos = reconstructWorldPos(sampleUV, sampleDepth);
        vec3 diff = samplePos - worldPos;
        float dist = length(diff);
        vec3 horizonDir = diff / max(dist, 0.0001);

        float rangeCheck = smoothstep(1.5, 0.0, dist);
        occlusion += max(0.0, dot(N, horizonDir) - 0.08) * rangeCheck;
    }

    return clamp(1.0 - (occlusion * 0.22), 0.35, 1.0);
}

// ============================================================================
// Cascaded Shadow Maps (Soft Rotated Poisson PCF)
// ============================================================================
float sampleCascadedShadow(vec3 worldPos, vec3 N, vec3 L, float viewDist) {
    int cascade = (viewDist < u_CascadeSplits.x) ? 0 :
                  (viewDist < u_CascadeSplits.y) ? 1 : 2;

    // Normal offset bias scaled by cascade coverage and slope to prevent shadow acne
    float cascadeScale = (cascade == 0) ? 1.0 : (cascade == 1) ? 2.5 : 6.0;
    float NdotL = max(dot(N, L), 0.0);
    vec3 offsetPos = worldPos + N * (0.015 * cascadeScale * (1.0 - NdotL * 0.7));

    vec4 shadowClip = u_CascadeViewProj[cascade] * vec4(offsetPos, 1.0);
    vec3 shadowCoord = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;

    if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0 ||
        shadowCoord.y < 0.0 || shadowCoord.y > 1.0 ||
        shadowCoord.z > 1.0) {
        return 1.0;
    }

    // Per-pixel rotation matrix using Interleaved Gradient Noise
    float angle = interleavedGradientNoise(gl_FragCoord.xy, u_FrameIndex) * 2.0 * PI;
    float s = sin(angle);
    float c = cos(angle);
    mat2 rot = mat2(c, -s, s, c);

    // Filter radius shrinks on wider cascades to keep world-space penumbra consistent
    float filterRadius = (cascade == 0) ? 2.0 : (cascade == 1) ? 1.35 : 0.85;
    vec2 texelSize = vec2(1.0 / 2048.0) * filterRadius;
    float depthBias = 0.0006 * cascadeScale;

    float shadow = 0.0;
    for (int i = 0; i < 16; ++i) {
        vec2 offset = (rot * POISSON_DISK[i]) * texelSize;
        shadow += texture(u_ShadowMap, vec4(shadowCoord.xy + offset, float(cascade), shadowCoord.z - depthBias));
    }

    // Smooth fade out at the far edge of Cascade 2 (256m)
    float farFade = smoothstep(u_CascadeSplits.z * 0.9, u_CascadeSplits.z, viewDist);
    return mix(shadow / 16.0, 1.0, farFade);
}

// ============================================================================
// Cook-Torrance Microfacet GGX BRDF (Recipe E)
// ============================================================================
float distributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float denom = (NdotH * NdotH * (a2 - 1.0) + 1.0);
    return a2 / (PI * denom * denom);
}

float geometrySchlickGGX(float NdotV, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return NdotV / (NdotV * (1.0 - k) + k);
}

float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    return geometrySchlickGGX(max(dot(N, V), 0.0), roughness) *
           geometrySchlickGGX(max(dot(N, L), 0.0), roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 fresnelSchlickRoughness(float cosTheta, vec3 F0, float roughness) {
    return F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 evaluatePbrBRDF(vec3 N, vec3 V, vec3 L, vec3 albedo, float roughness, float metallic, vec3 lightColor) {
    vec3 H = normalize(V + L);
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.0);
    if (NdotL <= 0.0 || NdotV <= 0.0) return vec3(0.0);

    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    float NDF = distributionGGX(N, H, roughness);
    float G = geometrySmith(N, V, L, roughness);
    vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

    vec3 specular = (NDF * G * F) / (4.0 * NdotV * NdotL + 0.0001);
    vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);
    return (kD * albedo / PI + specular) * lightColor * NdotL;
}

// ============================================================================
// Sky & Atmospheric Color Model
// ============================================================================
vec3 evaluateAtmosphericSky(vec3 viewDir, vec3 sunDir, vec3 sunColor) {
    float horizon = clamp(viewDir.y, 0.0, 1.0);
    vec3 zenithSky = u_AmbientColor.rgb * 1.35;
    vec3 horizonSky = mix(u_FogColor.rgb, u_AmbientColor.rgb, 0.45);
    vec3 skyGradient = mix(horizonSky, zenithSky, pow(horizon, 0.55));

    // Forward solar Mie glow near the sun disk
    float sunViewDot = max(dot(viewDir, sunDir), 0.0);
    vec3 sunGlow = sunColor * (pow(sunViewDot, 12.0) * 0.25 + pow(sunViewDot, 256.0) * 2.5);

    return skyGradient + sunGlow;
}

// ============================================================================
// Main Deferred Lighting Entry Point
// ============================================================================
void main() {
    vec2 uv = gl_FragCoord.xy / u_ScreenSize;
    float depth = texture(u_Depth, uv).r;

    vec3 L_sun = normalize(u_SunDirection.xyz);
    vec3 sunRadiance = u_SunColor.rgb * u_SunColor.a;

    // Early out for unoccluded sky background (depth == 1.0)
    if (depth >= 1.0) {
        vec3 clipFar = reconstructWorldPos(uv, 1.0);
        vec3 viewRay = normalize(clipFar - u_CameraPos.xyz);
        outHdrColor = vec4(evaluateAtmosphericSky(viewRay, L_sun, sunRadiance), 1.0);
        return;
    }

    // 1. Unpack G-Buffer Attributes
    vec4 albedoRoughness = texture(u_AlbedoRoughness, uv);
    vec4 normalMetallic  = texture(u_NormalMetallic, uv);
    vec4 lightEmission   = texture(u_LightEmission, uv);

    // Fallback A: When rendered with Nvidium's default shader, Attachment 0 contains
    // pre-lit vanilla diffuse, while normal and light buffers are unwritten (0).
    // In that case, output the pre-lit color directly so terrain is always 100% visible!
    if (length(lightEmission.rg) < 0.001 && length(normalMetallic.xyz) < 0.01) {
        outHdrColor = vec4(albedoRoughness.rgb, 1.0);
        return;
    }

    // Reconstruct geometry & view vectors
    vec3 worldPos = reconstructWorldPos(uv, depth);
    vec3 V = normalize(u_CameraPos.xyz - worldPos);
    float viewDist = length(u_CameraPos.xyz - worldPos);

    // Fallback B: If normal texture is unwritten, derive geometric normal from screen derivatives
    vec3 N;
    if (length(normalMetallic.xyz) < 0.01) {
        vec3 dX = dFdx(worldPos);
        vec3 dY = dFdy(worldPos);
        N = normalize(cross(dY, dX));
    } else {
        N = normalize(normalMetallic.rgb * 2.0 - 1.0);
    }

    // Convert sRGB Albedo to Linear space for physical lighting
    vec3 albedo     = pow(albedoRoughness.rgb, vec3(2.2));
    float roughness = clamp(albedoRoughness.a, 0.04, 1.0);
    float metallic  = clamp(normalMetallic.a, 0.0, 1.0);

    float blockLight = lightEmission.r; // [0.0, 1.0]
    float skyLight   = lightEmission.g; // [0.0, 1.0]
    float emission   = lightEmission.b; // LabPBR emissive

    // ========================================================================
    // 1. Direct Sunlight (Cook-Torrance GGX + Cascaded Soft PCF Shadows)
    // ========================================================================
    float shadowFactor = sampleCascadedShadow(worldPos, N, L_sun, viewDist);
    // Modulate shadow by skyLight to prevent light leakage inside sealed caves
    float caveShadowMask = smoothstep(0.02, 0.25, skyLight);
    vec3 directLighting = evaluatePbrBRDF(N, V, L_sun, albedo, roughness, metallic, sunRadiance)
                          * shadowFactor * caveShadowMask;

    // ========================================================================
    // 2. Dynamic Colored Torchlight (Inverse-Square Physical Falloff)
    // ========================================================================
    // Map normalized Minecraft blocklight [0..1] to physical distance (0m to 15m)
    float torchDist = max((1.0 - blockLight) * 15.0, 0.0);
    // Physical inverse-square law with smooth windowing to zero at light level 0
    float invSqAttenuation = 1.0 / (1.0 + 1.45 * torchDist + 0.85 * torchDist * torchDist);
    float windowFalloff = pow(clamp(blockLight, 0.0, 1.0), 2.0);
    float torchIntensity = invSqAttenuation * windowFalloff * 5.5;

    // Subtle organic flame flicker using u_Time and world coordinates
    float flicker = 1.0 + 0.035 * sin(u_Time * 11.0 + dot(worldPos, vec3(2.3, 1.7, 3.1)))
                        + 0.020 * cos(u_Time * 23.0 - worldPos.y * 4.0);

    // Warm blackbody colour temperature (~1900K orange core shifting to deep ember)
    vec3 torchSpectrum = mix(vec3(1.0, 0.32, 0.06), vec3(1.0, 0.68, 0.30), blockLight);
    vec3 torchRadiance = torchSpectrum * torchIntensity * flicker;

    // Evaluate diffuse + microfacet specular contribution from local light field
    vec3 L_torch = normalize(V + vec3(0.0, 0.45, 0.0));
    vec3 torchBrdf = evaluatePbrBRDF(N, V, L_torch, albedo, roughness, metallic, torchRadiance);
    vec3 torchAmbient = albedo * (1.0 - metallic) * torchRadiance * (0.35 / PI);
    vec3 torchLighting = torchBrdf + torchAmbient;

    // ========================================================================
    // 3. Realistic Atmospheric Skylight + Subtle Ambient Occlusion
    // ========================================================================
    float ssao = computeSubtleSSAO(uv, worldPos, N, viewDist);
    float voxelAo = mix(0.18, 1.0, skyLight * skyLight);
    float combinedAO = ssao * voxelAo;

    // Hemispheric sky-ground irradiance gradient
    float hemiWeight = N.y * 0.5 + 0.5;
    vec3 skyIrradiance = u_AmbientColor.rgb * 1.15;
    vec3 groundAlbedoBounce = u_AmbientColor.rgb * vec3(0.32, 0.30, 0.26) * 0.45;
    vec3 hemisphereIrradiance = mix(groundAlbedoBounce, skyIrradiance, hemiWeight);

    // Energy-conserving ambient diffuse & rough Fresnel sky reflection
    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    vec3 F_env = fresnelSchlickRoughness(max(dot(N, V), 0.0), F0, roughness);
    vec3 kD_env = (1.0 - F_env) * (1.0 - metallic);

    vec3 R = reflect(-V, N);
    vec3 skyReflectionColor = evaluateAtmosphericSky(R, L_sun, sunRadiance * 0.25);
    float specOcclusion = clamp(pow(combinedAO, 1.5 - roughness), 0.0, 1.0);
    vec3 envSpecular = skyReflectionColor * F_env * (1.0 - roughness * 0.75) * specOcclusion * skyLight;

    // Minimum deep-cave ambient floor so unlit caves retain subtle visibility
    vec3 caveFloorAmbient = vec3(0.0035, 0.004, 0.006) * ssao;
    vec3 ambientLighting = (kD_env * albedo * hemisphereIrradiance * combinedAO) + envSpecular + (albedo * caveFloorAmbient);

    // ========================================================================
    // LabPBR 1.3 Emissive Surfaces
    // ========================================================================
    vec3 emissiveLighting = albedo * emission * 12.0;

    // Combine all HDR lighting contributions
    vec3 litColor = directLighting + torchLighting + ambientLighting + emissiveLighting;

    // ========================================================================
    // 4. Smooth Distance Fog Blending into Sky Color
    // ========================================================================
    vec3 viewRay = -V;
    vec3 directionalSkyFog = evaluateAtmosphericSky(viewRay, L_sun, sunRadiance * 0.35);
    vec3 finalFogColor = mix(u_FogColor.rgb, directionalSkyFog, 0.65);

    // Combine smooth linear vanilla fog step with exponential atmospheric extinction
    float fogRange = max(u_FogEnd - u_FogStart, 0.001);
    float linearFog = clamp((viewDist - u_FogStart) / fogRange, 0.0, 1.0);
    float smoothFog = smoothstep(0.0, 1.0, linearFog);
    float expHaze = 1.0 - exp(-max(viewDist - u_FogStart * 0.25, 0.0) * (1.2 / max(u_FogEnd, 1.0)));
    float fogFactor = clamp(mix(expHaze * 0.35, 1.0, smoothFog), 0.0, 1.0);

    vec3 finalColor = mix(litColor, finalFogColor, fogFactor);

    outHdrColor = vec4(finalColor, 1.0);
}