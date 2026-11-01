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

layout(binding = 3) uniform sampler2D u_Depth;
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outVolumetricScattering;

const float PI = 3.14159265359;
const int   NUM_STEPS = 32;
const float MAX_MARCH_DIST = 192.0;
const float SEA_LEVEL = 63.0;

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

float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * pow(max(denom, 0.0001), 1.5));
}

float sampleCascadeVisibility(vec3 samplePos, float distFromCam) {
    int cascadeIdx = 2;
    if (distFromCam < u_CascadeSplits.x) cascadeIdx = 0;
    else if (distFromCam < u_CascadeSplits.y) cascadeIdx = 1;

    vec4 shadowClip = u_CascadeViewProj[cascadeIdx] * vec4(samplePos, 1.0);
    vec3 shadowNdc = shadowClip.xyz / shadowClip.w;
    vec3 shadowUv = shadowNdc * 0.5 + 0.5;

    if (shadowUv.x < 0.0 || shadowUv.x > 1.0 || shadowUv.y < 0.0 || shadowUv.y > 1.0 || shadowUv.z > 1.0) {
        return 1.0;
    }

    return texture(u_ShadowMap, vec4(shadowUv.xy, float(cascadeIdx), shadowUv.z - 0.0005));
}

void main() {
    float depth = texture(u_Depth, v_Uv).r;
    vec3 worldEnd = reconstructWorldPos(v_Uv, depth);

    vec3 rayVec = worldEnd - u_CameraPos.xyz;
    float totalDist = length(rayVec);
    vec3 rayDir = rayVec / max(totalDist, 0.0001);

    float marchDist = min(totalDist, MAX_MARCH_DIST);
    float stepSize = marchDist / float(NUM_STEPS);

    float jitter = interleavedGradientNoise(gl_FragCoord.xy);
    vec3 currentPos = u_CameraPos.xyz + rayDir * (stepSize * jitter);

    vec3 L = normalize(u_SunDirection.xyz);
    float cosTheta = dot(rayDir, L);
    float phase = henyeyGreenstein(cosTheta, 0.76);

    vec3 sunRadiance = u_SunColor.rgb * max(u_SunColor.a, 1.0) * 0.85;
    vec3 ambientScatter = u_AmbientColor.rgb * 0.04;

    vec3 accumulatedRadiance = vec3(0.0);
    float transmittance = 1.0;

    const float baseExtinction = 0.0042;
    const float heightFalloff  = 0.032;

    for (int i = 0; i < NUM_STEPS; ++i) {
        float distFromCam = (float(i) + jitter) * stepSize;
        float heightAboveSea = max(currentPos.y - SEA_LEVEL, 0.0);
        float density = baseExtinction * exp(-heightAboveSea * heightFalloff);

        if (density > 0.00001) {
            float shadowVis = sampleCascadeVisibility(currentPos, distFromCam);
            vec3 stepScattering = (sunRadiance * phase * shadowVis + ambientScatter) * density;

            float stepTransmittance = exp(-density * stepSize);
            vec3 stepIntegral = stepScattering * (1.0 - stepTransmittance) / max(density, 0.00001);

            accumulatedRadiance += transmittance * stepIntegral;
            transmittance *= stepTransmittance;

            if (transmittance < 0.01) {
                break;
            }
        }

        currentPos += rayDir * stepSize;
    }

    outVolumetricScattering = vec4(max(accumulatedRadiance, vec3(0.0)), 1.0);
}
