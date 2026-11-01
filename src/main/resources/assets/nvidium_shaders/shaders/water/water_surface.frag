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

float interleavedGradientNoise(vec2 pixelCoord) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    float frameOffset = float(u_FrameIndex % 64) * 5.588238;
    return fract(magic.z * fract(dot(pixelCoord + frameOffset, magic.xy)));
}

float hash21(vec2 p) {
    p = fract(p * vec2(234.34, 435.345));
    p += dot(p, p + 34.23);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 4; ++i) {
        v += amp * valueNoise(p);
        p = rot * p * 2.03;
        amp *= 0.5;
    }
    return v;
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

vec3 calculateGerstnerNormal(vec2 pos, float time) {
    const vec2 dirs[6] = vec2[6](
        normalize(vec2(1.0, 0.35)),
        normalize(vec2(0.65, -0.75)),
        normalize(vec2(-0.45, 0.88)),
        normalize(vec2(-0.82, -0.55)),
        normalize(vec2(0.25, -0.96)),
        normalize(vec2(-0.92, 0.38))
    );
    const float freqs[6]  = float[6](0.55, 1.15, 2.35, 4.20, 7.80, 14.50);
    const float amps[6]   = float[6](0.12, 0.065, 0.032, 0.016, 0.0075, 0.0035);
    const float speeds[6] = float[6](1.25, 1.70, 2.30, 3.10, 4.20, 5.60);
    const float steep[6]  = float[6](0.45, 0.40, 0.35, 0.30, 0.25, 0.20);

    vec3 tangent = vec3(1.0, 0.0, 0.0);
    vec3 binormal = vec3(0.0, 0.0, 1.0);

    for (int i = 0; i < 6; ++i) {
        float w = freqs[i];
        float A = amps[i];
        float Q = steep[i] / (w * A * 6.0);
        float phase = dot(dirs[i], pos) * w + time * speeds[i];
        float s = sin(phase);
        float c = cos(phase);
        float wa = w * A;

        tangent.x  -= Q * dirs[i].x * dirs[i].x * wa * s;
        tangent.y  += dirs[i].x * wa * c;
        tangent.z  -= Q * dirs[i].x * dirs[i].y * wa * s;

        binormal.x -= Q * dirs[i].x * dirs[i].y * wa * s;
        binormal.y += dirs[i].y * wa * c;
        binormal.z -= Q * dirs[i].y * dirs[i].y * wa * s;
    }

    float micro1 = fbm(pos * 3.5 + vec2(time * 0.4, -time * 0.3));
    float micro2 = fbm(pos * 7.2 - vec2(time * 0.5, time * 0.35));
    vec3 N = normalize(cross(binormal, tangent));
    N.xz += (vec2(micro1, micro2) - 0.5) * 0.06;
    return normalize(N);
}

float sampleWaterShadow(vec3 worldPos, float viewDist) {
    int cascadeIdx = 2;
    if (viewDist < u_CascadeSplits.x) cascadeIdx = 0;
    else if (viewDist < u_CascadeSplits.y) cascadeIdx = 1;

    vec4 shadowClip = u_CascadeViewProj[cascadeIdx] * vec4(worldPos, 1.0);
    vec3 shadowNdc = shadowClip.xyz / shadowClip.w;
    vec3 shadowUv = shadowNdc * 0.5 + 0.5;

    if (shadowUv.x < 0.0 || shadowUv.x > 1.0 || shadowUv.y < 0.0 || shadowUv.y > 1.0 || shadowUv.z > 1.0) {
        return 1.0;
    }

    float bias = 0.0008 * float(cascadeIdx + 1);
    return texture(u_ShadowMap, vec4(shadowUv.xy, float(cascadeIdx), shadowUv.z - bias));
}

vec3 evaluateSkyGradient(vec3 dir, vec3 sunDir) {
    float horizon = clamp(dir.y, 0.0, 1.0);
    vec3 zenithCol = max(u_AmbientColor.rgb * 1.15, vec3(0.12, 0.28, 0.62));
    vec3 horizonCol = mix(u_FogColor.rgb, u_SunColor.rgb * 0.65, 0.25);
    vec3 sky = mix(horizonCol, zenithCol, pow(horizon, 0.55));
    float sunDot = max(dot(dir, sunDir), 0.0);
    sky += u_SunColor.rgb * pow(sunDot, 16.0) * 0.35;
    return sky;
}

void main() {
    vec2 screenUv = gl_FragCoord.xy / u_ScreenSize;
    vec3 viewVec = v_WorldPos - u_CameraPos.xyz;
    float surfaceDist = length(viewVec);
    vec3 V = -normalize(viewVec);
    vec3 L = normalize(u_SunDirection.xyz);

    vec3 N = calculateGerstnerNormal(v_WorldPos.xz, u_Time);
    float NdotV = max(dot(N, V), 0.001);

    float rawFloorDepth = texture(u_SceneDepth, screenUv).r;
    vec3 floorWorldPos = reconstructWorldPos(screenUv, rawFloorDepth);
    float floorDist = length(floorWorldPos - u_CameraPos.xyz);
    float deltaDepth = max(floorDist - surfaceDist, 0.0);
    float verticalDepth = max(v_WorldPos.y - floorWorldPos.y, 0.0);

    float distortScale = clamp(deltaDepth * 0.35, 0.0, 1.0) * 0.035 / max(surfaceDist * 0.15, 1.0);
    vec2 distortOffset = N.xz * distortScale;
    vec2 refractedUv = clamp(screenUv + distortOffset, vec2(0.001), vec2(0.999));

    float refractedRawDepth = texture(u_SceneDepth, refractedUv).r;
    vec3 refractedFloorPos = reconstructWorldPos(refractedUv, refractedRawDepth);
    float refractedFloorDist = length(refractedFloorPos - u_CameraPos.xyz);

    if (refractedFloorDist < surfaceDist) {
        refractedUv = screenUv;
        refractedFloorDist = floorDist;
        refractedFloorPos = floorWorldPos;
    }

    deltaDepth = max(refractedFloorDist - surfaceDist, 0.0);
    verticalDepth = max(v_WorldPos.y - refractedFloorPos.y, 0.0);

    vec3 seabedColor = texture(u_OpaqueSceneColor, refractedUv).rgb;

    const vec3 sigmaExtinction = vec3(0.46, 0.14, 0.055);
    vec3 transmittance = exp(-sigmaExtinction * deltaDepth);

    float skyLightFactor = clamp(v_LightCoord.y, 0.05, 1.0);
    vec3 shallowInScatter = vec3(0.02, 0.22, 0.26) * (u_AmbientColor.rgb + u_SunColor.rgb * max(L.y, 0.0) * 0.35);
    vec3 deepInScatter    = vec3(0.005, 0.045, 0.10) * (u_AmbientColor.rgb + 0.05);
    vec3 waterVolumeColor = mix(shallowInScatter, deepInScatter, clamp(verticalDepth / 18.0, 0.0, 1.0)) * skyLightFactor;

    vec3 refractedColor = seabedColor * transmittance + waterVolumeColor * (1.0 - transmittance);

    vec3 R = reflect(-V, N);
    vec3 reflectionColor = evaluateSkyGradient(R, L) * skyLightFactor;

    const int ssrSteps = 20;
    float jitter = interleavedGradientNoise(gl_FragCoord.xy);
    vec3 marchStart = v_WorldPos + N * 0.08;
    float stepSize = 0.65;
    vec3 rayPos = marchStart + R * (stepSize * jitter);

    for (int i = 0; i < ssrSteps; ++i) {
        vec4 proj = u_ViewProjMatrix * vec4(rayPos, 1.0);
        vec3 ndc = proj.xyz / proj.w;
        vec2 rayUv = ndc.xy * 0.5 + 0.5;

        if (rayUv.x < 0.0 || rayUv.x > 1.0 || rayUv.y < 0.0 || rayUv.y > 1.0 || ndc.z >= 1.0) {
            break;
        }

        float sceneZ = texture(u_SceneDepth, rayUv).r;
        float rayZ = ndc.z * 0.5 + 0.5;
        float thickness = rayZ - sceneZ;

        if (thickness > 0.00005 && thickness < 0.008) {
            vec2 edgeFade = smoothstep(vec2(0.0), vec2(0.1), rayUv) * (1.0 - smoothstep(vec2(0.9), vec2(1.0), rayUv));
            float ssrWeight = clamp(edgeFade.x * edgeFade.y, 0.0, 1.0);
            vec3 hitCol = texture(u_OpaqueSceneColor, rayUv).rgb;
            reflectionColor = mix(reflectionColor, hitCol, ssrWeight);
            break;
        }

        stepSize *= 1.18;
        rayPos += R * stepSize;
    }

    const vec3 F0 = vec3(0.02);
    vec3 F = fresnelSchlick(NdotV, F0);

    vec3 H = normalize(V + L);
    float roughness = 0.045;
    float D = distributionGGX(N, H, roughness);
    float G = geometrySchlickGGX(NdotV, roughness) * geometrySchlickGGX(max(dot(N, L), 0.001), roughness);
    vec3 F_sun = fresnelSchlick(max(dot(H, V), 0.0), F0);
    float NdotL = max(dot(N, L), 0.0);

    float sunShadow = sampleWaterShadow(v_WorldPos, surfaceDist);
    vec3 sunGlitter = (D * G * F_sun) / max(4.0 * NdotV * max(NdotL, 0.001), 0.001)
                      * u_SunColor.rgb * u_SunColor.a * NdotL * sunShadow * 2.5;

    vec3 waterSurfaceColor = mix(refractedColor, reflectionColor, F) + sunGlitter;

    float foamNoise = fbm(v_WorldPos.xz * 2.8 + vec2(u_Time * 0.35, -u_Time * 0.25));
    float shoreMask = 1.0 - smoothstep(0.0, 0.65, deltaDepth);
    float crestMask = smoothstep(0.22, 0.45, 1.0 - N.y);
    float foamFactor = clamp(shoreMask * smoothstep(0.32, 0.75, foamNoise + shoreMask * 0.4) + crestMask * foamNoise * 0.35, 0.0, 1.0);
    vec3 foamAlbedo = vec3(0.92, 0.96, 1.0) * (u_AmbientColor.rgb + u_SunColor.rgb * max(L.y, 0.0) * sunShadow * 0.85) * skyLightFactor;

    vec3 finalColor = mix(waterSurfaceColor, foamAlbedo, foamFactor * 0.85);

    float fogFactor = clamp((surfaceDist - u_FogStart) / max(u_FogEnd - u_FogStart, 1.0), 0.0, 1.0);
    fogFactor = fogFactor * fogFactor * (3.0 - 2.0 * fogFactor);
    finalColor = mix(finalColor, u_FogColor.rgb, fogFactor);

    outHdrColor = vec4(max(finalColor, vec3(0.0)), 1.0);
}
