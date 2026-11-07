#version 460 core

in vec2 v_TexCoord;
layout(location = 0) out vec4 outHdrColor;

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"
#include "../common/materials.glsl"
#include "../common/pbr_brdf.glsl"
#include "../common/shadows.glsl"
#include "../common/atmosphere.glsl"

layout(binding = 0) uniform sampler2D gAlbedoRoughness;
layout(binding = 1) uniform sampler2D gNormalMetallic;
layout(binding = 2) uniform sampler2D gLightEmission;
layout(binding = 3) uniform sampler2D gDepth;
layout(binding = 4) uniform sampler2DArray shadowMapArray;

/**
 * 4-Direction Horizon-Based Screen-Space Ambient Occlusion (GTAO) with spatial-temporal jitter.
 */
float computeHorizonGTAO(vec2 uv, vec3 camRelPos, vec3 worldNormal, float vertexAO) {
    if (u_ShadowParams.w < 0.5) {
        return vertexAO;
    }

    float viewDist = length(camRelPos);
    if (viewDist > 160.0) {
        return vertexAO;
    }

    vec2 texel = u_ScreenSize.zw;
    float radiusPixels = clamp(22.0 / max(viewDist * 0.35, 1.0), 3.0, 18.0);
    float taaPhase = (u_DebugParams.y > 0.5) ? mod(u_Time.z, 8.0) : 0.0;
    float noise = interleavedGradientNoise(gl_FragCoord.xy, taaPhase);
    float baseAngle = noise * HALF_PI;

    float occlusion = 0.0;
    const int DIRS = 4;
    const int STEPS = 3;

    for (int d = 0; d < DIRS; d++) {
        float angle = baseAngle + float(d) * (TAU / float(DIRS));
        vec2 dir = vec2(cos(angle), sin(angle));

        for (int s = 1; s <= STEPS; s++) {
            float stepFrac = (float(s) - 0.35 + noise * 0.3) / float(STEPS);
            vec2 sampleUV = uv + dir * (stepFrac * radiusPixels) * texel;
            if (sampleUV.x <= 0.0 || sampleUV.x >= 1.0 || sampleUV.y <= 0.0 || sampleUV.y >= 1.0) {
                continue;
            }
            float sampleDepth = textureLod(gDepth, sampleUV, 0.0).r;
            if (sampleDepth >= 0.9999) continue;

            vec3 sampleRelPos = reconstructCameraRelativePos(sampleUV, sampleDepth);
            vec3 diff = sampleRelPos - camRelPos;
            float dist2 = dot(diff, diff);
            float dist = sqrt(max(dist2, 1.0e-5));

            if (dist < 2.6 && dist > 0.02) {
                vec3 horizonVec = diff / dist;
                float nDotH = clamp(dot(worldNormal, horizonVec) - 0.08, 0.0, 1.0);
                float weight = clamp(1.0 - (dist / 2.6), 0.0, 1.0);
                occlusion += nDotH * weight;
            }
        }
    }

    float ssao = clamp(1.0 - (occlusion / float(DIRS * STEPS)) * 1.45, 0.20, 1.0);
    float distFade = smoothstep(160.0, 96.0, viewDist);
    ssao = mix(1.0, ssao, distFade);

    return clamp(ssao * vertexAO, 0.08, 1.0);
}

/**
 * Animated Voronoi-like water caustics pattern for underwater surfaces.
 */
float evaluateWaterCaustics(vec3 worldPos, float timeSec) {
    vec2 p1 = worldPos.xz * 0.72 + vec2(timeSec * 0.45, -timeSec * 0.32);
    vec2 p2 = worldPos.xz * 0.95 + vec2(-timeSec * 0.38, timeSec * 0.51);
    float n1 = abs(noise2D(p1 * 2.2) - 0.5);
    float n2 = abs(noise2D(p2 * 2.5) - 0.5);
    float c = clamp(1.0 - (n1 + n2) * 3.4, 0.0, 1.0);
    return pow(c, 2.2) * 1.65;
}

/**
 * Screen-Space Raymarched Reflection (SSR) for smooth metals, polished blocks, ice, and rain puddles.
 */
vec4 traceScreenSpaceReflection(vec3 camRelPos, vec3 R, float roughness, vec2 pixelCoord) {
    if (u_ShadowParams.z < 0.5 || roughness > 0.38) {
        return vec4(0.0);
    }

    float jitter = interleavedGradientNoise(pixelCoord, 0.0);
    vec3 rayPos = camRelPos + R * (0.18 + jitter * 0.15);
    vec3 stepVec = R * 0.42;

    for (int i = 0; i < 14; i++) {
        rayPos += stepVec;
        stepVec *= 1.16;

        vec3 uvDepth = projectCameraRelativeToUVDepth(rayPos);
        if (uvDepth.x <= 0.01 || uvDepth.x >= 0.99 || uvDepth.y <= 0.01 || uvDepth.y >= 0.99 || uvDepth.z <= 0.0 || uvDepth.z >= 1.0) {
            break;
        }

        float sceneDepth = textureLod(gDepth, uvDepth.xy, 0.0).r;
        if (sceneDepth >= 0.9999) continue;

        vec3 sceneRelPos = reconstructCameraRelativePos(uvDepth.xy, sceneDepth);
        float depthDiff = length(rayPos) - length(sceneRelPos);

        if (depthDiff > 0.0 && depthDiff < length(stepVec) * 1.85) {
            vec3 hitAlbedo = textureLod(gAlbedoRoughness, uvDepth.xy, 0.0).rgb;
            vec4 hitLight = textureLod(gLightEmission, uvDepth.xy, 0.0);
            vec3 hitColor = hitAlbedo * (u_SkyColor.rgb * hitLight.g * 1.1 + u_BlockLightColor.rgb * hitLight.r * 1.2 + hitAlbedo * hitLight.b * 2.0);
            vec2 edgeDist = smoothstep(vec2(0.0), vec2(0.12), uvDepth.xy) * (1.0 - smoothstep(vec2(0.88), vec2(1.0), uvDepth.xy));
            float confidence = edgeDist.x * edgeDist.y * (1.0 - roughness / 0.38);
            return vec4(hitColor, clamp(confidence, 0.0, 1.0));
        }
    }
    return vec4(0.0);
}

vec3 materialIdDebugColor(uint matId) {
    if (matId == MAT_WATER)          return vec3(0.10, 0.45, 1.00);
    if (matId == MAT_LAVA)           return vec3(1.00, 0.28, 0.02);
    if (matId == MAT_FOLIAGE_WAVING) return vec3(0.25, 0.90, 0.20);
    if (matId == MAT_LEAVES)         return vec3(0.10, 0.55, 0.15);
    if (matId == MAT_GLASS)          return vec3(0.75, 0.95, 1.00);
    if (matId == MAT_ICE)            return vec3(0.45, 0.80, 1.00);
    if (matId == MAT_EMISSIVE)       return vec3(1.00, 0.92, 0.25);
    if (matId == MAT_METAL)          return vec3(0.80, 0.82, 0.88);
    if (matId == MAT_GEM)            return vec3(0.20, 0.95, 0.82);
    if (matId == MAT_POLISHED)       return vec3(0.62, 0.45, 0.78);
    if (matId == MAT_WET)            return vec3(0.38, 0.28, 0.20);
    if (matId == MAT_SAND_SNOW)      return vec3(0.95, 0.88, 0.65);
    if (matId == MAT_PORTAL)         return vec3(0.75, 0.15, 1.00);
    if (matId == MAT_SLIME_HONEY)    return vec3(0.55, 0.95, 0.25);
    if (matId == MAT_ORE)            return vec3(1.00, 0.55, 0.15);
    return vec3(0.45, 0.45, 0.45);
}

void main() {
    float depth = textureLod(gDepth, v_TexCoord, 0.0).r;

    //Camera-relative position (camera is at 0,0,0)
    vec3 camRelPos = reconstructCameraRelativePos(v_TexCoord, depth);
    vec3 V = normalize(camRelPos); // Ray direction from camera toward pixel
    vec3 viewToCam = -V;           // Direction from surface toward camera

    // 1. Sky Background
    if (depth >= 0.99999) {
        vec3 skyHdr = evaluatePhysicalSky(V, OPT_CLOUD_MODE, OPT_CLOUD_COVERAGE, OPT_ATMOS_HAZINESS);
        outHdrColor = vec4(skyHdr, 1.0);
        return;
    }

    vec3 worldPos = camRelPos + u_CameraPos.xyz;
    float viewDist = length(camRelPos);

    vec4 albedoRoughness = textureLod(gAlbedoRoughness, v_TexCoord, 0.0);
    vec4 normalMetallic  = textureLod(gNormalMetallic, v_TexCoord, 0.0);
    vec4 lightEmission   = textureLod(gLightEmission, v_TexCoord, 0.0);

    // Fallback safety if a non-G-Buffer pixel wrote to attachment 0 only
    if (dot(normalMetallic.xyz, normalMetallic.xyz) < 0.001) {
        outHdrColor = vec4(albedoRoughness.rgb, 1.0);
        return;
    }

    vec3 albedo = albedoRoughness.rgb;
    float roughness = clamp(albedoRoughness.a, 0.03, 1.0);
    vec3 N = normalize(normalMetallic.xyz);

    float metallic;
    uint materialId;
    unpackMetallicAndMaterial(normalMetallic.a, metallic, materialId);

    float blockLight = lightEmission.r;
    float skyLight   = lightEmission.g;
    float emissive   = lightEmission.b;
    float vertexAO   = clamp(lightEmission.a, 0.08, 1.0);

    // Subsurface amount from material ID
    float subsurface = 0.0;
    if (OPT_SUBSURFACE_SCATTERING > 0.5) {
        if (materialId == MAT_FOLIAGE_WAVING) subsurface = 0.78;
        else if (materialId == MAT_LEAVES)    subsurface = 0.65;
        else if (materialId == MAT_ICE)       subsurface = 0.55;
        else if (materialId == MAT_SAND_SNOW) subsurface = 0.38;
        else if (materialId == MAT_SLIME_HONEY) subsurface = 0.72;
        else if (materialId == MAT_GEM)       subsurface = 0.28;
    }

    // 2. Outdoor Rain Puddles & Raindrop Ripples
    float puddleMask = 0.0;
    float wetness = u_Weather.y;
    if (wetness > 0.01 && OPT_RAIN_PUDDLES > 0.5 && N.y > 0.75 && skyLight > 0.70 && materialId != MAT_LAVA && materialId != MAT_EMISSIVE) {
        float puddleNoise = fbm2D(worldPos.xz * 0.32);
        float outdoor = smoothstep(0.70, 0.92, skyLight) * smoothstep(0.75, 0.95, N.y);
        puddleMask = smoothstep(0.54 - wetness * 0.22, 0.68 - wetness * 0.15, puddleNoise) * wetness * outdoor;

        if (puddleMask > 0.01) {
            // Animated raindrop rings inside puddles
            vec2 dropCell = floor(worldPos.xz * 2.5);
            vec2 dropFrac = fract(worldPos.xz * 2.5) - 0.5;
            float dropPhase = fract(u_Time.x * 1.8 + hash12(dropCell));
            float ringDist = length(dropFrac);
            float ripple = sin((ringDist - dropPhase * 0.45) * 42.0) * exp(-ringDist * 8.0) * (1.0 - dropPhase);
            vec3 rippleNormal = normalize(vec3(dropFrac.x * ripple * 0.18, 1.0, dropFrac.y * ripple * 0.18));

            N = normalize(mix(N, rippleNormal, puddleMask * 0.88));
            roughness = mix(roughness, 0.025, puddleMask * 0.92);
            albedo = mix(albedo, albedo * 0.72, puddleMask * 0.5);
        }
    }

    // 3. Horizon GTAO + Multi-Bounce Ambient Occlusion
    float combinedAO = computeHorizonGTAO(v_TexCoord, camRelPos, N, vertexAO);
    vec3 multiBounceAO = gtaoMultiBounce(combinedAO, albedo);

    // 4. Cascaded Shadow Evaluation (Sun by day, Moon by night)
    vec3 shadowLightDir = normalize(u_ShadowLightDir.xyz);
    int activeCascadeIdx = 0;
    float shadowFactor = 1.0;

    int dimId = int(round(u_FogParams.w));
    if (dimId == 0 && skyLight > 0.03) {
        shadowFactor = evaluateCascadedShadow(
                shadowMapArray,
                camRelPos,
                N,
                shadowLightDir,
                OPT_SHADOW_SOFTNESS,
                gl_FragCoord.xy,
                activeCascadeIdx
        );
        // Attenuate direct sunlight in deep caves where skyLight approaches 0
        float caveSkyGate = smoothstep(0.03, 0.35, skyLight);
        shadowFactor *= caveSkyGate;
    }

    // Debug visualization modes (1..6)
    int debugMode = int(round(u_DebugParams.x));
    if (debugMode == 1) {
        outHdrColor = vec4(albedo, 1.0);
        return;
    } else if (debugMode == 2) {
        outHdrColor = vec4(N * 0.5 + 0.5, 1.0);
        return;
    } else if (debugMode == 3) {
        outHdrColor = vec4(blockLight, skyLight, emissive, 1.0);
        return;
    } else if (debugMode == 4) {
        vec3 cascTint = (activeCascadeIdx == 0) ? vec3(0.25, 1.0, 0.35)
                      : ((activeCascadeIdx == 1) ? vec3(1.0, 0.85, 0.20) : vec3(1.0, 0.30, 0.35));
        outHdrColor = vec4(albedo * cascTint * (0.25 + 0.75 * shadowFactor), 1.0);
        return;
    } else if (debugMode == 5) {
        outHdrColor = vec4(materialIdDebugColor(materialId), 1.0);
        return;
    } else if (debugMode == 6) {
        outHdrColor = vec4(vec3(combinedAO), 1.0);
        return;
    }

    // 5. Direct Sun & Moon PBR Lighting
    vec3 sunDir = normalize(u_SunDirection.xyz);
    vec3 moonDir = normalize(u_MoonDirection.xyz);
    float sunIntensity = u_SunColor.w * max(0.1, OPT_SUNLIGHT_INTENSITY);
    float moonIntensity = u_MoonColor.w * max(0.1, OPT_SUNLIGHT_INTENSITY);

    vec3 totalLighting = vec3(0.0);

    if (sunIntensity > 0.001 && sunDir.y > -0.08) {
        float sunShadow = (u_SunDirection.y >= 0.0) ? shadowFactor : 0.0;
        vec3 sunRadiance = u_SunColor.rgb * sunIntensity * sunShadow;
        totalLighting += evaluateDirectPBR(N, viewToCam, sunDir, albedo, roughness, metallic, subsurface, sunRadiance);
    }

    if (moonIntensity > 0.001 && moonDir.y > -0.08) {
        float moonShadow = (u_SunDirection.y < 0.0) ? shadowFactor : 0.0;
        vec3 moonRadiance = u_MoonColor.rgb * moonIntensity * moonShadow;
        totalLighting += evaluateDirectPBR(N, viewToCam, moonDir, albedo, roughness, metallic, subsurface, moonRadiance);
    }

    // Underwater Caustics on sunlit seabed
    if (OPT_WATER_CAUSTICS > 0.5 && (u_CameraPos.w > 0.5 && u_CameraPos.w < 1.5)) {
        float caustics = evaluateWaterCaustics(worldPos, u_Time.x);
        totalLighting += albedo * u_SunColor.rgb * caustics * shadowFactor * skyLight * 0.55;
    }

    // 6. Handheld Colored Dynamic Point Light & World Colored Emissive Point Lights (PointLightUniforms)
    float heldLightLevel = u_Environment.z;
    if (heldLightLevel > 0.05 && OPT_HANDHELD_DYNAMIC_LIGHT > 0.5) {
        // Light source slightly offset to player's hand position
        vec3 handOffset = vec3(0.0, -0.15, 0.0);
        vec3 toLight = handOffset - camRelPos;
        float distSq = dot(toLight, toLight);
        float radius = max(5.0, u_HeldLightColor.w);
        if (distSq < radius * radius) {
            float dist = sqrt(max(distSq, 0.04));
            vec3 L_hand = toLight / dist;
            float rangeAtten = pow(clamp(1.0 - pow(dist / radius, 2.0), 0.0, 1.0), 2.0);
            float invSq = 1.0 / (1.0 + 0.18 * dist + 0.08 * distSq);
            float flicker = 0.96 + 0.04 * sin(u_Time.x * 7.5 + sin(u_Time.x * 19.0));
            vec3 handRadiance = u_HeldLightColor.rgb * (heldLightLevel / 15.0) * 3.2 * rangeAtten * invSq * flicker;

            totalLighting += evaluateDirectPBR(N, viewToCam, L_hand, albedo, roughness, metallic, subsurface * 0.5, handRadiance);
        }
    }

    int pointLightCount = clamp(int(round(u_PointLightMeta.x)), 0, MAX_POINT_LIGHTS);
    if (pointLightCount > 0 && viewDist < 42.0) {
        float distFade = smoothstep(42.0, 26.0, viewDist);
        for (int i = 0; i < pointLightCount; i++) {
            vec4 posRad = u_PointLightPosRadius[i];
            vec3 toPt = posRad.xyz - camRelPos;
            float radius = max(1.0, posRad.w);
            float d2 = dot(toPt, toPt);
            if (d2 < radius * radius) {
                float d = sqrt(max(d2, 0.06));
                vec3 L_pt = toPt / d;
                vec4 colInt = u_PointLightColor[i];
                float window = pow(clamp(1.0 - (d2 / (radius * radius)), 0.0, 1.0), 2.0);
                float atten = window / (1.0 + 0.35 * d + 0.22 * d2);
                vec3 ptRadiance = colInt.rgb * (colInt.w * atten * distFade * 1.35);
                totalLighting += evaluateDirectPBR(N, viewToCam, L_pt, albedo, roughness, metallic, subsurface * 0.35, ptRadiance);
            }
        }
    }

    // 7. Hemispherical Sky Ambient + Indirect Ground Bounce
    float hemiUp = N.y * 0.5 + 0.5;
    vec3 zenithAmb = u_SkyColor.rgb * 0.95 + u_HorizonColor.rgb * 0.25;
    vec3 groundBounce = mix(vec3(0.04, 0.035, 0.03), u_HorizonColor.rgb * 0.35, u_SkyColor.w);
    vec3 skyAmbientIrradiance = mix(groundBounce, zenithAmb, hemiUp) * (skyLight * skyLight * 0.85 + skyLight * 0.15);

    // Minimum cave & night floor + Night Vision support
    float minAmbientFloor = mix(0.018, 0.18, u_Environment.w);
    if (dimId == -1) {
        // Nether ambient glow
        skyAmbientIrradiance = vec3(0.24, 0.09, 0.05) * (0.65 + 0.35 * hemiUp);
    } else if (dimId == 1) {
        // End ambient glow
        skyAmbientIrradiance = vec3(0.14, 0.10, 0.20) * (0.65 + 0.35 * hemiUp);
    }
    skyAmbientIrradiance = max(skyAmbientIrradiance, vec3(minAmbientFloor)) * max(0.1, OPT_AMBIENT_INTENSITY);

    // 8. Physical Warm Blocklight (Torches / Lanterns / Lava) with inverse-square-like falloff curve
    float blockCurve = pow(blockLight, 2.15) * 2.1 + blockLight * 0.22;
    float microCreviceHighlight = 0.85 + 0.15 * clamp(N.y + 0.5, 0.0, 1.0);
    vec3 blockIrradiance = u_BlockLightColor.rgb * u_BlockLightColor.w * blockCurve * microCreviceHighlight;

    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    float NoV = abs(dot(N, viewToCam)) + 1.0e-5;
    vec3 F_env = F_SchlickRoughness(NoV, f0, roughness);
    vec3 kD_env = (vec3(1.0) - F_env) * (1.0 - metallic);

    vec3 diffuseAmbient = kD_env * albedo * (skyAmbientIrradiance + blockIrradiance) * multiBounceAO;
    totalLighting += diffuseAmbient;

    // 9. Environment Specular IBL + Screen-Space Reflections (SSR)
    vec3 R = reflect(-viewToCam, N);
    float skyReflMask = clamp(skyLight * skyLight, 0.05, 1.0);
    vec3 envSkyRefl = (roughness < 0.18)
            ? evaluatePhysicalSky(R, 1.0, OPT_CLOUD_COVERAGE, OPT_ATMOS_HAZINESS) * skyReflMask
            : evaluateAtmosphericHazeSky(R, OPT_ATMOS_HAZINESS) * skyReflMask;
    envSkyRefl += u_BlockLightColor.rgb * pow(blockLight, 2.5) * 0.45;

    if (roughness < 0.42 || puddleMask > 0.05 || metallic > 0.3) {
        vec4 ssr = traceScreenSpaceReflection(camRelPos, R, roughness, gl_FragCoord.xy);
        envSkyRefl = mix(envSkyRefl, ssr.rgb, ssr.a);
    }

    float specOcclusion = clamp(pow(combinedAO, 1.5) + metallic * 0.35, 0.1, 1.0);
    float smoothnessFactor = (1.0 - roughness) * (1.0 - roughness);
    totalLighting += envSkyRefl * F_env * smoothnessFactor * specOcclusion * (0.45 + puddleMask * 0.55 + metallic * 0.55);

    // 10. Emissive Blocks (Torches, Lanterns, Lava, Glowstone, Sculk, Ores, Portals)
    if (emissive > 0.001) {
        vec3 emissiveColor = pow(max(albedo, vec3(0.04)), vec3(0.82)) * emissive * (2.6 * OPT_EMISSIVE_STRENGTH);
        totalLighting += emissiveColor;
    }

    // 11. Aerial Perspective / Distance Atmospheric Haze (disk-free so Sun/Moon never shine through opaque geometry)
    float fogStart = max(32.0, u_FogParams.x * 0.85);
    float fogEnd = max(fogStart + 48.0, u_FogParams.y * 1.15);
    float distHaze = smoothstep(fogStart, fogEnd, viewDist);
    float heightHaze = exp(-max(worldPos.y - 58.0, 0.0) * 0.018) * (1.0 - exp(-viewDist * 0.0028 * OPT_ATMOS_HAZINESS));
    float totalHaze = clamp(distHaze * 0.85 + heightHaze * 0.25 * (1.0 + u_Weather.x), 0.0, 0.96);

    vec3 horizonSky = evaluateAtmosphericHazeSky(normalize(vec3(V.x, max(V.y, 0.04), V.z)), OPT_ATMOS_HAZINESS);
    totalLighting = mix(totalLighting, horizonSky, totalHaze);

    outHdrColor = vec4(max(totalLighting, vec3(0.0)), 1.0);
}
