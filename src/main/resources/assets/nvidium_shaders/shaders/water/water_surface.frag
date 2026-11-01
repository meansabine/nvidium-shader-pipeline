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


// Samplers for Pass 5 Water & Translucent Pass
layout(binding = 0) uniform sampler2D u_OpaqueSceneColor; // Lit opaque scene from Pass 3
layout(binding = 1) uniform sampler2D u_SceneDepth;       // Opaque scene depth
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap; // Optional shadow lookup for sun specularity

// Inputs from Nvidium Water Mesh / Vertex Stage
layout(location = 0) in vec3 v_WorldPos;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_LightCoord;

layout(location = 0) out vec4 outHdrColor;

const float PI = 3.14159265359;

// Recipe A: World Position Reconstruction from Depth
vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}

// Recipe B: Analytical Surface Normal
vec3 deriveGeometricNormal(vec3 worldPos) {
    return normalize(cross(dFdx(worldPos), dFdy(worldPos)));
}

// 1. Animated Multi-Octave Gerstner Micro-Wave Normal Perturbations using u_Time
vec3 evaluateGerstnerWaterNormal(vec3 worldPos, float time) {
    vec2 p = worldPos.xz;
    vec3 tangent = vec3(1.0, 0.0, 0.0);
    vec3 binormal = vec3(0.0, 0.0, 1.0);

    // 6-octave directional Gerstner wave bank: xy = direction, z = steepness, w = wavelength
    const vec4 waves[6] = vec4[](
        vec4( 0.85,  0.52, 0.12, 3.80),
        vec4( 0.35, -0.93, 0.10, 2.25),
        vec4(-0.62,  0.78, 0.08, 1.40),
        vec4(-0.91, -0.41, 0.06, 0.85),
        vec4( 0.48,  0.87, 0.05, 0.42),
        vec4(-0.25, -0.96, 0.04, 0.22)
    );

    for (int i = 0; i < 6; ++i) {
        vec2 dir = normalize(waves[i].xy);
        float steepness = waves[i].z;
        float wavelength = waves[i].w;
        float k = 2.0 * PI / wavelength;
        float c = sqrt(9.81 / k);
        float f = k * (dot(dir, p) - c * time * 1.15);
        float a = steepness / k;

        float sinF = sin(f);
        float cosF = cos(f);

        tangent += vec3(
            -dir.x * dir.x * steepness * sinF,
             dir.x * steepness * cosF,
            -dir.x * dir.y * steepness * sinF
        );
        binormal += vec3(
            -dir.x * dir.y * steepness * sinF,
             dir.y * steepness * cosF,
            -dir.y * dir.y * steepness * sinF
        );
    }

    return normalize(cross(binormal, tangent));
}

// Cascaded Shadow Map check for specular sun glitter
float sampleWaterSunShadow(vec3 worldPos, float viewDist) {
    int cascade = (viewDist < u_CascadeSplits.x) ? 0 :
                  (viewDist < u_CascadeSplits.y) ? 1 : 2;
    vec4 shadowClip = u_CascadeViewProj[cascade] * vec4(worldPos, 1.0);
    vec3 shadowCoord = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;
    if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0 ||
        shadowCoord.y < 0.0 || shadowCoord.y > 1.0 ||
        shadowCoord.z > 1.0) {
        return 1.0;
    }
    return texture(u_ShadowMap, vec4(shadowCoord.xy, float(cascade), shadowCoord.z - 0.0015));
}

// 2. Screen-Space Reflections (SSR) raymarching the opaque depth buffer
vec4 traceScreenSpaceReflection(vec3 worldPos, vec3 R, float viewDist) {
    const int   SSR_STEPS = 28;
    const int   BINARY_STEPS = 5;
    float maxRayDist = clamp(viewDist * 1.25, 16.0, 96.0);
    float stepSize = maxRayDist / float(SSR_STEPS);

    vec3 rayPos = worldPos + R * 0.08;
    vec3 prevPos = rayPos;

    for (int i = 0; i < SSR_STEPS; ++i) {
        prevPos = rayPos;
        rayPos += R * stepSize;
        stepSize *= 1.06; // Mild exponential step growth

        vec4 clip = u_ViewProjMatrix * vec4(rayPos, 1.0);
        vec3 ndc = clip.xyz / clip.w;
        vec2 uv = ndc.xy * 0.5 + 0.5;
        float rayDepth = ndc.z * 0.5 + 0.5;

        // Abort if outside screen frustum
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0 || rayDepth >= 1.0 || rayDepth <= 0.0) {
            break;
        }

        float sceneDepth = texture(u_SceneDepth, uv).r;
        if (rayDepth > sceneDepth && sceneDepth < 0.99999) {
            // Binary search refinement
            vec3 minPos = prevPos;
            vec3 maxPos = rayPos;
            vec2 hitUv = uv;
            float hitSceneDepth = sceneDepth;
            float hitRayDepth = rayDepth;

            for (int b = 0; b < BINARY_STEPS; ++b) {
                vec3 midPos = 0.5 * (minPos + maxPos);
                vec4 mClip = u_ViewProjMatrix * vec4(midPos, 1.0);
                vec3 mNdc = mClip.xyz / mClip.w;
                hitUv = mNdc.xy * 0.5 + 0.5;
                hitRayDepth = mNdc.z * 0.5 + 0.5;
                hitSceneDepth = texture(u_SceneDepth, hitUv).r;

                if (hitRayDepth > hitSceneDepth) {
                    maxPos = midPos;
                } else {
                    minPos = midPos;
                }
            }

            vec3 hitWorldPos = reconstructWorldPos(hitUv, hitSceneDepth);
            float thickness = length(hitWorldPos - maxPos);
            if (thickness < 2.5) {
                // Smooth screen-edge vignette fade
                vec2 edgeDist = smoothstep(vec2(0.0), vec2(0.12), hitUv) *
                                (1.0 - smoothstep(vec2(0.88), vec2(1.0), hitUv));
                float confidence = edgeDist.x * edgeDist.y * (1.0 - float(i) / float(SSR_STEPS));
                vec3 reflColor = texture(u_OpaqueSceneColor, hitUv).rgb;
                return vec4(reflColor, clamp(confidence, 0.0, 1.0));
            }
        }
    }
    return vec4(0.0);
}

// Recipe E GGX helpers for solar specular glitter on water
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

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

void main() {
    vec2 screenUv = gl_FragCoord.xy / u_ScreenSize;
    vec3 V = normalize(u_CameraPos.xyz - v_WorldPos);
    float viewDist = length(u_CameraPos.xyz - v_WorldPos);

    // Construct perturbed surface normal from Gerstner wave bank
    vec3 geomNormal = deriveGeometricNormal(v_WorldPos);
    vec3 waveNormal = evaluateGerstnerWaterNormal(v_WorldPos, u_Time);
    // Align wave normal with geometric orientation (handles flowing water slopes)
    vec3 N = normalize(mix(geomNormal, waveNormal, 0.85));

    // 3. Depth-Based Water Refraction with Beer-Lambert Physical Color Absorption
    float rawSceneDepth = texture(u_SceneDepth, screenUv).r;
    vec3 unrefractedFloorPos = reconstructWorldPos(screenUv, rawSceneDepth);
    float rawWaterDepth = max(0.0, length(unrefractedFloorPos - u_CameraPos.xyz) - viewDist);

    // Chromatic / wave normal UV refraction offset scaled by water depth
    float refractStrength = clamp(rawWaterDepth * 0.035, 0.0, 0.045) / max(viewDist * 0.15, 1.0);
    vec2 refractUv = clamp(screenUv + N.xz * refractStrength, vec2(0.001), vec2(0.999));

    float refractedDepthSample = texture(u_SceneDepth, refractUv).r;
    // Prevent refracting foreground objects that sit in front of the water surface
    if (refractedDepthSample < gl_FragCoord.z) {
        refractUv = screenUv;
        refractedDepthSample = rawSceneDepth;
    }

    vec3 seabedWorldPos = reconstructWorldPos(refractUv, refractedDepthSample);
    float opticalWaterDepth = max(0.0, length(seabedWorldPos - u_CameraPos.xyz) - viewDist);
    float verticalDepth = max(0.0, v_WorldPos.y - seabedWorldPos.y);
    float effectiveDepth = mix(opticalWaterDepth, verticalDepth, 0.4);

    vec3 seabedColor = texture(u_OpaqueSceneColor, refractUv).rgb;

    // Physical Beer-Lambert absorption coefficients (R absorbed rapidly, G/B penetrate deeper)
    const vec3 sigmaAbsorption = vec3(0.42, 0.11, 0.045);
    vec3 transmittance = exp(-sigmaAbsorption * effectiveDepth);

    // Deep water multiple-scattering in-scatter color (dark turquoise / navy)
    vec3 sunRadiance = u_SunColor.rgb * u_SunColor.a;
    vec3 L_sun = normalize(u_SunDirection.xyz);
    float sunElevation = max(L_sun.y, 0.05);
    vec3 deepWaterInScatter = vec3(0.008, 0.052, 0.095) * (u_AmbientColor.rgb + sunRadiance * sunElevation * 0.25);

    // Subtle shoreline foam at shallow contact boundaries
    float foamMask = smoothstep(0.35, 0.0, verticalDepth) * max(0.0, sin(effectiveDepth * 24.0 - u_Time * 4.0) * 0.5 + 0.5);
    vec3 refractedWater = seabedColor * transmittance + deepWaterInScatter * (1.0 - transmittance);

    // 4. Screen-Space Reflections (SSR) + Procedural Sky Fallback
    vec3 R = reflect(-V, N);
    vec4 ssrSample = traceScreenSpaceReflection(v_WorldPos, R, viewDist);

    // Procedural sky reflection fallback
    float skyZenith = clamp(R.y, 0.0, 1.0);
    vec3 skyFallback = mix(u_FogColor.rgb, u_AmbientColor.rgb * 1.15, pow(skyZenith, 0.55));
    vec3 reflectedColor = mix(skyFallback, ssrSample.rgb, ssrSample.a);

    // Direct GGX Sun Specular Glitter on water ripples
    vec3 H = normalize(V + L_sun);
    float waterRoughness = 0.045;
    float NDF = distributionGGX(N, H, waterRoughness);
    float G   = geometrySchlickGGX(max(dot(N, V), 0.0), waterRoughness) *
                geometrySchlickGGX(max(dot(N, L_sun), 0.0), waterRoughness);
    vec3 F_sun = fresnelSchlick(max(dot(H, V), 0.0), vec3(0.02));
    float NdotL = max(dot(N, L_sun), 0.0);
    float NdotV = max(dot(N, V), 0.001);
    float sunShadow = sampleWaterSunShadow(v_WorldPos, viewDist);
    vec3 sunSpecHighlight = (NDF * G * F_sun) / (4.0 * NdotV * NdotL + 0.0001) * sunRadiance * NdotL * sunShadow;

    // 5. Schlick Fresnel Blending (Water IOR = 1.333 -> F0 = 0.02)
    vec3 F = fresnelSchlick(max(dot(N, V), 0.0), vec3(0.02));
    vec3 waterSurfaceColor = mix(refractedWater, reflectedColor, F) + sunSpecHighlight;
    waterSurfaceColor = mix(waterSurfaceColor, u_AmbientColor.rgb * 0.9, foamMask * 0.35);

    // Apply distance fog matching Pass 3
    float fogFactor = smoothstep(u_FogStart, u_FogEnd, viewDist);
    vec3 finalColor = mix(waterSurfaceColor, u_FogColor.rgb, fogFactor);

    outHdrColor = vec4(max(finalColor, vec3(0.0)), 1.0);
}
