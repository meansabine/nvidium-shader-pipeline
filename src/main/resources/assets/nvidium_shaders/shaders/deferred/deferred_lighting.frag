#version 460 core

// Global Scene Uniform Buffer (std140, binding = 3)
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


// G-Buffer and Shadow Map Bindings (Pass 3 specification)
layout(binding = 0) uniform sampler2D u_AlbedoRoughness;       // From G-Buffer RT0
layout(binding = 1) uniform sampler2D u_NormalMetallic;        // From G-Buffer RT1
layout(binding = 2) uniform sampler2D u_LightEmission;         // From G-Buffer RT2
layout(binding = 3) uniform sampler2D u_Depth;                 // Hardware Scene Depth (GL_DEPTH_COMPONENT32F)
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;  // 3-Cascade Shadow Map Array

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outHdrColor;

const float PI = 3.14159265359;

// Recipe A: World Position Reconstruction from Depth
vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}

// Interleaved Gradient Noise for soft shadow PCF jittering
float interleavedGradientNoise(vec2 pixelCoord) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    float frameOffset = float(u_FrameIndex % 64) * 5.588238;
    return fract(magic.z * fract(dot(pixelCoord + frameOffset, magic.xy)));
}

// Recipe D: Cascaded Shadow Map Sampling (Hardware PCF + Vogel Disk Soft Filtering)
float sampleCascadedShadow(vec3 worldPos, vec3 N, vec3 L, float viewDist) {
    int cascade = (viewDist < u_CascadeSplits.x) ? 0 :
                  (viewDist < u_CascadeSplits.y) ? 1 : 2;

    // Normal offset bias scaled by cascade size to prevent shadow acne
    float cascadeScale = (cascade == 0) ? 0.015 : (cascade == 1) ? 0.045 : 0.12;
    float NdotL = max(dot(N, L), 0.0);
    vec3 biasedPos = worldPos + N * cascadeScale * (1.0 - NdotL * 0.7);

    vec4 shadowClip = u_CascadeViewProj[cascade] * vec4(biasedPos, 1.0);
    vec3 shadowCoord = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;

    // Discard out-of-bounds coordinates
    if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0 ||
        shadowCoord.y < 0.0 || shadowCoord.y > 1.0 ||
        shadowCoord.z > 1.0) {
        return 1.0;
    }

    // 4-sample rotated/jittered PCF per Recipe D with temporal noise rotation
    float angle = interleavedGradientNoise(gl_FragCoord.xy) * 2.0 * PI;
    float s = sin(angle);
    float c = cos(angle);
    mat2 rot = mat2(c, -s, s, c);

    float shadow = 0.0;
    vec2 texelSize = vec2(1.0 / 2048.0);
    for (int x = -1; x <= 1; x += 2) {
        for (int y = -1; y <= 1; y += 2) {
            vec2 offset = rot * (vec2(x, y) * texelSize * 0.75);
            shadow += texture(u_ShadowMap, vec4(shadowCoord.xy + offset, float(cascade), shadowCoord.z - 0.0015));
        }
    }
    return shadow * 0.25;
}

// Recipe E: Cook-Torrance Microfacet GGX BRDF
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

// Screen-space micro-crevice ambient occlusion from depth & normal gradients
float computeMicroCreviceAO(vec2 uv, vec3 worldPos, vec3 N, float viewDist) {
    vec2 texel = 1.0 / u_ScreenSize;
    float ao = 0.0;
    const vec2 offsets[4] = vec2[](
        vec2( 1.0,  0.0), vec2(-1.0,  0.0),
        vec2( 0.0,  1.0), vec2( 0.0, -1.0)
    );
    float radius = clamp(2.5 / max(viewDist, 1.0), 1.0, 4.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUv = uv + offsets[i] * texel * radius;
        float sampleDepth = texture(u_Depth, sampleUv).r;
        if (sampleDepth >= 1.0) continue;
        vec3 samplePos = reconstructWorldPos(sampleUv, sampleDepth);
        vec3 diff = samplePos - worldPos;
        float dist = length(diff);
        if (dist > 0.0001 && dist < 1.25) {
            ao += max(0.0, dot(N, diff / dist) - 0.05) * (1.0 - dist / 1.25);
        }
    }
    return clamp(1.0 - (ao * 0.35), 0.25, 1.0);
}

// Procedural atmospheric sky gradient for background & fog blending
vec3 evaluateAtmosphericSky(vec3 viewDir, vec3 sunDir, vec3 sunRadiance, vec3 ambientSky) {
    float zenith = clamp(viewDir.y, 0.0, 1.0);
    vec3 horizonColor = mix(u_FogColor.rgb, ambientSky * 1.35, 0.5);
    vec3 zenithColor  = ambientSky * 0.75;
    vec3 sky = mix(horizonColor, zenithColor, pow(zenith, 0.6));

    // Solar disk & Mie aureole
    float sunDot = max(dot(viewDir, sunDir), 0.0);
    vec3 mieGlow = sunRadiance * 0.15 * pow(sunDot, 16.0);
    vec3 sunDisk = sunRadiance * 4.0 * smoothstep(0.9993, 0.9998, sunDot);
    return sky + mieGlow + sunDisk;
}

void main() {
    float depth = texture(u_Depth, v_Uv).r;
    vec3 L_sun = normalize(u_SunDirection.xyz);
    vec3 sunRadiance = u_SunColor.rgb * u_SunColor.a;

    // Sky pixels (far plane depth == 1.0)
    if (depth >= 0.999999) {
        vec3 worldFar = reconstructWorldPos(v_Uv, 1.0);
        vec3 viewDir = normalize(worldFar - u_CameraPos.xyz);
        vec3 skyColor = evaluateAtmosphericSky(viewDir, L_sun, sunRadiance, u_AmbientColor.rgb);
        outHdrColor = vec4(skyColor, 1.0);
        return;
    }

    // Unpack G-Buffer MRTs
    vec4 albedoRoughness = texture(u_AlbedoRoughness, v_Uv);
    vec4 normalMetallic  = texture(u_NormalMetallic, v_Uv);
    vec4 lightEmission   = texture(u_LightEmission, v_Uv);

    // Fallback A: When rendered with Nvidium's default shader, Attachment 0 contains
    // pre-lit vanilla diffuse, while normal and light buffers are unwritten (0).
    // In that case, output the pre-lit color directly so terrain is always 100% visible!
    if (length(lightEmission.rg) < 0.001 && length(normalMetallic.xyz) < 0.01) {
        outHdrColor = vec4(albedoRoughness.rgb, 1.0);
        return;
    }

    vec3 worldPos = reconstructWorldPos(v_Uv, depth);
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

    vec3  albedo     = albedoRoughness.rgb;
    float roughness  = clamp(albedoRoughness.a, 0.04, 1.0);
    float metallic   = clamp(normalMetallic.a, 0.0, 1.0);
    float blockLight = lightEmission.r;
    float skyLight   = lightEmission.g;
    float emissive   = lightEmission.b;

    // 1. PBR Cook-Torrance GGX Direct Sunlight with Soft Cascaded PCF Shadows
    float shadow = sampleCascadedShadow(worldPos, N, L_sun, viewDist);
    vec3 directSun = evaluatePbrBRDF(N, V, L_sun, albedo, roughness, metallic, sunRadiance) * shadow;

    // 2. Dynamic Colored Torchlight with Inverse-Square Physical Falloff
    // Convert normalized Minecraft block light [0, 1] (0..15 levels) to physical distance
    float lightDeficit = (1.0 - blockLight) * 15.0;
    float torchDist = max(0.65, lightDeficit + 0.65);
    float inverseSquareAtten = pow(blockLight, 2.0) / (torchDist * torchDist + 0.35);
    // Subtle warm blackbody flicker (~2400K-2900K)
    float flicker = 1.0 + 0.03 * sin(u_Time * 11.0 + worldPos.x * 2.3 + worldPos.z * 2.7);
    vec3 torchSpectrum = mix(vec3(1.0, 0.36, 0.08), vec3(1.0, 0.72, 0.35), blockLight);
    vec3 torchRadiance = torchSpectrum * (inverseSquareAtten * 18.0 * flicker);
    // Approximate local light direction lifted slightly toward viewer & surface normal
    vec3 L_torch = normalize(N + V * 0.45 + vec3(0.0, 0.35, 0.0));
    vec3 torchLighting = evaluatePbrBRDF(N, V, L_torch, albedo, roughness, metallic, torchRadiance);

    // 3. Realistic Atmospheric Skylight with Subtle Ambient Occlusion
    float microAO = computeMicroCreviceAO(v_Uv, worldPos, N, viewDist);
    float cavityAO = mix(0.35, 1.0, skyLight * skyLight) * microAO;
    float hemiFactor = N.y * 0.5 + 0.5;
    vec3 groundBounce = u_AmbientColor.rgb * 0.22;
    vec3 hemiSkyColor = mix(groundBounce, u_AmbientColor.rgb, hemiFactor);

    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    vec3 kS_amb = fresnelSchlickRoughness(max(dot(N, V), 0.0), F0, roughness);
    vec3 kD_amb = (1.0 - kS_amb) * (1.0 - metallic);
    vec3 ambientDiffuse  = kD_amb * albedo * hemiSkyColor;
    vec3 ambientSpecular = kS_amb * hemiSkyColor * (1.0 - roughness * 0.75);
    vec3 ambientLighting = (ambientDiffuse + ambientSpecular) * (skyLight * skyLight * cavityAO) + (albedo * 0.008 * microAO);

    // LabPBR Emissive surfaces
    vec3 emissionLighting = albedo * (emissive * emissive * 24.0);

    vec3 litColor = directSun + torchLighting + ambientLighting + emissionLighting;

    // 4. Smooth Distance Fog Blending into the Atmospheric Sky Color
    vec3 viewDir = -V;
    vec3 skyHorizonColor = evaluateAtmosphericSky(viewDir, L_sun, sunRadiance, u_AmbientColor.rgb);
    vec3 fogTargetColor = mix(u_FogColor.rgb, skyHorizonColor, 0.65);

    float fogSpan = max(u_FogEnd - u_FogStart, 0.001);
    float linearFog = smoothstep(u_FogStart, u_FogEnd, viewDist);
    float expFog = 1.0 - exp(-max(0.0, viewDist - u_FogStart * 0.35) * (1.75 / max(u_FogEnd, 32.0)));
    float fogFactor = clamp(mix(expFog, linearFog, 0.45), 0.0, 1.0);

    vec3 finalHdr = mix(litColor, fogTargetColor, fogFactor);
    outHdrColor = vec4(max(finalHdr, vec3(0.0)), 1.0);
}
