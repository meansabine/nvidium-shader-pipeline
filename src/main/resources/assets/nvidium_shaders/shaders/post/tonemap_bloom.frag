#version 460 core

in vec2 v_TexCoord;
layout(location = 0) out vec4 outColor;

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"
#include "../common/post_tonemap.glsl"

layout(binding = 0) uniform sampler2D hdrSceneTex;      // Mip-mapped HDR scene texture
layout(binding = 1) uniform sampler2D volumetricFogTex; // RGBA16F (rgb=inscatter, a=transmittance)
layout(binding = 2) uniform sampler2D gLightEmission;
layout(binding = 3) uniform sampler2D gDepth;
layout(binding = 4) uniform sampler2D prevHistoryTex;   // Previous frame's resolved output for TAA
layout(binding = 5) uniform sampler2D gNormalMetallic;

vec3 softBloomThreshold(vec3 color, float threshold, float knee) {
    float br = max(color.r, max(color.g, color.b));
    float soft = clamp(br - threshold + knee, 0.0, 2.0 * knee);
    soft = (soft * soft) / max(4.0 * knee, 1.0e-5);
    float contrib = max(soft, br - threshold) / max(br, 1.0e-4);
    return color * clamp(contrib, 0.0, 1.0);
}

vec3 sampleTentMip(int mipLevel, vec2 uv, vec2 radiusUV) {
    float lod = float(mipLevel);
    vec3 c  = textureLod(hdrSceneTex, uv, lod).rgb * 4.0;
    c += textureLod(hdrSceneTex, uv + vec2(-radiusUV.x,  0.0), lod).rgb * 2.0;
    c += textureLod(hdrSceneTex, uv + vec2( radiusUV.x,  0.0), lod).rgb * 2.0;
    c += textureLod(hdrSceneTex, uv + vec2( 0.0, -radiusUV.y), lod).rgb * 2.0;
    c += textureLod(hdrSceneTex, uv + vec2( 0.0,  radiusUV.y), lod).rgb * 2.0;
    c += textureLod(hdrSceneTex, uv + vec2(-radiusUV.x, -radiusUV.y), lod).rgb;
    c += textureLod(hdrSceneTex, uv + vec2( radiusUV.x, -radiusUV.y), lod).rgb;
    c += textureLod(hdrSceneTex, uv + vec2(-radiusUV.x,  radiusUV.y), lod).rgb;
    c += textureLod(hdrSceneTex, uv + vec2( radiusUV.x,  radiusUV.y), lod).rgb;
    return softBloomThreshold(c * (1.0 / 16.0), 0.95, 0.55);
}

vec3 computeMultiOctaveBloom(vec2 uv) {
    vec2 baseTexel = u_ScreenSize.zw;
    vec3 b1 = sampleTentMip(1, uv, baseTexel * 2.5);
    vec3 b2 = sampleTentMip(2, uv, baseTexel * 5.0);
    vec3 b3 = sampleTentMip(3, uv, baseTexel * 10.0);
    vec3 b4 = sampleTentMip(4, uv, baseTexel * 20.0);
    vec3 b5 = sampleTentMip(5, uv, baseTexel * 38.0);
    return (b1 * 0.30 + b2 * 0.26 + b3 * 0.20 + b4 * 0.14 + b5 * 0.10);
}

vec3 rgbToYCoCg(vec3 c) {
    return vec3(
         0.25 * c.r + 0.5 * c.g + 0.25 * c.b,
         0.50 * c.r             - 0.50 * c.b,
        -0.25 * c.r + 0.5 * c.g - 0.25 * c.b
    );
}

vec3 yCoCgToRgb(vec3 ycc) {
    float y  = ycc.x;
    float co = ycc.y;
    float cg = ycc.z;
    return vec3(y + co - cg, y + cg, y - co - cg);
}

vec3 resolveCurrentPixelSrgb(vec2 uv, vec3 precomputedBloom) {
    vec3 hdr = textureLod(hdrSceneTex, uv, 0.0).rgb;
    vec4 volFog = textureLod(volumetricFogTex, uv, 0.0);
    hdr = hdr * volFog.a + volFog.rgb;

    float bloomStrength = u_PostParams.y;
    if (bloomStrength > 0.001) {
        hdr += precomputedBloom * bloomStrength;
    }

    float exposure = u_PostParams.x;
    float saturation = u_PostParams.z;
    int tonemapOp = int(round(u_PostParams.w));

    vec3 graded = applyColorGrading(hdr * exposure, saturation);
    vec3 mappedLinear = applySelectedTonemap(graded, tonemapOp);
    return linearToSrgb(mappedLinear);
}

void main() {
    float bloomStrength = u_PostParams.y;
    vec3 bloom = (bloomStrength > 0.001) ? computeMultiOctaveBloom(v_TexCoord) : vec3(0.0);

    vec3 currSrgb = resolveCurrentPixelSrgb(v_TexCoord, bloom);

    // Debug modes bypass TAA
    if (u_DebugParams.x > 0.5) {
        outColor = vec4(currSrgb, 1.0);
        return;
    }

    // Temporal Accumulation / Anti-Aliasing (TAA) with 3x3 YCoCg Variance Clipping
    bool taaEnabled = (u_DebugParams.y > 0.5) && (u_CameraDelta.w > 0.5);
    if (taaEnabled) {
        float depth = textureLod(gDepth, v_TexCoord, 0.0).r;
        vec3 camRelPos = reconstructCameraRelativePos(v_TexCoord, depth);

        // Convert current camera-relative pos to previous frame's camera-relative origin
        vec3 prevCamRelPos = (depth >= 0.99999) ? camRelPos : (camRelPos + u_CameraDelta.xyz);
        vec4 prevClip = u_PrevViewProjMatrix * vec4(prevCamRelPos, 1.0);

        if (prevClip.w > 1.0e-5) {
            vec2 prevUV = (prevClip.xy / prevClip.w) * 0.5 + 0.5;
            if (prevUV.x > 0.001 && prevUV.x < 0.999 && prevUV.y > 0.001 && prevUV.y < 0.999) {
                vec3 historySrgb = textureLod(prevHistoryTex, prevUV, 0.0).rgb;

                // Sample 3x3 cross neighborhood in current frame to build YCoCg variance box
                vec2 texel = u_ScreenSize.zw;
                vec3 c0 = rgbToYCoCg(currSrgb);
                vec3 cN = rgbToYCoCg(resolveCurrentPixelSrgb(v_TexCoord + vec2( 0.0,  texel.y), bloom));
                vec3 cS = rgbToYCoCg(resolveCurrentPixelSrgb(v_TexCoord + vec2( 0.0, -texel.y), bloom));
                vec3 cE = rgbToYCoCg(resolveCurrentPixelSrgb(v_TexCoord + vec2( texel.x, 0.0), bloom));
                vec3 cW = rgbToYCoCg(resolveCurrentPixelSrgb(v_TexCoord + vec2(-texel.x, 0.0), bloom));

                vec3 m1 = (c0 + cN + cS + cE + cW) * 0.2;
                vec3 m2 = (c0 * c0 + cN * cN + cS * cS + cE * cE + cW * cW) * 0.2;
                vec3 sigma = sqrt(max(m2 - m1 * m1, vec3(1.0e-6)));

                float motionPixels = length((prevUV - v_TexCoord) * u_ScreenSize.xy);
                float gammaBox = mix(1.35, 0.80, clamp(motionPixels / 12.0, 0.0, 1.0));

                vec3 boxMin = m1 - gammaBox * sigma;
                vec3 boxMax = m1 + gammaBox * sigma;

                vec3 histYcc = clamp(rgbToYCoCg(historySrgb), boxMin, boxMax);
                vec3 clampedHistory = clamp(yCoCgToRgb(histYcc), 0.0, 1.0);

                float blendWeight = mix(0.86, 0.62, clamp(motionPixels / 16.0, 0.0, 1.0));
                currSrgb = mix(currSrgb, clampedHistory, blendWeight);
            }
        }
    }

    // Subtle filmic vignette
    vec2 vigUV = v_TexCoord * (1.0 - v_TexCoord.yx);
    float vig = pow(clamp(vigUV.x * vigUV.y * 16.0, 0.0, 1.0), 0.12);
    currSrgb *= mix(0.90, 1.0, vig);

    outColor = vec4(clamp(currSrgb, 0.0, 1.0), 1.0);
}
