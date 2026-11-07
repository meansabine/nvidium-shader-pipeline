#ifndef NVIDIUM_SCENE_UNIFORMS_GLSL
#define NVIDIUM_SCENE_UNIFORMS_GLSL

layout(std140, binding = 3) uniform SceneUniforms {
    mat4 u_ViewMatrix;            // [0..64]   Camera-relative View Matrix (camera at 0,0,0)
    mat4 u_ProjMatrix;            // [64..128] Projection Matrix
    mat4 u_ViewProjMatrix;        // [128..192] Proj * View
    mat4 u_InvViewProjMatrix;     // [192..256] inverse(Proj * View)
    mat4 u_PrevViewProjMatrix;    // [256..320] Previous frame's Proj * View

    mat4 u_CascadeViewProj0;      // [320..384] Camera-relative Light ViewProj Cascade 0
    mat4 u_CascadeViewProj1;      // [384..448] Camera-relative Light ViewProj Cascade 1
    mat4 u_CascadeViewProj2;      // [448..512] Camera-relative Light ViewProj Cascade 2
    vec4 u_CascadeSplits;         // [512..528] x=split0, y=split1, z=split2, w=maxShadowDist

    vec4 u_SunDirection;          // [528..544] xyz=dir toward sun, w=sunElevationSin
    vec4 u_SunColor;              // [544..560] rgb=sun color, w=sunIlluminance
    vec4 u_MoonDirection;         // [560..576] xyz=dir toward moon, w=moonPhaseFactor
    vec4 u_MoonColor;             // [576..592] rgb=moon color, w=moonIlluminance
    vec4 u_ShadowLightDir;        // [592..608] xyz=active shadow light dir (sun or moon), w=shadowFade(0..1)

    vec4 u_SkyColor;              // [608..624] rgb=zenith sky color, w=dayFactor(0..1)
    vec4 u_HorizonColor;          // [624..640] rgb=horizon sky color, w=sunsetFactor(0..1)
    vec4 u_BlockLightColor;       // [640..656] rgb=warm torchlight color, w=intensity

    vec4 u_CameraPos;             // [656..672] xyz=world camera pos, w=eyeSubmersion (0=air,1=water,2=lava,3=snow)
    vec4 u_ScreenSize;            // [672..688] x=width, y=height, z=1/width, w=1/height
    vec4 u_Time;                  // [688..704] x=renderTimeSec, y=dayTime01, z=frameCounter, w=dt
    vec4 u_Weather;               // [704..720] x=rainStrength, y=wetnessSmoothed, z=thunderStrength, w=lightningFlash
    vec4 u_Environment;           // [720..736] x=eyeSkyLight01, y=eyeBlockLight01, z=heldLightLevel(0..15), w=nightVision
    vec4 u_HeldLightColor;        // [736..752] rgb=held item light color, w=heldLightRadius
    vec4 u_FogColor;              // [752..768] rgb=vanilla fog color, w=nightFactor
    vec4 u_FogParams;             // [768..784] x=fogStart, y=fogEnd, z=volumetricFogEnabled, w=dimensionId(0,-1,1)
    vec4 u_PostParams;            // [784..800] x=exposure, y=bloomIntensity, z=saturation, w=tonemapOperator
    vec4 u_ShadowParams;          // [800..816] x=shadowRes, y=1/shadowRes, z=ssrEnabled, w=gtaoEnabled
    vec4 u_DebugParams;           // [816..832] x=debugMode, y=taaEnabled, z=casSharpening, w=taaBlendFactor
    vec4 u_CameraDelta;           // [832..848] xyz=currentCamPos - prevCamPos, w=hasPrevFrame
    vec4 u_TaaJitter;             // [848..864] xy=current Halton(2,3) UV jitter, zw=previous frame UV jitter
};

const int MAX_POINT_LIGHTS = 64;

layout(std140, binding = 5) uniform PointLightUniforms {
    vec4 u_PointLightMeta;                     // x=activeCount(0..64), y=maxRadius, z=globalMult, w=timeSec
    vec4 u_PointLightPosRadius[MAX_POINT_LIGHTS]; // xyz=camera-relative light pos, w=radius
    vec4 u_PointLightColor[MAX_POINT_LIGHTS];     // rgb=linear color, w=intensity
};

const float PI = 3.14159265359;
const float HALF_PI = 1.57079632679;
const float TAU = 6.28318530718;

/**
 * Reconstructs camera-relative world position (where camera is at 0,0,0) from UV and depth [0..1].
 * Since u_ViewMatrix and u_ViewProjMatrix are camera-relative, this avoids large-coordinate precision loss!
 */
vec3 reconstructCameraRelativePos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 relPos = u_InvViewProjMatrix * clip;
    return relPos.xyz / max(relPos.w, 1.0e-7);
}

/**
 * Reconstructs absolute world position (for noise/clouds/waves/puddles).
 */
vec3 reconstructWorldPos(vec2 uv, float depth) {
    return reconstructCameraRelativePos(uv, depth) + u_CameraPos.xyz;
}

/**
 * Projects a camera-relative position to screen UV + [0..1] depth.
 */
vec3 projectCameraRelativeToUVDepth(vec3 camRelPos) {
    vec4 clip = u_ViewProjMatrix * vec4(camRelPos, 1.0);
    if (clip.w <= 1.0e-5) {
        return vec3(-1.0);
    }
    vec3 ndc = clip.xyz / clip.w;
    return ndc * 0.5 + 0.5;
}

/**
 * Interleaved Gradient Noise (Jorge Jimenez) for high-frequency spatial-temporal dithering.
 */
float interleavedGradientNoise(vec2 pixelCoord, float frameOffset) {
    vec2 p = pixelCoord + frameOffset * 5.588238;
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

float noise2D(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm2D(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    mat2 rot = mat2(0.80, -0.60, 0.60, 0.80);
    for (int i = 0; i < 4; i++) {
        v += amp * noise2D(p);
        p = rot * p * 2.03 + vec2(17.1, 31.7);
        amp *= 0.5;
    }
    return v;
}

#endif
