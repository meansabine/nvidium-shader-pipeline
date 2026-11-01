# Nvidium Shader Pipeline (NSP) — AI Shader Developer Instructions

> **Purpose:** Feed this file to an AI (like Google Gemini) along with your shader feature request. It contains the exact technical specification, GPU data structures, uniform layouts, texture bindings, and mathematical models needed to generate functional, high-performance GLSL 4.60 shaders for the Nvidium Shader Pipeline.

---

## 1. System Overview & Core Directives

### What is the Nvidium Shader Pipeline (NSP)?
NSP is a dedicated, GPU-driven shader pipeline engineered specifically for **Nvidium** on Minecraft 1.21.1 (NeoForge + Sodium).
* **Target Architecture:** NVIDIA Turing, Ampere, Ada Lovelace, and Blackwell (GTX 16xx, RTX 20/30/40/50 series).
* **OpenGL Version:** OpenGL 4.60 Core Profile.
* **Core Paradigm:** Task + Mesh Shaders (`GL_NV_mesh_shader`) $\to$ G-Buffer Multiple Render Targets (MRT) $\to$ Deferred Lighting & Cascaded PCF Shadows $\to$ Volumetric Atmospherics $\to$ Forward/Translucent Water with SSR $\to$ HDR Bloom & ACES Tone Mapping.

### ⚠️ STRICT DIRECTIVES FOR AI CODE GENERATION
1. **DO NOT USE OPTIFINE / IRIS CONVENTIONS:**
   * **Do NOT** use OptiFine/Iris uniform names (e.g., `gbufferModelView`, `gbufferProjection`, `shadowModelView`, `shadowProjection`, `colortex0`..`colortex15`, `sunPosition`, `heldItemId`, `isEyeInWater`, etc.).
   * **Do NOT** write legacy OptiFine `.vsh`/`.fsh` files or composite program chains with `#define` configurations.
   * **DO** use the exact `SceneUniforms` block (`std140`, binding = 3) documented below (binding point 0 is reserved for Nvidium internal GPU command pointers).
2. **GLSL VERSION & EXTENSIONS:**
   * All shaders must start with `#version 460 core`.
   * For mesh shaders, require `#extension GL_NV_mesh_shader : require` and `#extension GL_NV_gpu_shader5 : require`.
   * For fragment shaders reading barycentrics, use `#extension GL_NV_fragment_shader_barycentric : require`.
3. **PBR STANDARD:**
   * Conform to the **LabPBR 1.3** standard for specular and normal mapping.
4. **DEPTH CONVENTION:**
   * OpenGL depth ranges from `0.0` (near plane) to `1.0` (far plane / sky).

---

## 2. Global Scene Uniform Buffer (`std140`, binding = 3)

All shaders have access to the global scene state through this uniform block. **Always copy this exact declaration into generated shaders:**

```glsl
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

## 3. Render Passes & Texture Bindings

### Pass 1: G-Buffer Generation (`gbuffer_terrain.frag`)
Outputs geometric attributes from Nvidium's mesh shaders into 3 Multiple Render Targets (MRT):

```glsl
layout(location = 0) out vec4 outAlbedoRoughness; // RGB: Base Albedo, A: Roughness [0.04, 1.0]
layout(location = 1) out vec4 outNormalMetallic;   // RGB: Encoded Normal (N * 0.5 + 0.5), A: Metalness [0.0, 1.0]
layout(location = 2) out vec4 outLightEmission;    // R: Block Light, G: Sky Light, B: Emission, A: Material Flag

// Samplers available during G-Buffer pass
layout(binding = 0) uniform sampler2D tex_diffuse;   // Block Atlas (RGBA8)
layout(binding = 1) uniform sampler2D tex_light;     // Vanilla Lightmap (block/sky light levels)
layout(binding = 2) uniform sampler2D tex_normals;   // LabPBR Normal Atlas (XYZ normal + height)
layout(binding = 3) uniform sampler2D tex_specular;  // LabPBR Specular Atlas (R=Smoothness, G=Metal, B=Emit)
```

### Pass 2: Shadow Pass (`shadow.mesh` / `shadow.frag`)
* Cascaded Shadow Maps (CSM) rendering 3 cascades (Near: 16m, Mid: 64m, Far: 256m) at 2048x2048 resolution.
* Bound to `GL_TEXTURE_2D_ARRAY` with `GL_DEPTH_COMPONENT32F` and hardware comparison mode enabled (`GL_COMPARE_REF_TO_TEXTURE`).

### Pass 3: Deferred Lighting Pass (`deferred_lighting.frag`)
Reads the G-Buffer and Shadow Map to compute direct sunlight, Cook-Torrance GGX specular highlights, ambient skylight, and torchlight:

```glsl
layout(binding = 0) uniform sampler2D u_AlbedoRoughness; // From G-Buffer RT0
layout(binding = 1) uniform sampler2D u_NormalMetallic;   // From G-Buffer RT1
layout(binding = 2) uniform sampler2D u_LightEmission;    // From G-Buffer RT2
layout(binding = 3) uniform sampler2D u_Depth;            // Hardware Scene Depth (GL_DEPTH_COMPONENT32F)
layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap; // 3-Cascade Shadow Map Array
```

### Pass 4: Volumetric Fog Pass (`volumetric_fog.frag`)
Raymarches sunlight through the frustum using the shadow cascades and Henyey-Greenstein phase scattering, blended additively (`GL_ONE`, `GL_ONE`) onto the HDR color target.

### Pass 5: Water & Translucent Pass (`water_surface.frag`)
Translucent rendering with Screen-Space Reflections (SSR), depth refraction, and Beer-Lambert water absorption:

```glsl
layout(binding = 0) uniform sampler2D u_OpaqueSceneColor; // Lit opaque scene from Pass 3
layout(binding = 1) uniform sampler2D u_SceneDepth;        // Opaque scene depth
```

### Pass 6: Post-Processing & Tone Mapping (`tonemap_bloom.frag`)
Reads the final HDR floating-point buffer, applies bloom pyramid composite, exposure adjustment, ACES / AgX filmic tone mapping, and outputs sRGB to the screen:

```glsl
layout(binding = 0) uniform sampler2D u_HdrColorTexture; // Input HDR Color (GL_RGBA16F)
```

---

## 4. Reusable Shader Algorithms (Recipes)

### Recipe A: World Position Reconstruction from Depth
```glsl
vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}
```

### Recipe B: Analytical Surface Normal & Tangent (TBN) Mapping
Since Nvidium's vertex layout is compressed (16 bytes) and does not store per-vertex normals, derive them dynamically from position derivatives:
```glsl
vec3 deriveGeometricNormal(vec3 worldPos) {
    return normalize(cross(dFdx(worldPos), dFdy(worldPos)));
}

mat3 constructTBN(vec3 worldPos, vec2 uv, vec3 geomNormal) {
    vec3 dX = dFdx(worldPos);
    vec3 dY = dFdy(worldPos);
    vec2 dUvX = dFdx(uv);
    vec2 dUvY = dFdy(uv);
    vec3 tangent = normalize(dX * dUvY.t - dY * dUvX.t);
    vec3 bitangent = cross(geomNormal, tangent);
    return mat3(tangent, bitangent, geomNormal);
}
```

### Recipe C: LabPBR 1.3 Material Decoding
```glsl
// Specular atlas: R = Smoothness, G = Metallic, B = Emissive
vec4 spec = texture(tex_specular, uv);
float roughness = clamp(1.0 - spec.r, 0.04, 1.0);
float metallic  = spec.g;
float emissive  = spec.a > 0.0 ? spec.b : 0.0;
```

### Recipe D: Cascaded Shadow Map Sampling (Hardware PCF)
```glsl
float sampleCascadedShadow(vec3 worldPos, float viewDist) {
    // Select cascade index based on camera distance
    int cascade = (viewDist < u_CascadeSplits.x) ? 0 : 
                  (viewDist < u_CascadeSplits.y) ? 1 : 2;

    vec4 shadowClip = u_CascadeViewProj[cascade] * vec4(worldPos, 1.0);
    vec3 shadowCoord = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;

    // Discard out-of-bounds coordinates
    if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0 ||
        shadowCoord.y < 0.0 || shadowCoord.y > 1.0 ||
        shadowCoord.z > 1.0) {
        return 1.0;
    }

    // 4-sample jittered PCF
    float shadow = 0.0;
    vec2 texelSize = vec2(1.0 / 2048.0);
    for (int x = -1; x <= 1; x += 2) {
        for (int y = -1; y <= 1; y += 2) {
            vec2 offset = vec2(x, y) * texelSize * 0.75;
            shadow += texture(u_ShadowMap, vec4(shadowCoord.xy + offset, float(cascade), shadowCoord.z - 0.0015));
        }
    }
    return shadow * 0.25;
}
```

### Recipe E: Cook-Torrance Microfacet GGX BRDF
```glsl
const float PI = 3.14159265359;

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

float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    return geometrySchlickGGX(max(dot(N, V), 0.0), roughness) * 
           geometrySchlickGGX(max(dot(N, L), 0.0), roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 evaluatePbrBRDF(vec3 N, vec3 V, vec3 L, vec3 albedo, float roughness, float metallic, vec3 lightColor) {
    vec3 H = normalize(V + L);
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 0.0);
    if (NdotL <= 0.0 || NdotV <= 0.0) return vec3(0.0);

    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    float NDF = distributionGGX(N, H, roughness);
    float G = geometrySmith(N, V, L, roughness);
    vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

    vec3 specular = (NDF * G * F) / (4.0 * NdotV * NdotL + 0.0001);
    vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);
    return (kD * albedo / PI + specular) * lightColor * NdotL;
}
```

### Recipe F: ACES Filmic Tone Mapping Curve
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

## 5. Ready-to-Use Prompt Templates for Gemini

Copy and paste one of these prompts directly into Gemini along with this `instructions.md` file:

### Prompt 1: Cinematic Lighting & Shadows
```
I am providing instructions.md which specifies the architecture of the Nvidium Shader Pipeline (NSP).
Please write a complete, production-ready 'deferred_lighting.frag' shader that implements:
1. PBR Cook-Torrance GGX direct sunlight with soft cascaded PCF shadows.
2. Dynamic colored torchlight with inverse-square physical falloff.
3. Realistic atmospheric skylight with subtle ambient occlusion.
4. Smooth distance fog blending into the sky color.
Conform strictly to the SceneUniforms layout and G-Buffer bindings in instructions.md.
```

### Prompt 2: Photorealistic Water with Reflections & Waves
```
Using the Nvidium Shader Pipeline specifications in instructions.md, write a complete 'water_surface.frag' shader featuring:
1. Screen-Space Reflections (SSR) reflecting the lit opaque scene.
2. Animated Gerstner micro-wave normal perturbations using u_Time.
3. Depth-based water refraction with Beer-Lambert physical color absorption (turning deep water dark turquoise/navy).
4. Schlick Fresnel blending between underwater refraction and surface reflection.
```

### Prompt 3: Atmospheric Volumetric God Rays
```
Referencing instructions.md for the Nvidium Shader Pipeline, write a 'volumetric_fog.frag' shader that:
1. Raymarches from u_CameraPos towards the reconstructed scene depth.
2. Samples the Cascaded Shadow Map (u_ShadowMap) at each step to detect shadowed vs unshadowed air volumes.
3. Computes forward Mie light scattering with the Henyey-Greenstein phase function.
4. Outputs additive in-scattered light shafts to composite over the scene.
```

### Prompt 4: Screen-Space Ground Truth Ambient Occlusion (GTAO)
```
Using the G-Buffer layout in instructions.md, write a compute shader ('gtao.comp') or fragment shader that:
1. Samples u_Depth and u_NormalMetallic.
2. Computes Ground Truth Ambient Occlusion (GTAO) using multi-directional horizon sweeps.
3. Applies a depth-aware bilateral blur pass to produce clean contact shadows in corners and crevices.
```
