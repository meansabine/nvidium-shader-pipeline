#version 460 core

in vec2 v_TexCoord;
layout(location = 0) out vec4 outVolumetricFog;

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"
#include "../common/shadows.glsl"
#include "../common/atmosphere.glsl"

layout(binding = 0) uniform sampler2D gDepth;
layout(binding = 1) uniform sampler2DArray shadowMapArray;

const int VOL_STEPS = 16;

void main() {
    float godraysStrength = OPT_GODRAYS_STRENGTH;
    if (u_FogParams.z < 0.5 || godraysStrength <= 0.001) {
        outVolumetricFog = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // Scale volumetric scattering by player's outdoor sky exposure so deep caves don't glow
    float eyeSkyExposure = smoothstep(0.05, 0.45, u_Environment.x);
    int dimId = int(round(u_FogParams.w));
    if (dimId != 0 && u_CameraPos.w < 0.5) {
        outVolumetricFog = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    float depth = textureLod(gDepth, v_TexCoord, 0.0).r;
    vec3 camRelTarget = reconstructCameraRelativePos(v_TexCoord, depth);

    float maxMarchDist = min(u_CascadeSplits.w * 0.92, 128.0);
    float rayLen = (depth >= 0.99999) ? maxMarchDist : min(length(camRelTarget), maxMarchDist);
    if (rayLen <= 0.25) {
        outVolumetricFog = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec3 rayDir = camRelTarget / max(length(camRelTarget), 1.0e-5);
    float stepSize = rayLen / float(VOL_STEPS);
    float jitter = interleavedGradientNoise(gl_FragCoord.xy, 0.0);

    vec3 lightDir = normalize(u_ShadowLightDir.xyz);
    bool isDay = (u_SunDirection.y >= 0.0);
    vec3 lightColor = isDay ? (u_SunColor.rgb * u_SunColor.w) : (u_MoonColor.rgb * u_MoonColor.w * 2.2);

    float cosTheta = dot(rayDir, lightDir);
    float hgPhase = phaseHenyeyGreenstein(cosTheta, 0.65) * 2.15 + 0.12;

    bool underwater = (u_CameraPos.w > 0.5 && u_CameraPos.w < 1.5);
    float baseDensity = underwater ? 0.014 : (0.0032 * max(0.25, OPT_ATMOS_HAZINESS) * (1.0 + u_Weather.x * 0.85));

    vec3 accumLight = vec3(0.0);
    float transmittance = 1.0;

    for (int i = 0; i < VOL_STEPS; i++) {
        float t = (float(i) + jitter) * stepSize;
        vec3 sampleRelPos = rayDir * t;
        vec3 sampleWorldPos = sampleRelPos + u_CameraPos.xyz;

        float heightFalloff = underwater
                ? 1.0
                : clamp(exp(-(sampleWorldPos.y - 62.0) * 0.020), 0.20, 2.2);
        float localDensity = baseDensity * heightFalloff;

        if (localDensity > 1.0e-5) {
            float shadowVis = sampleVolumetricShadowTap(shadowMapArray, sampleRelPos);
            vec3 directScatter = lightColor * shadowVis * hgPhase * godraysStrength * u_ShadowLightDir.w * 1.45;
            vec3 ambientScatter = (underwater ? vec3(0.04, 0.24, 0.35) : (u_SkyColor.rgb * 0.12)) * 0.25;

            vec3 stepInscatter = (directScatter + ambientScatter) * localDensity * stepSize;
            accumLight += stepInscatter * transmittance;
            transmittance *= exp(-localDensity * stepSize * 0.85);
            if (transmittance < 0.02) break;
        }
    }

    accumLight *= eyeSkyExposure;
    transmittance = mix(1.0, transmittance, eyeSkyExposure);

    outVolumetricFog = vec4(max(accumLight, vec3(0.0)), clamp(transmittance, 0.0, 1.0));
}
