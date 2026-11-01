# Nvidium Shader Pipeline (NSP) - Complete AI Master Guide & Generator Prompts
> **Document Purpose:** Feed this entire document directly to Google Gemini AI (or any state-of-the-art LLM on the web) to generate production-ready, breathtaking GLSL 4.60 shaders tailored specifically for the Nvidium GPU Mesh Shader Architecture.

---

## 1. Executive Summary & Diagnostic: Why Was Water & Shaders Not Working?

If shaders previously looked identical to vanilla Minecraft or water was completely standard, here is the exact architectural reason:
1. **The Fallback Safety Trap:** In earlier versions, Nvidium's default fragment shader only wrote diffuse color to Attachment 0 (`colour`), leaving Normals (Attachment 1) and Lights (Attachment 2) blank (`0.0`). The deferred lighting shader detected that lights and normals were empty and safely fell back to outputting vanilla pre-lit color so the game wouldn't be black.
2. **Water / Translucency Was Unhooked:** In Nvidium, opaque terrain and translucent terrain (water, stained glass) are handled by separate rasterizers (`PrimaryTerrainRasterizer` vs `TranslucentTerrainRasterizer`). The translucent rasterizer was hardcoded to run Nvidium's vanilla water shader and never executed the custom water pass.
3. **The Solution in this Guide:** This document specifies how Nvidium's Mesh Shaders feed real G-Buffer Multiple Render Targets (MRT), how the Deferred Lighting pass performs physical Cook-Torrance GGX + Cascaded Shadows + $1/r^2$ Torchlight, and how the Water Shader samples the opaque scene color and depth to execute true Beer-Lambert optical absorption, Gerstner waves, and Screen-Space Reflections (SSR).

---

## 2. Global Scene Uniform Buffer (`std140`, binding = 3)

All shaders have access to the global engine and camera state through uniform buffer binding point **`3`** (binding point 0 is strictly reserved for Nvidium's internal 64-bit unified GPU memory pointers).

```glsl
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
```

---

## 3. Pipeline Architecture & G-Buffer Layout

The Nvidium Shader Pipeline executes in 6 distinct sequential stages:

```
[ Nvidium Mesh Shaders ]
          │
          ▼
┌────────────────────────────────────────────────────────┐
│ PASS 1: G-Buffer MRT Generation                        │
│ - Attachment 0 (GL_RGBA16F): Albedo (RGB) + Roughness  │
│ - Attachment 1 (GL_RGBA16F): Normal (XYZ) + Metalness  │
│ - Attachment 2 (GL_RGBA16F): BlockLight, SkyLight, Emit│
│ - Depth (GL_DEPTH_COMPONENT24): Scene Hardware Depth   │
└────────────────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────┐
│ PASS 2: Cascaded Shadow Map (CSM)                      │
│ - 3 Cascades (16m, 64m, 256m) at 2048x2048 Array       │
└────────────────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────┐
│ PASS 3: Deferred PBR Lighting (Fullscreen Quad)        │
│ - Cook-Torrance GGX Specular + Lambertian Diffuse      │
│ - Cascaded Soft PCF Shadows (Poisson disk)             │
│ - Dynamic Colored Torchlight (Physical 1/r² Falloff)   │
│ - Atmospheric Skylight & Contact Crevice AO            │
│ - Output: HDR Color Texture (GL_RGBA16F)               │
└────────────────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────┐
│ PASS 4: Volumetric God Rays (Raymarched Fog)           │
│ - 32-step Mie forward scattering sun shafts            │
│ - Additively blended directly into HDR Color           │
└────────────────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────┐
│ PASS 5: Physical Water & Translucency                  │
│ - Reads Opaque Lit Color & Depth                       │
│ - Multi-octave Gerstner wave normal displacement       │
│ - Depth-based Beer-Lambert light extinction            │
│ - Screen-Space Reflections (SSR) + Fresnel reflection  │
│ - Sun specular glitter highlight                       │
└────────────────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────┐
│ PASS 6: Post-Processing, Bloom & ACES Tonemapping      │
│ - 4-octave Dual-Kawase HDR Bloom pyramid               │
│ - ACES Filmic Curve + Gamma 2.2 Encoding               │
│ - Outputs final sRGB to Minecraft's main display FBO   │
└────────────────────────────────────────────────────────┘
```

---

## 4. Standard Reusable Shader Algorithms (GLSL 4.60)

All AI-generated shaders should utilize these standardized mathematical recipes:

### Recipe A: World Position Reconstruction from Depth
```glsl
vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}
```

### Recipe B: Interleaved Gradient Noise (IGN)
Used for temporal jittering of raymarching and PCF shadows:
```glsl
float interleavedGradientNoise(vec2 pixelCoord) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    float frameOffset = float(u_FrameIndex % 64) * 5.588238;
    return fract(magic.z * fract(dot(pixelCoord + frameOffset, magic.xy)));
}
```

### Recipe C: Cook-Torrance GGX Microfacet BRDF
```glsl
float distributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float NdotH = max(dot(N, H), 0.0);
    float NdotH2 = NdotH * NdotH;
    float denom = (NdotH2 * (a2 - 1.0) + 1.0);
    return a2 / (3.14159265359 * denom * denom + 0.00001);
}

float geometrySchlickGGX(float NdotV, float roughness) {
    float r = (roughness + 1.0);
    float k = (r * r) / 8.0;
    return NdotV / (NdotV * (1.0 - k) + k);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}
```

### Recipe D: ACES Filmic Tone Mapping Curve
```glsl
vec3 acesFilm(vec3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}
```

---

## 5. Master Prompts for Gemini AI on the Web

Copy and paste the prompts below into Gemini AI on the web to generate individual shader stages.

---

### PROMPT 1: Ultra-Realistic Physical Water Shader (`water_surface.frag`)
```markdown
You are a senior graphics programmer specializing in modern Minecraft and real-time PBR water rendering.
Generate a complete, production-grade GLSL 4.60 fragment shader for "water_surface.frag" conforming to the Nvidium Shader Pipeline (NSP).

STRICT CONSTRAINTS:
1. Version: #version 460 core.
2. Uniforms: Use the exact SceneUniforms block on (std140, binding = 3).
3. Samplers:
   - layout(binding = 0) uniform sampler2D u_OpaqueSceneColor; (Lit opaque terrain color)
   - layout(binding = 1) uniform sampler2D u_SceneDepth;       (Opaque terrain depth)
   - layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap; (Cascaded shadow map)
4. Inputs:
   - layout(location = 0) in vec3 v_WorldPos;
   - layout(location = 1) in vec2 v_TexCoord;
   - layout(location = 2) in vec2 v_LightCoord;
5. Output:
   - layout(location = 0) out vec4 outHdrColor;

REQUIRED WATER FEATURES:
1. Multi-Octave Gerstner Waves:
   - Calculate animated surface normal using a bank of at least 6 directional Gerstner waves driven by u_Time.
   - Steepness, frequency, and wave vectors must produce believable organic ocean swell and fine ripples.
2. Depth-Based Beer-Lambert Optical Absorption:
   - Sample u_SceneDepth under the water pixel to determine water column depth: deltaDepth = distance(floor) - distance(surface).
   - Apply wavelength-dependent extinction exp(-sigma * deltaDepth) where red light absorbs quickly and cyan/blue penetrates deep.
   - In-scatter deep ocean ambient color for deep water.
3. Shoreline Contact Foam:
   - Detect shallow boundaries near blocks using smoothstep on water depth.
   - Modulate with animated noise to create subtle breaking foam along beaches and cliffs.
4. Screen-Space Reflections (SSR):
   - March reflected view ray R = reflect(-V, N) in screen space across u_SceneDepth.
   - If ray intersects geometry within tolerance, sample u_OpaqueSceneColor.
   - Fall back to realistic atmospheric sky gradient if SSR leaves the screen.
5. Schlick Fresnel & Sun Specular Glitter:
   - Water IOR = 1.333 (F0 = 0.02).
   - Evaluate Cook-Torrance GGX specular highlight from u_SunDirection with sharp highlight and shadow lookup.
6. Refraction Distortion:
   - Distort background seabed UVs using the perturbed wave normal, scaled by water depth to avoid refracting foreground objects.

Write the entire shader in a single clean GLSL code block without placeholders or omissions.
```

---

### PROMPT 2: High-End Deferred PBR Lighting Shader (`deferred_lighting.frag`)
```markdown
You are a senior graphics programmer specializing in deferred rendering pipelines and physically-based lighting (PBR).
Generate a complete, production-ready GLSL 4.60 fragment shader for "deferred_lighting.frag" conforming to the Nvidium Shader Pipeline (NSP).

STRICT CONSTRAINTS:
1. Version: #version 460 core.
2. Uniforms: Use the exact SceneUniforms block on (std140, binding = 3).
3. G-Buffer Samplers:
   - layout(binding = 0) uniform sampler2D u_AlbedoRoughness; (RGB: Albedo, A: Roughness)
   - layout(binding = 1) uniform sampler2D u_NormalMetallic;  (RGB: Encoded Normal, A: Metalness)
   - layout(binding = 2) uniform sampler2D u_LightEmission;   (R: Block Light, G: Sky Light, B: Emission)
   - layout(binding = 3) uniform sampler2D u_Depth;           (Hardware Depth)
   - layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap; (3-Cascade Shadow Map Array)
4. Input & Output:
   - layout(location = 0) in vec2 v_Uv;
   - layout(location = 0) out vec4 outHdrColor;

REQUIRED LIGHTING FEATURES:
1. Depth Unprojection & Sky Background:
   - If depth >= 0.999999, render realistic atmospheric sky with sun disk and Mie aureole.
2. Robust Pre-Lit Fallback:
   - If length(lightEmission.rg) < 0.001 && length(normalMetallic.xyz) < 0.01, output albedo directly so unhooked/vanilla blocks are never black.
   - If normal map is missing, derive geometric normal via normalize(cross(dFdx(worldPos), dFdy(worldPos))).
3. Direct Sunlight (Cook-Torrance GGX + Cascaded PCF Shadows):
   - Sample cascaded shadow map using 16-tap Poisson disk with Interleaved Gradient Noise jittering.
   - Select cascade automatically based on view distance and splits (u_CascadeSplits: 16m, 64m, 256m).
   - Evaluate D (GGX), G (Smith), and F (Schlick) specular terms plus energy-conserving Lambertian diffuse.
4. Physical 1/r² Dynamic Colored Torchlight:
   - Convert normalized Minecraft block light level [0..1] to physical distance.
   - Physical inverse-square falloff with smooth windowing at distance boundary.
   - Blackbody spectral temperature shift (~1900K deep ember to 2800K bright flame) with organic flame flicker.
5. Atmospheric Sky Ambient & Micro-Crevice AO:
   - Screen-space depth-horizon crevice AO calculation.
   - Hemispheric irradiance gradient (warm ground bounce to cool blue sky zenith).
6. LabPBR Emissive Lighting:
   - Emissive blocks (lava, glowstone, lanterns) illuminate their surroundings and glow.
7. Smooth Distance Fog:
   - Exponential atmospheric haze blending smoothly into horizon sky color.

Write the entire shader in a single clean GLSL code block without placeholders or omissions.
```

---

### PROMPT 3: Cinematic Raymarched Volumetric God Rays (`volumetric_fog.frag`)
```markdown
You are a senior rendering engineer specializing in participating media and atmospheric scattering.
Generate a complete, production-grade GLSL 4.60 fragment shader for "volumetric_fog.frag" conforming to the Nvidium Shader Pipeline (NSP).

STRICT CONSTRAINTS:
1. Version: #version 460 core.
2. Uniforms: Use the exact SceneUniforms block on (std140, binding = 3).
3. Samplers:
   - layout(binding = 3) uniform sampler2D u_Depth;
   - layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;
4. Input & Output:
   - layout(location = 0) in vec2 v_Uv;
   - layout(location = 0) out vec4 outVolumetricScattering; (Blended additively GL_ONE, GL_ONE)

REQUIRED ALGORITHM:
1. Ray Construction:
   - Unproject screen UV and depth to find world endpoint.
   - Clamp maximum raymarching distance to 192 meters.
2. Temporal Noise Jittering:
   - Offset initial ray step using Interleaved Gradient Noise (u_FrameIndex) to eliminate visible ray slicing bands.
3. 32-Step Raymarching:
   - March along view ray from camera to endpoint in 32 uniform steps.
   - Test each sample position against the Cascaded Shadow Map (CSM) to determine sunlight occlusion.
4. Henyey-Greenstein Phase Function:
   - Evaluate forward aerosol Mie scattering with asymmetry parameter g = 0.76.
5. Height-Based Density Falloff:
   - Atmospheric density decays exponentially with height above sea level (Y=63).
6. Beer-Lambert Transmittance:
   - Accumulate in-scattered radiance attenuated by transmittance extinction.

Write the entire shader in a single clean GLSL code block without placeholders or omissions.
```

---

### PROMPT 4: Dual-Kawase Bloom & ACES Tonemapping (`tonemap_bloom.frag`)
```markdown
You are a senior graphics programmer specializing in HDR color grading and post-processing.
Generate a complete, production-ready GLSL 4.60 fragment shader for "tonemap_bloom.frag" conforming to the Nvidium Shader Pipeline (NSP).

STRICT CONSTRAINTS:
1. Version: #version 460 core.
2. Uniforms: Use the exact SceneUniforms block on (std140, binding = 3).
3. Samplers:
   - layout(binding = 0) uniform sampler2D u_HdrColorTexture;
4. Input & Output:
   - layout(location = 0) in vec2 v_Uv;
   - layout(location = 0) out vec4 outSrgbColor;

REQUIRED POST-PROCESSING PIPELINE:
1. Multi-Octave Dual-Kawase / Tent Bloom Pyramid Approximation:
   - Sample 4 radial octaves (radii 1.5, 3.5, 7.5, 14.0 texels) with 8 sample directions per octave.
   - Extract HDR highlights using soft-knee thresholding (threshold = 1.1, knee = 0.5).
   - Composite bloom additively with controllable intensity.
2. Exposure Adjustment:
   - Apply exposure multiplier (e.g. 1.05).
3. ACES Filmic Tone Mapping:
   - Apply Narkowicz 2015 ACES filmic reproduction curve mapping HDR [0, inf) to display [0, 1].
4. Color Vibrance & Lift:
   - Subtle contrast lift and luminance-preserving saturation enhancement.
5. Gamma Correction:
   - Convert linear color to standard sRGB gamma (pow(color, 1.0 / 2.2)).

Write the entire shader in a single clean GLSL code block without placeholders or omissions.
```

---

## 6. How to Deploy Generated Shaders

Once Gemini AI on the web generates any shader:
1. Save the generated shader file into the respective resource folder:
   - `deferred_lighting.frag` $\rightarrow$ `src/main/resources/assets/nvidium_shaders/shaders/deferred/`
   - `water_surface.frag` $\rightarrow$ `src/main/resources/assets/nvidium_shaders/shaders/water/`
   - `volumetric_fog.frag` $\rightarrow$ `src/main/resources/assets/nvidium_shaders/shaders/deferred/`
   - `tonemap_bloom.frag` $\rightarrow$ `src/main/resources/assets/nvidium_shaders/shaders/post/`
   - `gtao.comp` $\rightarrow$ `src/main/resources/assets/nvidium_shaders/shaders/deferred/`
2. Run `gradlew build` in `nvidium-shader-pipeline`.
3. Copy the resulting JAR into your Prism Launcher `mods/` directory.
