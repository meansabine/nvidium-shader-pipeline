#ifndef NVIDIUM_SHADOWS_GLSL
#define NVIDIUM_SHADOWS_GLSL

const vec2 PCF_DISK_9[9] = vec2[](
    vec2( 0.0000,  0.0000),
    vec2(-0.7071, -0.7071),
    vec2( 0.7071, -0.7071),
    vec2(-0.7071,  0.7071),
    vec2( 0.7071,  0.7071),
    vec2( 0.0000, -1.0000),
    vec2( 0.0000,  1.0000),
    vec2(-1.0000,  0.0000),
    vec2( 1.0000,  0.0000)
);

const float PCF_WEIGHTS_9[9] = float[](
    0.20,
    0.09, 0.09, 0.09, 0.09,
    0.11, 0.11, 0.11, 0.11
);

mat4 getCascadeViewProj(int cascadeIdx) {
    if (cascadeIdx == 0) return u_CascadeViewProj0;
    if (cascadeIdx == 1) return u_CascadeViewProj1;
    return u_CascadeViewProj2;
}

float sampleShadowBilinear(sampler2DArray csmArray, vec2 uv, float layer, float refDepth) {
    float res = u_ShadowParams.x;
    float invRes = u_ShadowParams.y;
    vec2 st = uv * res - 0.5;
    vec2 base = (floor(st) + 0.5) * invRes;
    vec2 f = fract(st);

    float d00 = (refDepth <= textureLod(csmArray, vec3(base, layer), 0.0).r) ? 1.0 : 0.0;
    float d10 = (refDepth <= textureLod(csmArray, vec3(base + vec2(invRes, 0.0), layer), 0.0).r) ? 1.0 : 0.0;
    float d01 = (refDepth <= textureLod(csmArray, vec3(base + vec2(0.0, invRes), layer), 0.0).r) ? 1.0 : 0.0;
    float d11 = (refDepth <= textureLod(csmArray, vec3(base + vec2(invRes, invRes), layer), 0.0).r) ? 1.0 : 0.0;

    return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
}

float sampleSingleCascadePCF(
        sampler2DArray csmArray,
        vec3 camRelPos,
        vec3 worldNormal,
        vec3 lightDir,
        int cascadeIdx,
        float filterScale,
        float rotAngle) {
    float normalBiasScale = (cascadeIdx == 0) ? 0.045 : ((cascadeIdx == 1) ? 0.095 : 0.19);
    float NoL = clamp(dot(worldNormal, lightDir), 0.0, 1.0);
    vec3 biasedPos = camRelPos + worldNormal * (normalBiasScale * (1.25 - NoL * 0.65));

    mat4 lightVP = getCascadeViewProj(cascadeIdx);
    vec4 lightClip = lightVP * vec4(biasedPos, 1.0);
    vec3 projCoords = lightClip.xyz / max(lightClip.w, 1.0e-6);

    // OpenGL standard clip space [-1..1] -> depth buffer [0..1]
    vec2 uv = projCoords.xy * 0.5 + 0.5;
    float currentDepth = projCoords.z * 0.5 + 0.5;

    if (uv.x <= 0.002 || uv.x >= 0.998 || uv.y <= 0.002 || uv.y >= 0.998 || currentDepth <= 0.0 || currentDepth >= 1.0) {
        return 1.0;
    }

    float slopeBias = max(0.00045 * (1.0 - NoL), 0.00014) * float(cascadeIdx + 1);
    float cascadeRadiusScale = (cascadeIdx == 0) ? 1.35 : ((cascadeIdx == 1) ? 1.10 : 0.85);
    float radiusTexels = cascadeRadiusScale * max(0.25, filterScale);
    vec2 texelSize = vec2(u_ShadowParams.y);
    float refDepth = currentDepth - slopeBias;
    float layer = float(cascadeIdx);

    float shadowSum = 0.0;
    float cRot = cos(rotAngle);
    float sRot = sin(rotAngle);
    mat2 diskRot = mat2(cRot, -sRot, sRot, cRot);
    for (int i = 0; i < 9; i++) {
        vec2 offset = (diskRot * PCF_DISK_9[i]) * texelSize * radiusTexels;
        shadowSum += sampleShadowBilinear(csmArray, uv + offset, layer, refDepth) * PCF_WEIGHTS_9[i];
    }
    return shadowSum;
}

/**
 * Evaluates 3-cascade shadow visibility [0..1] with smooth inter-cascade blending and distance fade.
 */
float evaluateCascadedShadow(
        sampler2DArray csmArray,
        vec3 camRelPos,
        vec3 worldNormal,
        vec3 lightDir,
        float filterScale,
        vec2 pixelCoord,
        out int outCascadeIdx) {
    float shadowFade = u_ShadowLightDir.w;
    if (shadowFade <= 0.001) {
        outCascadeIdx = 2;
        return 1.0;
    }

    float dist = length(camRelPos);
    float maxDist = u_CascadeSplits.w;
    if (dist >= maxDist) {
        outCascadeIdx = 2;
        return 1.0;
    }

    int cascadeIdx = 2;
    if (dist < u_CascadeSplits.x) {
        cascadeIdx = 0;
    } else if (dist < u_CascadeSplits.y) {
        cascadeIdx = 1;
    }
    outCascadeIdx = cascadeIdx;

    float taaPhase = (u_DebugParams.y > 0.5) ? mod(u_Time.z, 8.0) : 0.0;
    float rotAngle = interleavedGradientNoise(pixelCoord, taaPhase) * TAU;
    float shadow = sampleSingleCascadePCF(csmArray, camRelPos, worldNormal, lightDir, cascadeIdx, filterScale, rotAngle);

    // Smooth blend across cascade boundaries so seams are invisible
    float splitEnd = (cascadeIdx == 0) ? u_CascadeSplits.x : ((cascadeIdx == 1) ? u_CascadeSplits.y : u_CascadeSplits.z);
    float blendZone = splitEnd * 0.15;
    if (cascadeIdx < 2 && dist > (splitEnd - blendZone)) {
        float blendFactor = clamp((dist - (splitEnd - blendZone)) / max(blendZone, 0.001), 0.0, 1.0);
        float nextShadow = sampleSingleCascadePCF(csmArray, camRelPos, worldNormal, lightDir, cascadeIdx + 1, filterScale, rotAngle);
        shadow = mix(shadow, nextShadow, blendFactor);
    }

    // Smooth distance fade at far edge of Cascade 2
    float farFade = smoothstep(maxDist, maxDist * 0.82, dist);
    shadow = mix(1.0, shadow, farFade * shadowFade);

    return shadow;
}

/**
 * Fast single-tap shadow lookup for volumetric light-shaft raymarching.
 */
float sampleVolumetricShadowTap(sampler2DArray csmArray, vec3 camRelPos) {
    float dist = length(camRelPos);
    if (dist >= u_CascadeSplits.w) return 1.0;

    int cascadeIdx = (dist < u_CascadeSplits.x) ? 0 : ((dist < u_CascadeSplits.y) ? 1 : 2);
    mat4 lightVP = getCascadeViewProj(cascadeIdx);
    vec4 lightClip = lightVP * vec4(camRelPos, 1.0);
    vec3 projCoords = lightClip.xyz / max(lightClip.w, 1.0e-6);
    vec2 uv = projCoords.xy * 0.5 + 0.5;
    float currentDepth = projCoords.z * 0.5 + 0.5;
    if (uv.x <= 0.0 || uv.x >= 1.0 || uv.y <= 0.0 || uv.y >= 1.0 || currentDepth <= 0.0 || currentDepth >= 1.0) {
        return 1.0;
    }
    float closestDepth = texture(csmArray, vec3(uv, float(cascadeIdx))).r;
    return (currentDepth - 0.0012 <= closestDepth) ? 1.0 : 0.0;
}

#endif
