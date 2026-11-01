#version 460 core

// ============================================================================
// Nvidium Shader Pipeline (NSP) - Phase 5: Water & Translucency Surface Shader
// Implements multi-octave Gerstner wave normals, Beer-Lambert optical
// absorption, screen-space refraction, screen-space raymarched reflections,
// Schlick Fresnel (F0 = 0.02), GGX solar specular with CSM shadows, and foam.
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

layout(binding = 0) uniform sampler2D u_OpaqueSceneColor;
layout(binding = 1) uniform sampler2D u_SceneDepth;
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;

layout(location = 0) in vec3 v_WorldPos;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_LightCoord;

layout(location = 0) out vec4 outHdrColor;

const float PI = 3.14159265359;

vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}

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

float sampleWaterShadow(vec3 worldPos, float viewDist) {
    if (viewDist >= u_CascadeSplits.z) return 1.0;
    int cascadeIdx = selectCascade(viewDist);
    vec3 sc = getShadowCoords(worldPos, cascadeIdx);
    if (sc.x <= 0.0 || sc.x >= 1.0 || sc.y <= 0.0 || sc.y >= 1.0 || sc.z <= 0.0 || sc.z >= 1.0) {
        return 1.0;
    }
    return texture(u_ShadowMap, vec4(sc.xy, float(cascadeIdx), clamp(sc.z - 0.0006, 0.0, 1.0)));
}

// ----------------------------------------------------------------------------
// Requirement 1: Animated Gerstner Wave Normal & Crest Jacobian
// ----------------------------------------------------------------------------
vec3 computeGerstnerNormalAndCrest(vec2 xz, float time, out float crestFactor) {
    // (dirX, dirZ, steepness, wavelength)
    const vec4 waves[6] = vec4[6](
        vec4( 0.85,  0.52, 0.24, 4.20),
        vec4(-0.42,  0.91, 0.20, 2.65),
        vec4( 0.61, -0.79, 0.17, 1.55),
        vec4(-0.88, -0.47, 0.14, 0.88),
        vec4( 0.26,  0.96, 0.11, 0.47),
        vec4(-0.73,  0.68, 0.09, 0.26)
    );

    vec3 tangent   = vec3(1.0, 0.0, 0.0);
    vec3 bitangent = vec3(0.0, 0.0, 1.0);
    float jacobian = 1.0;

    for (int i = 0; i < 6; ++i) {
        vec2  D = normalize(waves[i].xy);
        float Q = waves[i].z;
        float wl = waves[i].w;
        float k = (2.0 * PI) / wl;
        float c = sqrt(9.81 / k);
        float phase = k * (dot(D, xz) - c * time);

        float WA = Q / 6.0;
        float s  = sin(phase);
        float co = cos(phase);

        tangent   += vec3(-D.x * D.x * WA * s, D.x * WA * co, -D.x * D.y * WA * s);
        bitangent += vec3(-D.x * D.y * WA * s, D.y * WA * co, -D.y * D.y * WA * s);
        jacobian  -= WA * co;
    }

    crestFactor = smoothstep(0.52, 0.25, jacobian);
    return normalize(cross(bitangent, tangent));
}

// ----------------------------------------------------------------------------
// Sky Fallback for Screen-Space Reflections
// ----------------------------------------------------------------------------
vec3 computeSkyReflection(vec3 R, vec3 sunDir) {
    float elevation = clamp(R.y, 0.0, 1.0);
    float sunElevation = clamp(sunDir.y, -1.0, 1.0);
    float dayFactor = smoothstep(-0.15, 0.25, sunElevation);

    vec3 zenith  = mix(vec3(0.01, 0.02, 0.05), vec3(0.12, 0.34, 0.86) * max(u_AmbientColor.rgb * 1.3, vec3(0.25)), dayFactor);
    vec3 horizon = mix(vec3(0.03, 0.05, 0.09), vec3(0.55, 0.74, 0.98) * max(u_SunColor.rgb, vec3(0.35)), dayFactor);
    return mix(horizon, zenith, pow(elevation, 0.65));
}

// ----------------------------------------------------------------------------
// Requirement 5: Screen-Space Reflections (SSR) Raymarch
// ----------------------------------------------------------------------------
vec3 traceScreenSpaceReflection(vec3 startWorldPos, vec3 R, vec3 sunDir) {
    const int   SSR_STEPS = 24;
    const float MAX_SSR_DIST = 48.0;

    vec3 skyFallback = computeSkyReflection(R, sunDir);
    float stepSize = MAX_SSR_DIST / float(SSR_STEPS);

    for (int i = 1; i <= SSR_STEPS; ++i) {
        float t = float(i) * stepSize;
        vec3 marchPos = startWorldPos + R * t;

        vec4 clip = u_ViewProjMatrix * vec4(marchPos, 1.0);
        if (clip.w <= 0.0) break;

        vec3 ndc = clip.xyz / clip.w;
        vec2 sampleUv = ndc.xy * 0.5 + 0.5;
        float rayDepth = ndc.z * 0.5 + 0.5;

        if (sampleUv.x <= 0.01 || sampleUv.x >= 0.99 ||
            sampleUv.y <= 0.01 || sampleUv.y >= 0.99 ||
            rayDepth >= 1.0) {
            break;
        }

        float sceneDepth = texture(u_SceneDepth, sampleUv).r;
        if (sceneDepth < 0.999999 && rayDepth > sceneDepth) {
            vec3 sceneWorld = reconstructWorldPos(sampleUv, sceneDepth);
            float thickness = length(marchPos - sceneWorld);
            if (thickness < stepSize * 2.5) {
                vec2 edgeDist = smoothstep(vec2(0.0), vec2(0.12), sampleUv) *
                                (1.0 - smoothstep(vec2(0.88), vec2(1.0), sampleUv));
                float vignette = edgeDist.x * edgeDist.y;
                vec3 hitColor = texture(u_OpaqueSceneColor, sampleUv).rgb;
                return mix(skyFallback, hitColor, vignette);
            }
        }
    }

    return skyFallback;
}

// ----------------------------------------------------------------------------
// Requirement 7: Cook-Torrance GGX Specular Functions
// ----------------------------------------------------------------------------
float distributionGGX(vec3 N, vec3 H, float roughness) {
    float a  = roughness * roughness;
    float a2 = a * a;
    float NdotH  = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float denom  = NdotH2 * (a2 - 1.0) + 1.0;
    return a2 / max(PI * denom * denom, 1e-6);
}

float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    float NdotV = max(dot(N, V), 0.0);
    float NdotL = max(dot(N, L), 0.0);
    float ggxV  = NdotV / max(NdotV * (1.0 - k) + k, 1e-6);
    float ggxL  = NdotL / max(NdotL * (1.0 - k) + k, 1e-6);
    return ggxV * ggxL;
}

void main() {
    vec2 screenUv = gl_FragCoord.xy / u_ScreenSize;
    vec3 camToSurface = v_WorldPos - u_CameraPos.xyz;
    float surfaceDistance = length(camToSurface);
    vec3 V = (surfaceDistance > 1e-5) ? (-camToSurface / surfaceDistance) : vec3(0.0, 1.0, 0.0);
    vec3 L = normalize(u_SunDirection.xyz);

    // Requirement 1: Animated surface normal using Gerstner waves
    float crestFoam = 0.0;
    vec3 N = computeGerstnerNormalAndCrest(v_WorldPos.xz, u_Time, crestFoam);

    // Requirement 2: Unproject seabed depth & compute water column depth
    float rawSceneDepth = texture(u_SceneDepth, screenUv).r;
    vec3 floorWorldPos = reconstructWorldPos(screenUv, rawSceneDepth);
    float floorDistance = length(floorWorldPos - u_CameraPos.xyz);
    float deltaDepth = max(floorDistance - surfaceDistance, 0.0);

    // Requirement 3: Screen-space refraction distorted by wave normal & deltaDepth
    float distortionScale = clamp(deltaDepth * 0.025, 0.0, 0.045) / max(surfaceDistance * 0.15, 1.0);
    vec2 refractUv = clamp(screenUv + N.xz * distortionScale, vec2(0.001), vec2(0.999));

    // Reject refracted samples that lie in front of the water surface
    float refractedDepthSample = texture(u_SceneDepth, refractUv).r;
    vec3 refractedWorldPos = reconstructWorldPos(refractUv, refractedDepthSample);
    float refractedFloorDist = length(refractedWorldPos - u_CameraPos.xyz);
    if (refractedFloorDist < surfaceDistance) {
        refractUv = screenUv;
        refractedFloorDist = floorDistance;
    }
    deltaDepth = max(refractedFloorDist - surfaceDistance, 0.0);

    vec3 refractedSceneColor = texture(u_OpaqueSceneColor, refractUv).rgb;

    // Requirement 4: Beer-Lambert absorption exp(-sigmaExtinction * deltaDepth)
    const vec3 sigmaExtinction = vec3(0.42, 0.13, 0.055);
    vec3 transmittance = exp(-sigmaExtinction * deltaDepth);
    vec3 deepWaterInScatter = vec3(0.012, 0.085, 0.145) * (u_AmbientColor.rgb + u_SunColor.rgb * max(L.y, 0.0) * 0.35);
    vec3 refractionColor = refractedSceneColor * transmittance + deepWaterInScatter * (1.0 - transmittance);

    // Requirement 5: Screen-Space Reflections (SSR) with sky fallback
    vec3 R = reflect(-V, N);
    vec3 reflectionColor = traceScreenSpaceReflection(v_WorldPos, R, L);

    // Requirement 6: Evaluate Schlick Fresnel reflectance (F0 = 0.02) & blend
    const float F0 = 0.02;
    float NdotV = max(dot(N, V), 0.0);
    float fresnel = F0 + (1.0 - F0) * pow(clamp(1.0 - NdotV, 0.0, 1.0), 5.0);
    vec3 waterColor = mix(refractionColor, reflectionColor, fresnel);

    // Requirement 7: Cook-Torrance GGX solar specular highlight with CSM shadow
    const float waterRoughness = 0.045;
    vec3 H = normalize(V + L);
    float D = distributionGGX(N, H, waterRoughness);
    float G = geometrySmith(N, V, L, waterRoughness);
    float F_spec = F0 + (1.0 - F0) * pow(clamp(1.0 - max(dot(H, V), 0.0), 0.0, 1.0), 5.0);
    float NdotL = max(dot(N, L), 0.0);

    float shadowVis = (NdotL > 0.001) ? sampleWaterShadow(v_WorldPos, surfaceDistance) : 0.0;
    vec3 sunRadiance = u_SunColor.rgb * max(u_SunColor.a, 1.0);
    vec3 sunSpecular = vec3((D * G * F_spec) / max(4.0 * NdotV * NdotL, 1e-4)) * sunRadiance * NdotL * shadowVis;
    waterColor += sunSpecular;

    // Requirement 8: Shore/crest foam and distance fog blend
    float shoreFoam = (1.0 - smoothstep(0.0, 0.65, deltaDepth)) * smoothstep(0.0, 0.04, deltaDepth);
    float foamPattern = 0.5 + 0.5 * sin(v_WorldPos.x * 6.0 + u_Time * 2.2) * cos(v_WorldPos.z * 6.0 - u_Time * 1.8);
    float totalFoam = clamp(shoreFoam * (0.65 + 0.35 * foamPattern) + crestFoam * 0.45, 0.0, 1.0);
    vec3 foamRadiance = vec3(0.92, 0.96, 1.0) * (u_AmbientColor.rgb + sunRadiance * max(NdotL, 0.15) * shadowVis * 0.6);
    waterColor = mix(waterColor, foamRadiance, totalFoam);

    float fogSpan = max(u_FogEnd - u_FogStart, 1e-3);
    float fogFactor = clamp((surfaceDistance - u_FogStart) / fogSpan, 0.0, 1.0);
    fogFactor = fogFactor * fogFactor * (3.0 - 2.0 * fogFactor);
    waterColor = mix(waterColor, u_FogColor.rgb, fogFactor * clamp(u_FogColor.a, 0.0, 1.0));

    outHdrColor = vec4(max(waterColor, vec3(0.0)), 1.0);
}
