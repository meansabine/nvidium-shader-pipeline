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


// Input HDR Color (GL_RGBA16F) from Pass 6 specification
layout(binding = 0) uniform sampler2D u_HdrColorTexture;

layout(location = 0) in vec2 v_Uv;
layout(location = 0) out vec4 outSrgbColor;

// Recipe F: ACES Filmic Tone Mapping Curve
vec3 acesFilm(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

// Soft knee luminance threshold for HDR bloom extraction
vec3 extractBrightPass(vec3 color, float threshold, float knee) {
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    float soft = clamp(luma - threshold + knee, 0.0, 2.0 * knee);
    soft = (soft * soft) / (4.0 * knee + 0.0001);
    float contribution = max(soft, luma - threshold) / max(luma, 0.0001);
    return color * max(contribution, 0.0);
}

// Multi-scale 13-tap Dual-Kawase / Tent Bloom Pyramid Approximation
vec3 sampleBloomPyramid(vec2 uv) {
    vec2 texel = 1.0 / u_ScreenSize;
    vec3 bloom = vec3(0.0);
    float totalWeight = 0.0;

    const float radii[4]   = float[](1.5, 3.5, 7.5, 14.0);
    const float weights[4] = float[](0.40, 0.30, 0.20, 0.10);
    const vec2  dirs[8]    = vec2[](
        vec2( 1.0,  0.0), vec2(-1.0,  0.0), vec2( 0.0,  1.0), vec2( 0.0, -1.0),
        vec2( 0.707,  0.707), vec2(-0.707,  0.707), vec2( 0.707, -0.707), vec2(-0.707, -0.707)
    );

    for (int octave = 0; octave < 4; ++octave) {
        float r = radii[octave];
        float w = weights[octave];
        vec3 octaveAccum = vec3(0.0);
        for (int i = 0; i < 8; ++i) {
            vec2 sampleUv = clamp(uv + dirs[i] * texel * r, vec2(0.001), vec2(0.999));
            vec3 hdrSample = texture(u_HdrColorTexture, sampleUv).rgb;
            octaveAccum += extractBrightPass(hdrSample, 1.1, 0.5);
        }
        bloom += (octaveAccum * 0.125) * w;
        totalWeight += w;
    }
    return bloom / max(totalWeight, 0.0001);
}

void main() {
    vec3 hdrColor = texture(u_HdrColorTexture, v_Uv).rgb;
    vec3 bloomColor = sampleBloomPyramid(v_Uv);

    // Composite bloom pyramid & apply exposure
    const float bloomIntensity = 0.12;
    const float exposure = 1.05;
    vec3 exposedHdr = (hdrColor + bloomColor * bloomIntensity) * exposure;

    // Apply ACES filmic curve
    vec3 mapped = acesFilm(exposedHdr);

    // Linear to sRGB gamma encoding
    vec3 srgb = pow(mapped, vec3(1.0 / 2.2));
    outSrgbColor = vec4(srgb, 1.0);
}
