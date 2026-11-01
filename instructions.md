# Nvidium Shader Pipeline (NSP) Technical Specification & Interface Guide

This document is a technical reference for the Nvidium Shader Pipeline (NSP). It describes the accessible GPU resources, uniform layouts, texture binding slots, render passes, and data contracts required to implement or generate shaders for this architecture.

---

## 1. Pipeline Architecture & Execution Flow

Rendering proceeds sequentially across 6 distinct phases:

```
[Nvidium Mesh Shader Rasterizer]
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Phase 1: Opaque Terrain G-Buffer (MRT)                 │
│ Output: GBuffer FBO (Attachments 0, 1, 2 + Depth)      │
└────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Phase 2: Cascaded Shadow Map (CSM)                     │
│ Output: 3-layer 2048x2048 Depth Array (Binding 4)      │
└────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Phase 3: Deferred Lighting Pass                        │
│ Input:  GBuffer Attachments 0-3, Shadow Map (Unit 4)   │
│ Output: HDR Color Buffer (GL_RGBA16F)                  │
└────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Phase 4: Volumetric Fog Pass                           │
│ Input:  Depth (Unit 3), Shadow Map (Unit 4)            │
│ Output: Additive blend into HDR Color Buffer           │
└────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Phase 5: Translucent Terrain / Water                   │
│ Input:  Opaque Scene Color (Unit 0), Depth (Unit 1)    │
│ Output: Rendered into Minecraft Main FBO               │
└────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────┐
│ Phase 6: Post-Process, Bloom & Tonemapping             │
│ Input:  HDR Color Buffer (Unit 0)                      │
│ Output: Minecraft Display Framebuffer (sRGB)           │
└────────────────────────────────────────────────────────┘
```

---

## 2. Global Scene Uniform Buffer (`std140`, binding = 3)

All shaders access global engine state via uniform buffer binding point **3**.  
*(Note: Binding point 0 is reserved for Nvidium's 64-bit unified GPU memory pointers).*

```glsl
layout(std140, binding = 3) uniform SceneUniforms {
    mat4  u_ViewMatrix;          // Camera view matrix
    mat4  u_ProjMatrix;          // Camera projection matrix
    mat4  u_ViewProjMatrix;      // Combined View * Projection matrix
    mat4  u_InvViewProjMatrix;   // Inverse View-Projection (clip to world space)
    mat4  u_CascadeViewProj[3];  // Orthographic View-Projection for 3 shadow cascades
    vec4  u_CascadeSplits;       // x = Cascade 0 (16m), y = Cascade 1 (64m), z = Cascade 2 (256m), w = unused
    vec4  u_SunDirection;        // Normalized vector pointing towards the sun
    vec4  u_SunColor;            // Sun color (RGB), intensity multiplier (A)
    vec4  u_AmbientColor;        // Sky ambient color (RGB)
    vec4  u_CameraPos;           // World-space camera position (XYZ)
    vec2  u_ScreenSize;          // Viewport width, height in pixels
    vec2  _pad0;                 // std140 16-byte alignment padding
    float u_Time;                // Elapsed game time in seconds
    int   u_FrameIndex;          // Monotonically increasing frame index
    float u_FogStart;            // Fog start distance in meters
    float u_FogEnd;              // Fog end distance in meters
    vec4  u_FogColor;            // Fog RGBA color
};
```

---

## 3. Pass Interfaces & Resource Bindings

### 3.1 G-Buffer (Multiple Render Targets)
* **FBO:** `pipeline.getGbuffer()`
* **Color Attachments:**
  * `layout(location = 0) out vec4 outAlbedoRoughness;`
    * RGB: Albedo color
    * A: Roughness $[0.04, 1.0]$
  * `layout(location = 1) out vec4 outNormalMetallic;`
    * RGB: Surface normal vector (encoded as $N \cdot 0.5 + 0.5$ or $[-1.0, 1.0]$)
    * A: Metalness $[0.0, 1.0]$
  * `layout(location = 2) out vec4 outLightEmission;`
    * R: Normalized block light level $[0.0, 1.0]$ ($0 = \text{dark}, 1.0 = \text{level 15}$)
    * G: Normalized sky light level $[0.0, 1.0]$ ($0 = \text{underground}, 1.0 = \text{open sky}$)
    * B: Material emission factor $[0.0, 1.0]$
    * A: Material flag (1.0 = terrain)
* **Depth Attachment:** Hardware depth (`GL_DEPTH_COMPONENT24`, shared with Minecraft's main depth buffer).

---

### 3.2 Deferred Lighting Pass (`deferred_lighting.frag`)
* **Vertex Shader:** `shaders/common/fullscreen.vert` (Generates screen-covering triangle from `gl_VertexID`).
* **Fragment Shader:** `shaders/deferred/deferred_lighting.frag`
* **Framebuffer:** HDR FBO (`GL_RGBA16F`, attachment 0). Depth test disabled, depth write disabled.
* **Samplers:**
  ```glsl
  layout(binding = 0) uniform sampler2D u_AlbedoRoughness;
  layout(binding = 1) uniform sampler2D u_NormalMetallic;
  layout(binding = 2) uniform sampler2D u_LightEmission;
  layout(binding = 3) uniform sampler2D u_Depth;
  layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;
  ```
* **Inputs & Outputs:**
  ```glsl
  layout(location = 0) in vec2 v_Uv;
  layout(location = 0) out vec4 outHdrColor;
  ```
* **Sky handling:** Fragments with `depth >= 0.999999` are sky pixels.

---

### 3.3 Volumetric Fog Pass (`volumetric_fog.frag`)
* **Vertex Shader:** `shaders/common/fullscreen.vert`
* **Fragment Shader:** `shaders/deferred/volumetric_fog.frag`
* **Framebuffer:** HDR FBO. Blend mode: `GL_ONE, GL_ONE` (additive). Depth test disabled.
* **Samplers:**
  ```glsl
  layout(binding = 3) uniform sampler2D u_Depth;
  layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;
  ```
* **Inputs & Outputs:**
  ```glsl
  layout(location = 0) in vec2 v_Uv;
  layout(location = 0) out vec4 outVolumetricScattering;
  ```

---

### 3.4 Water & Translucency Pass (`water_surface.frag`)
* **Execution:** Executed during translucent terrain rasterization or as a surface pass.
* **Samplers:**
  ```glsl
  layout(binding = 0) uniform sampler2D u_OpaqueSceneColor; // Resolved lit opaque terrain
  layout(binding = 1) uniform sampler2D u_SceneDepth;       // Opaque depth buffer
  layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap; // Cascaded shadow map
  ```
* **Inputs & Outputs:**
  ```glsl
  layout(location = 0) in vec3 v_WorldPos;
  layout(location = 1) in vec2 v_TexCoord;
  layout(location = 2) in vec2 v_LightCoord;
  layout(location = 0) out vec4 outHdrColor;
  ```

---

### 3.5 Post-Process & Tonemapping Pass (`tonemap_bloom.frag`)
* **Vertex Shader:** `shaders/common/fullscreen.vert`
* **Fragment Shader:** `shaders/post/tonemap_bloom.frag`
* **Framebuffer:** Minecraft main target (`mcTarget.frameBufferId`). Viewport = window dimensions.
* **Samplers:**
  ```glsl
  layout(binding = 0) uniform sampler2D u_HdrColorTexture; // Input HDR color from deferred & fog passes
  ```
* **Inputs & Outputs:**
  ```glsl
  layout(location = 0) in vec2 v_Uv;
  layout(location = 0) out vec4 outSrgbColor;
  ```

---

## 4. Mathematical Helper Functions

### 4.1 World Position Reconstruction from Depth
Transforms normalized screen UV $[0, 1]$ and hardware depth $[0, 1]$ back to camera-relative world coordinates:
```glsl
vec3 reconstructWorldPos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 worldPosH = u_InvViewProjMatrix * clip;
    return worldPosH.xyz / worldPosH.w;
}
```

### 4.2 Interleaved Gradient Noise (IGN)
Generates high-frequency spatial-temporal noise for dithering and jitter:
```glsl
float interleavedGradientNoise(vec2 pixelCoord) {
    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    float frameOffset = float(u_FrameIndex % 64) * 5.588238;
    return fract(magic.z * fract(dot(pixelCoord + frameOffset, magic.xy)));
}
```

### 4.3 Cascaded Shadow Map Coordinate Selection
Selects shadow cascade index based on view distance and projects sample into shadow atlas coordinates:
```glsl
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
```

### 4.4 Fullscreen Triangle Generation (`fullscreen.vert`)
Generates a clip-space triangle covering the full viewport using 3 vertices without vertex buffer objects:
```glsl
#version 460 core
layout(location = 0) out vec2 v_Uv;

void main() {
    v_Uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(v_Uv * 2.0 - 1.0, 0.0, 1.0);
}
```

---

## 5. File Layout & Resource Locations

In `nvidium-shader-pipeline`:

| Shader Stage | File Path in Project |
|---|---|
| Fullscreen Vertex | `src/main/resources/assets/nvidium_shaders/shaders/common/fullscreen.vert` |
| Deferred Lighting | `src/main/resources/assets/nvidium_shaders/shaders/deferred/deferred_lighting.frag` |
| Volumetric Fog | `src/main/resources/assets/nvidium_shaders/shaders/deferred/volumetric_fog.frag` |
| GTAO Compute | `src/main/resources/assets/nvidium_shaders/shaders/deferred/gtao.comp` |
| Water Vertex | `src/main/resources/assets/nvidium_shaders/shaders/water/water_surface.vert` |
| Water Fragment | `src/main/resources/assets/nvidium_shaders/shaders/water/water_surface.frag` |
| Post-Process / Bloom | `src/main/resources/assets/nvidium_shaders/shaders/post/tonemap_bloom.frag` |
| G-Buffer Terrain | `src/main/resources/assets/nvidium_shaders/shaders/gbuffer/terrain_gbuffer.frag` |

---

## 6. Generator Prompts for LLMs

Use the prompts below to generate specific shaders. Each prompt contains only the interface specification, uniform layout, and functional requirements.

---

### Prompt: Deferred Lighting Shader (`deferred_lighting.frag`)
```
Generate a complete GLSL 4.60 fragment shader for "deferred_lighting.frag".

Interface:
- #version 460 core
- Uniform Buffer: layout(std140, binding = 3) uniform SceneUniforms (View, Proj, InvViewProj, CascadeViewProj[3], CascadeSplits, SunDirection, SunColor, AmbientColor, CameraPos, ScreenSize, Time, FrameIndex, FogStart, FogEnd, FogColor)
- Samplers:
  - layout(binding = 0) uniform sampler2D u_AlbedoRoughness;
  - layout(binding = 1) uniform sampler2D u_NormalMetallic;
  - layout(binding = 2) uniform sampler2D u_LightEmission;
  - layout(binding = 3) uniform sampler2D u_Depth;
  - layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;
- Input: layout(location = 0) in vec2 v_Uv;
- Output: layout(location = 0) out vec4 outHdrColor;

Functional Requirements:
1. Reconstruct world position from u_Depth and u_InvViewProjMatrix.
2. If depth >= 0.999999, output atmospheric sky gradient.
3. Fallback: if length(lightEmission.rg) < 0.001 and length(normalMetallic.xyz) < 0.01, output albedo directly.
4. If normal is zero, compute geometric normal from dFdx(worldPos) and dFdy(worldPos); otherwise decode normal from [0, 1] to [-1, 1].
5. Evaluate direct sunlight using Cook-Torrance GGX (D = GGX, G = Smith, F = Schlick).
6. Sample u_ShadowMap across the appropriate cascade (u_CascadeSplits: x=16m, y=64m, z=256m) with PCF filtering.
7. Compute torch lighting from lightEmission.r with inverse-square falloff and lightEmission.b for material emission.
8. Apply ambient irradiance scaled by lightEmission.g (sky light).
9. Blend with distance fog (u_FogStart, u_FogEnd, u_FogColor).
10. Output linear HDR color in outHdrColor.
```

---

### Prompt: Volumetric Fog Shader (`volumetric_fog.frag`)
```
Generate a complete GLSL 4.60 fragment shader for "volumetric_fog.frag".

Interface:
- #version 460 core
- Uniform Buffer: layout(std140, binding = 3) uniform SceneUniforms (View, Proj, InvViewProj, CascadeViewProj[3], CascadeSplits, SunDirection, SunColor, AmbientColor, CameraPos, ScreenSize, Time, FrameIndex)
- Samplers:
  - layout(binding = 3) uniform sampler2D u_Depth;
  - layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;
- Input: layout(location = 0) in vec2 v_Uv;
- Output: layout(location = 0) out vec4 outVolumetricScattering;

Functional Requirements:
1. Reconstruct ray end world position from u_Depth. Clamp march distance to 192 meters.
2. Apply ray start jitter using Interleaved Gradient Noise driven by u_FrameIndex.
3. Raymarch from camera position to endpoint in 32 uniform steps.
4. At each step, test shadow visibility against u_ShadowMap using the corresponding cascade.
5. Evaluate phase function (Henyey-Greenstein, g = 0.76) for angle between ray direction and u_SunDirection.
6. Apply exponential height falloff based on world position Y.
7. Accumulate in-scattered radiance and integrate Beer-Lambert transmittance.
8. Output accumulated scattering in outVolumetricScattering (will be blended with GL_ONE, GL_ONE).
```

---

### Prompt: Water Surface Shader (`water_surface.frag`)
```
Generate a complete GLSL 4.60 fragment shader for "water_surface.frag".

Interface:
- #version 460 core
- Uniform Buffer: layout(std140, binding = 3) uniform SceneUniforms (View, Proj, InvViewProj, CascadeViewProj[3], CascadeSplits, SunDirection, SunColor, AmbientColor, CameraPos, ScreenSize, Time, FrameIndex, FogStart, FogEnd, FogColor)
- Samplers:
  - layout(binding = 0) uniform sampler2D u_OpaqueSceneColor;
  - layout(binding = 1) uniform sampler2D u_SceneDepth;
  - layout(binding = 4) uniform sampler2DArrayShadow u_ShadowMap;
- Inputs:
  - layout(location = 0) in vec3 v_WorldPos;
  - layout(location = 1) in vec2 v_TexCoord;
  - layout(location = 2) in vec2 v_LightCoord;
- Output: layout(location = 0) out vec4 outHdrColor;

Functional Requirements:
1. Compute animated surface normal using Gerstner wave formulation driven by u_Time and world position XZ.
2. Unproject seabed depth under the water fragment from u_SceneDepth. Compute water column depth: deltaDepth = floorDistance - surfaceDistance.
3. Distort screen UV by wave normal scaled by deltaDepth to sample u_OpaqueSceneColor for refraction.
4. Apply Beer-Lambert absorption: exp(-sigmaExtinction * deltaDepth) to refracted color.
5. March reflected ray R = reflect(-V, N) in screen space across u_SceneDepth to compute Screen-Space Reflections (SSR), falling back to sky gradient.
6. Evaluate Fresnel reflectance (F0 = 0.02) and blend refraction and reflection.
7. Add Cook-Torrance GGX specular highlight from u_SunDirection with shadow lookup from u_ShadowMap.
8. Blend shore/crest foam and distance fog. Output linear HDR color in outHdrColor.
```

---

### Prompt: Post-Processing & Tonemapping Shader (`tonemap_bloom.frag`)
```
Generate a complete GLSL 4.60 fragment shader for "tonemap_bloom.frag".

Interface:
- #version 460 core
- Uniform Buffer: layout(std140, binding = 3) uniform SceneUniforms (ScreenSize)
- Samplers:
  - layout(binding = 0) uniform sampler2D u_HdrColorTexture;
- Input: layout(location = 0) in vec2 v_Uv;
- Output: layout(location = 0) out vec4 outSrgbColor;

Functional Requirements:
1. Sample u_HdrColorTexture at v_Uv.
2. Extract bright highlights exceeding threshold 1.1 with smooth knee 0.5.
3. Compute multi-octave radial blur (radii 1.5, 3.5, 7.5, 14.0 texels) to approximate bloom and add to HDR color.
4. Apply exposure scaling.
5. Apply ACES Filmic tonemapping curve: clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0).
6. Convert output to sRGB gamma: pow(color, vec3(1.0 / 2.2)).
7. Output RGBA in outSrgbColor.
```
