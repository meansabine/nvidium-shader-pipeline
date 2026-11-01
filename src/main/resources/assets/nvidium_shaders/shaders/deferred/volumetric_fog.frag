#version 460 core

// ============================================================================
// Nvidium Shader Pipeline (NSP) - Phase 4: Volumetric Fog Pass
// 32-step raymarched crepuscular god-rays with IGN jitter, Henyey-Greenstein
// phase function (g = 0.76), exponential height falloff, and Beer-Lambert law.
// Output is additively blended (GL_ONE, GL_ONE) into the HDR framebuffer.
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

layout(binding = 3) uniform sampler2D u_Depth;
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outVolumetricScattering;

const float PI = 3.14159265359;
const int   MARCH_STEPS = 32;
const float MAX_MARCH_DISTANCE = 192.0;
const float HG_ASYMMETRY_G = 0.76;

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

float sampleShadowVisibility(vec3 samplePos, float stepDist) {
    if (stepDist >= u_CascadeSplits.z) {
        return 1.0;
    }
    int cascadeIdx = selectCascade(stepDist);
    vec3 sc = getShadowCoords(samplePos, cascadeIdx);
    if (sc.x <= 0.0 || sc.x >= 1.0 || sc.y <= 0.0 || sc.y >= 1.0 || sc.z <= 0.0 || sc.z >= 1.0) {
        return 1.0;
    }
    return texture(u_ShadowMap, vec4(sc.xy, float(cascadeIdx), clamp(sc.z - 0.0005, 0.0, 1.0)));
}

// Requirement 5: Henyey-Greenstein phase function (g = 0.76)
float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * pow(max(denom, 1e-4), 1.5));
}

// Requirement 6: Exponential height falloff based on world position Y
float computeHeightDensity(vec3 worldPos) {
    const float seaLevel = 63.0;
    const float heightFalloff = 0.032;
    const float baseDensity = 0.0042;
    float relativeY = max(worldPos.y - seaLevel, -24.0);
    return baseDensity * exp(-heightFalloff * relativeY);
}

void main() {
    // Requirement 1: Reconstruct ray end world position & clamp to 192 meters
    float depth = texture(u_Depth, v_Uv).r;
    vec3 targetWorldPos = reconstructWorldPos(v_Uv, depth);

    vec3 rayVector = targetWorldPos - u_CameraPos.xyz;
    float totalDist = length(rayVector);
    vec3 rayDir = (totalDist > 1e-5) ? (rayVector / totalDist) : vec3(0.0, 0.0, -1.0);

    float marchDist = min(totalDist, MAX_MARCH_DISTANCE);
    float stepSize  = marchDist / float(MARCH_STEPS);

    // Requirement 2: Ray start jitter using Interleaved Gradient Noise
    float jitter = interleavedGradientNoise(gl_FragCoord.xy);

    vec3 sunDir = normalize(u_SunDirection.xyz);
    float cosTheta = dot(rayDir, sunDir);
    float phase = henyeyGreenstein(cosTheta, HG_ASYMMETRY_G);

    vec3 sunRadiance = u_SunColor.rgb * max(u_SunColor.a, 1.0);
    vec3 ambientFog  = u_AmbientColor.rgb * 0.22;

    vec3  accumulatedScattering = vec3(0.0);
    float transmittance = 1.0;

    // Requirement 3: Raymarch in 32 uniform steps
    for (int i = 0; i < MARCH_STEPS; ++i) {
        float t = (float(i) + jitter) * stepSize;
        vec3 samplePos = u_CameraPos.xyz + rayDir * t;

        float density = computeHeightDensity(samplePos);
        if (density > 1e-6) {
            // Requirement 4: Shadow visibility lookup across CSM
            float shadowVis = sampleShadowVisibility(samplePos, t);

            // In-scattered direct + isotropic ambient radiance
            vec3 stepScattering = (sunRadiance * phase * shadowVis + ambientFog * (1.0 / (4.0 * PI))) * density;

            // Requirement 7: Beer-Lambert transmittance integration
            float stepExtinction = density * stepSize;
            float stepTransmittance = exp(-stepExtinction);
            vec3 scatteringIntegral = stepScattering * (1.0 - stepTransmittance) / max(density, 1e-6);

            accumulatedScattering += transmittance * scatteringIntegral;
            transmittance *= stepTransmittance;

            if (transmittance < 0.01) {
                break;
            }
        }
    }

    // Requirement 8: Output accumulated scattering for additive blend (GL_ONE, GL_ONE)
    outVolumetricScattering = vec4(max(accumulatedScattering, vec3(0.0)), 1.0 - transmittance);
}
