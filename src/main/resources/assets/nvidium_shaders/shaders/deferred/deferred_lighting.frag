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

layout(binding = 0) uniform sampler2D u_AlbedoRoughness;
layout(binding = 1) uniform sampler2D u_NormalMetallic;
layout(binding = 2) uniform sampler2D u_LightEmission;
layout(binding = 3) uniform sampler2D u_Depth;
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outHdrColor;

const float PI = 3.14159265359;

const vec2 POISSON_DISK[16] = vec2[16](
    vec2(-0.94201624, -0.39906216),
    vec2( 0.94558609, -0.76890725),
    vec2(-0.09418410, -0.92938870),
    vec2( 0.34495938,  0.29387760),
    vec2(-0.91588581,  0.45771432),
    vec2(-0.81544232, -0.87912464),
    vec2(-0.38277543,  0.27676845),
    vec2( 0.97484398,  0.75648379),
    vec2( 0.44323325, -0.97511554),
    vec2( 0.53742981, -0.47373420),
    vec2(-0.26496911, -0.41893023),
    vec2( 0.79197514,  0.19090188),
    vec2(-0.24188840,  0.99706507),
    vec2(-0.81409955,  0.91437590),
    vec2( 0.19984126,  0.78641367),
    vec2( 0.14383161, -0.14100790)
);

vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}

float interleavedGradientNoise(vec2 pixelCoord) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    float frameOffset = float(u_FrameIndex % 64) * 5.588238;
    return fract(magic.z * fract(dot(pixelCoord + frameOffset, magic.xy)));
}

float distributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    return a2 / (PI * denom * denom + 0.00001);
}

float geometrySchlickGGX(float NdotV, float roughness) {
    float r = (roughness + 1.0);
    float k = (r * r) / 8.0;
    return NdotV / (NdotV * (1.0 - k) + k);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 renderAtmosphericSky(vec3 viewDir, vec3 sunDir) {
    float elevation = clamp(viewDir.y, 0.0, 1.0);
    vec3 zenithColor = max(u_AmbientColor.rgb * 1.25, vec3(0.10, 0.25, 0.58));
    vec3 horizonColor = mix(u_FogColor.rgb, u_SunColor.rgb * 0.6, 0.2);
    vec3 sky = mix(horizonColor, zenithColor, pow(elevation, 0.6));

    float cosTheta = dot(viewDir, sunDir);
    float g = 0.78;
    float miePhase = (1.0 - g * g) / (4.0 * PI * pow(1.0 + g * g - 2.0 * g * max(cosTheta, 0.0), 1.5));
    sky += u_SunColor.rgb * u_SunColor.a * miePhase * 0.12;

    float sunDisk = smoothstep(0.9993, 0.99985, cosTheta);
    sky += u_SunColor.rgb * u_SunColor.a * sunDisk * 18.0;

    return sky;
}

float sampleCascadedShadowPCF(vec3 worldPos, vec3 N, vec3 L, float viewDist) {
    int cascadeIdx = 2;
    if (viewDist < u_CascadeSplits.x) cascadeIdx = 0;
    else if (viewDist < u_CascadeSplits.y) cascadeIdx = 1;

    float normalOffsetScale = (cascadeIdx == 0) ? 0.025 : ((cascadeIdx == 1) ? 0.065 : 0.15);
    vec3 offsetPos = worldPos + N * normalOffsetScale * clamp(1.0 - dot(N, L), 0.15, 1.0);

    vec4 shadowClip = u_CascadeViewProj[cascadeIdx] * vec4(offsetPos, 1.0);
    vec3 shadowNdc = shadowClip.xyz / shadowClip.w;
    vec3 shadowUv = shadowNdc * 0.5 + 0.5;

    if (shadowUv.x <= 0.001 || shadowUv.x >= 0.999 || shadowUv.y <= 0.001 || shadowUv.y >= 0.999 || shadowUv.z >= 1.0) {
        return 1.0;
    }

    float angle = interleavedGradientNoise(gl_FragCoord.xy) * 2.0 * PI;
    float s = sin(angle);
    float c = cos(angle);
    mat2 rot = mat2(c, -s, s, c);

    float filterRadius = (cascadeIdx == 0) ? (1.75 / 2048.0) : ((cascadeIdx == 1) ? (1.35 / 2048.0) : (1.0 / 2048.0));
    float slopeBias = max(0.00075 * (1.0 - dot(N, L)), 0.00025) * float(cascadeIdx + 1);

    float shadowSum = 0.0;
    for (int i = 0; i < 16; ++i) {
        vec2 offset = (rot * POISSON_DISK[i]) * filterRadius;
        shadowSum += texture(u_ShadowMap, vec4(shadowUv.xy + offset, float(cascadeIdx), shadowUv.z - slopeBias));
    }

    return shadowSum / 16.0;
}

float computeMicroCreviceAO(vec2 uv, vec3 worldPos, vec3 N, float viewDist) {
    vec2 texelSize = 1.0 / u_ScreenSize;
    float ao = 0.0;
    const vec2 dirs[4] = vec2[4](vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0));
    float radius = clamp(2.5 / max(viewDist * 0.15, 1.0), 1.0, 4.0);

    for (int i = 0; i < 4; ++i) {
        vec2 sampleUv = uv + dirs[i] * texelSize * radius;
        float sampleDepth = texture(u_Depth, sampleUv).r;
        if (sampleDepth >= 0.999999) continue;

        vec3 samplePos = reconstructWorldPos(sampleUv, sampleDepth);
        vec3 diff = samplePos - worldPos;
        float dist = length(diff);
        if (dist > 0.001 && dist < 1.25) {
            float horizon = max(dot(N, diff / dist) - 0.08, 0.0);
            float attenuation = 1.0 - clamp(dist / 1.25, 0.0, 1.0);
            ao += horizon * attenuation;
        }
    }
    return clamp(1.0 - ao * 0.55, 0.25, 1.0);
}

void main() {
    float depth = texture(u_Depth, v_Uv).r;
    vec3 worldPos = reconstructWorldPos(v_Uv, depth);
    vec3 viewVec = worldPos - u_CameraPos.xyz;
    float viewDist = length(viewVec);
    vec3 V = -normalize(viewVec);
    vec3 L = normalize(u_SunDirection.xyz);

    if (depth >= 0.999999) {
        outHdrColor = vec4(renderAtmosphericSky(-V, L), 1.0);
        return;
    }

    vec4 albedoRoughness = texture(u_AlbedoRoughness, v_Uv);
    vec4 normalMetallic  = texture(u_NormalMetallic, v_Uv);
    vec4 lightEmission   = texture(u_LightEmission, v_Uv);

    if (length(lightEmission.rg) < 0.001 && length(normalMetallic.xyz) < 0.01) {
        outHdrColor = vec4(pow(albedoRoughness.rgb, vec3(2.2)), 1.0);
        return;
    }

    vec3 albedo = pow(max(albedoRoughness.rgb, vec3(0.0)), vec3(2.2));
    float roughness = clamp(albedoRoughness.a, 0.04, 1.0);
    float metallic  = clamp(normalMetallic.a, 0.0, 1.0);

    vec3 rawNormal = normalMetallic.xyz;
    vec3 N;
    if (length(rawNormal) < 0.01) {
        N = normalize(cross(dFdx(worldPos), dFdy(worldPos)));
    } else {
        vec3 decoded = (rawNormal.x < -0.001 || rawNormal.y < -0.001 || rawNormal.z < -0.001)
                       ? rawNormal
                       : (rawNormal * 2.0 - 1.0);
        N = normalize(decoded);
    }

    float blockLight = clamp(lightEmission.r, 0.0, 1.0);
    float skyLight   = clamp(lightEmission.g, 0.0, 1.0);
    float emission   = clamp(lightEmission.b, 0.0, 1.0);

    vec3 H = normalize(V + L);
    float NdotV = max(dot(N, V), 0.001);
    float NdotL = max(dot(N, L), 0.0);

    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    vec3 F  = fresnelSchlick(max(dot(H, V), 0.0), F0);
    float D = distributionGGX(N, H, roughness);
    float G = geometrySchlickGGX(NdotV, roughness) * geometrySchlickGGX(max(NdotL, 0.001), roughness);

    vec3 specular = (D * G * F) / max(4.0 * NdotV * max(NdotL, 0.001), 0.001);
    vec3 kS = F;
    vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);

    float shadow = (NdotL > 0.0) ? sampleCascadedShadowPCF(worldPos, N, L, viewDist) : 0.0;
    shadow *= smoothstep(0.02, 0.25, skyLight);

    vec3 sunRadiance = u_SunColor.rgb * max(u_SunColor.a, 1.0);
    vec3 directLighting = (kD * albedo / PI + specular) * sunRadiance * NdotL * shadow;

    float torchDist = max((1.0 - blockLight) * 15.0, 0.35);
    float window = clamp(1.0 - pow(torchDist / 15.0, 4.0), 0.0, 1.0);
    window = window * window;
    float attenuation = window / (torchDist * torchDist + 0.85);

    float flicker = 1.0 + 0.035 * sin(u_Time * 13.7 + worldPos.x * 2.3 + worldPos.z * 1.9)
                        + 0.020 * cos(u_Time * 23.1 - worldPos.y * 3.1);
    vec3 emberColor = vec3(1.0, 0.28, 0.04);
    vec3 flameColor = vec3(1.0, 0.68, 0.32);
    vec3 torchSpectrum = mix(emberColor, flameColor, pow(blockLight, 1.35));
    vec3 torchLighting = albedo * torchSpectrum * attenuation * flicker * 3.4;

    float creviceAO = computeMicroCreviceAO(v_Uv, worldPos, N, viewDist);
    float hemiFactor = N.y * 0.5 + 0.5;
    vec3 groundBounce = u_AmbientColor.rgb * vec3(0.45, 0.38, 0.30);
    vec3 skyZenith    = u_AmbientColor.rgb * vec3(0.85, 0.95, 1.15);
    vec3 hemiIrradiance = mix(groundBounce, skyZenith, hemiFactor);

    float caveBaseAmbient = 0.018;
    vec3 ambientLighting = kD * albedo * (hemiIrradiance * (skyLight * skyLight) + vec3(caveBaseAmbient)) * creviceAO;

    vec3 emissiveLighting = albedo * (emission * emission) * 12.0;

    vec3 totalColor = directLighting + torchLighting + ambientLighting + emissiveLighting;

    float fogRange = max(u_FogEnd - u_FogStart, 1.0);
    float fogDist  = max(viewDist - u_FogStart * 0.65, 0.0);
    float expFog   = 1.0 - exp(-pow(fogDist / fogRange, 1.65) * 2.8);
    vec3 horizonSky = renderAtmosphericSky(normalize(vec3(viewVec.x, max(viewVec.y, 0.02), viewVec.z)), L);
    vec3 fogTarget  = mix(u_FogColor.rgb, horizonSky, 0.45);

    totalColor = mix(totalColor, fogTarget, clamp(expFog, 0.0, 1.0));

    outHdrColor = vec4(max(totalColor, vec3(0.0)), 1.0);
}
