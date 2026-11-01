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


// Samplers for Pass 4 Volumetric Fog
layout(binding = 3) uniform sampler2D u_Depth;                 // Hardware Scene Depth (GL_DEPTH_COMPONENT32F)
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;  // 3-Cascade Shadow Map Array

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outVolumetricScattering; // Blended additively (GL_ONE, GL_ONE)

const float PI = 3.14159265359;
const int   NUM_RAY_STEPS = 32;
const float MAX_RAY_DISTANCE = 192.0;
const float MIE_G = 0.76; // Forward scattering anisotropy factor

// Recipe A: World Position Reconstruction from Depth
vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}

// Interleaved Gradient Noise for temporal ray offset jittering
float interleavedGradientNoise(vec2 pixelCoord) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    float frameOffset = float(u_FrameIndex % 64) * 5.588238;
    return fract(magic.z * fract(dot(pixelCoord + frameOffset, magic.xy)));
}

// Single-tap hardware PCF shadow lookup for fast volumetric marching
float sampleShadowCascadeFast(vec3 worldPos, float distFromCam) {
    int cascade = (distFromCam < u_CascadeSplits.x) ? 0 :
                  (distFromCam < u_CascadeSplits.y) ? 1 : 2;

    vec4 shadowClip = u_CascadeViewProj[cascade] * vec4(worldPos, 1.0);
    vec3 shadowCoord = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;

    if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0 ||
        shadowCoord.y < 0.0 || shadowCoord.y > 1.0 ||
        shadowCoord.z > 1.0) {
        return 1.0;
    }

    return texture(u_ShadowMap, vec4(shadowCoord.xy, float(cascade), shadowCoord.z - 0.0015));
}

// Henyey-Greenstein Phase Function for Mie Aerosol Forward Scattering
float henyeyGreensteinPhase(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * pow(max(denom, 0.0001), 1.5));
}

// Height-based exponential atmospheric aerosol density
float sampleFogDensity(vec3 worldPos) {
    // Sea level in Minecraft is Y=63; exponential height falloff above sea level
    float heightAboveSea = max(worldPos.y - 62.0, 0.0);
    float heightFalloff = exp(-heightAboveSea * 0.025);
    float baseSigmaS = 0.0065;
    return baseSigmaS * heightFalloff;
}

void main() {
    float depth = texture(u_Depth, v_Uv).r;
    vec3 targetWorldPos = reconstructWorldPos(v_Uv, depth);

    vec3 rayOrigin = u_CameraPos.xyz;
    vec3 rayVector = targetWorldPos - rayOrigin;
    float totalDist = length(rayVector);
    vec3 rayDir = rayVector / max(totalDist, 0.0001);

    // Clamp raymarching distance to maximum shadow cascade range
    float marchDist = min(totalDist, min(u_CascadeSplits.z, MAX_RAY_DISTANCE));
    float stepSize = marchDist / float(NUM_RAY_STEPS);

    // Jitter starting position along the ray to break banding
    float jitter = interleavedGradientNoise(gl_FragCoord.xy);
    vec3 currentPos = rayOrigin + rayDir * (stepSize * jitter);

    vec3 L_sun = normalize(u_SunDirection.xyz);
    float cosTheta = dot(rayDir, L_sun);
    float phaseMie = henyeyGreensteinPhase(cosTheta, MIE_G);

    vec3 sunRadiance = u_SunColor.rgb * u_SunColor.a;
    vec3 ambientRadiance = u_AmbientColor.rgb * 0.08;

    vec3 accumScattering = vec3(0.0);
    float transmittance = 1.0;

    for (int i = 0; i < NUM_RAY_STEPS; ++i) {
        float distFromCam = (float(i) + jitter) * stepSize;
        float density = sampleFogDensity(currentPos);

        if (density > 0.00001) {
            float shadowVis = sampleShadowCascadeFast(currentPos, distFromCam);

            // Combine anisotropic direct sunlight shafts with isotropic sky ambient scattering
            vec3 stepInScattering = (sunRadiance * shadowVis * phaseMie + ambientRadiance * (0.25 / PI)) * density;

            // Beer-Lambert step transmittance integration
            float stepExtinction = density * 1.15;
            float stepTransmittance = exp(-stepExtinction * stepSize);
            vec3 Sint = (stepInScattering - stepInScattering * stepTransmittance) / max(stepExtinction, 0.00001);

            accumScattering += transmittance * Sint;
            transmittance *= stepTransmittance;

            if (transmittance < 0.01) break;
        }

        currentPos += rayDir * stepSize;
    }

    // Output additive in-scattered light shafts (Pass 4 blends with GL_ONE, GL_ONE)
    outVolumetricScattering = vec4(max(accumScattering, vec3(0.0)), 1.0);
}
