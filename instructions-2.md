# Nvidium Shader Pipeline (NSP) Technical Specification & Architectural Guide

This document is a structural reference for the Nvidium Shader Pipeline (NSP). It specifies the accessible GPU resources, uniform buffer layouts, texture binding slots, render passes, vertex decoding interfaces, shader pack archive structure, and condensed structural outlines of all pipeline shader files.

---

## 1. Pipeline Architecture & Execution Flow

Frame rendering proceeds in 7 sequential phases:

```
[Nvidium Mesh Shader Rasterizer]
             │
             ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 1: Shadow Pass (CSM)                                             │
│ Shader:   shadow/shadow.frag                                           │
│ Target:   Cascaded Shadow Map Texture Array (3 layers, 2048x2048)      │
│ Geometry: Mesh-shader opaque terrain drawn per cascade                 │
└────────────────────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 2: Opaque Terrain G-Buffer (MRT)                                 │
│ Shader:   gbuffer/terrain_gbuffer.frag                                 │
│ Target:   GBuffer FBO (Attachments 0, 1, 2 + Minecraft Shared Depth)   │
│ Geometry: Nvidium Mesh Shader terrain pipeline                         │
└────────────────────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 3: Deferred Lighting Pass                                        │
│ Shader:   deferred/deferred_lighting.frag                              │
│ Target:   HDR Scene FBO (GL_RGBA16F, mip-mapped)                       │
│ Inputs:   GBuffer Attachments 0-2, Depth (Unit 3), Shadow Map (Unit 4) │
└────────────────────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 4: Volumetric Fog Pass                                           │
│ Shader:   deferred/volumetric_fog.frag                                 │
│ Target:   Fog FBO (GL_RGBA16F, rgb = inscatter, a = transmittance)     │
│ Inputs:   Depth (Unit 0), Shadow Map (Unit 1)                          │
└────────────────────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 5: Post-Process & Tonemapping (Pre-Translucent)                  │
│ Shader:   post/tonemap_bloom.frag                                      │
│ Target:   History FBO + Blit to Minecraft mainRenderTarget             │
│ Inputs:   HDR Scene (Unit 0), Volumetric Fog (Unit 1),                 │
│           Light Emission (Unit 2), Depth (Unit 3), TAA History (Unit 4)│
└────────────────────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 6: Translucent Terrain / Water Shading                           │
│ Shader:   water/water_surface.frag                                     │
│ Target:   Minecraft mainRenderTarget (Depth Test LEQUAL, Depth Mask F) │
│ Inputs:   Atlas (0), Lightmap (1), LabPBR Normal (2), Specular (3),    │
│           Opaque Color Copy (4), Depth Copy (5), Shadow Map (6)        │
└────────────────────────────────────────────────────────────────────────┘
             │
             ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 7: Final Composite                                               │
│ Shader:   post/final_composite.frag                                    │
│ Target:   Minecraft Display Framebuffer                                │
│ Inputs:   Scene Color (Unit 0), Scene Depth (Unit 1),                  │
│           Normal/Metallic (Unit 2), Light Emission (Unit 3)            │
│ Function: Geometric-edge FXAA + Contrast Adaptive Sharpening (CAS)     │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Uniform Buffer Objects (UBO)

### 2.1 Global Scene Uniforms (`layout(std140, binding = 3) uniform SceneUniforms`)

Included via `#include "../common/scene_uniforms.glsl"`. All camera matrices are camera-relative (camera at `0, 0, 0`):

```glsl
layout(std140, binding = 3) uniform SceneUniforms {
    mat4 u_ViewMatrix;            // [0..64]    Camera-relative View Matrix
    mat4 u_ProjMatrix;            // [64..128]  Projection Matrix
    mat4 u_ViewProjMatrix;        // [128..192] Proj * View
    mat4 u_InvViewProjMatrix;     // [192..256] inverse(Proj * View)
    mat4 u_PrevViewProjMatrix;    // [256..320] Previous frame's Proj * View

    mat4 u_CascadeViewProj0;      // [320..384] Light ViewProj for Cascade 0 (camera-relative)
    mat4 u_CascadeViewProj1;      // [384..448] Light ViewProj for Cascade 1 (camera-relative)
    mat4 u_CascadeViewProj2;      // [448..512] Light ViewProj for Cascade 2 (camera-relative)
    vec4 u_CascadeSplits;         // [512..528] x=split0, y=split1, z=split2, w=maxShadowDist

    vec4 u_SunDirection;          // [528..544] xyz=dir toward sun, w=sunElevationSin
    vec4 u_SunColor;              // [544..560] rgb=sun color, w=sunIlluminance
    vec4 u_MoonDirection;         // [560..576] xyz=dir toward moon, w=moonPhaseFactor
    vec4 u_MoonColor;             // [576..592] rgb=moon color, w=moonIlluminance
    vec4 u_ShadowLightDir;        // [592..608] xyz=active shadow light dir, w=shadowFade(0..1)

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
    vec4 u_FogParams;             // [768..784] x=fogStart, y=fogEnd, z=volumetricFogEnabled, w=dimensionId (0=overworld, -1=nether, 1=end)
    vec4 u_PostParams;            // [784..800] x=exposure, y=bloomIntensity, z=saturation, w=tonemapOperator (0=AgX, 1=ACES, 2=Neutral)
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
```

### 2.2 Pack Options Uniforms (`layout(std140, binding = 4) uniform PackOptions`)

Included via `#include "../common/options.glsl"`. Provides user-configurable sliders from Video Settings:

```glsl
layout(std140, binding = 4) uniform PackOptions {
    vec4 u_PackOptions[16];
};

#define OPT_SUNLIGHT_INTENSITY      u_PackOptions[0].x  // slot 0
#define OPT_AMBIENT_INTENSITY       u_PackOptions[0].y  // slot 1
#define OPT_EMISSIVE_STRENGTH       u_PackOptions[0].z  // slot 2
#define OPT_HANDHELD_DYNAMIC_LIGHT  u_PackOptions[0].w  // slot 3

#define OPT_CLOUD_MODE              u_PackOptions[1].x  // slot 4 (0=Off, 1=2D Cirrus, 2=Volumetric)
#define OPT_CLOUD_COVERAGE          u_PackOptions[1].y  // slot 5 (0.0 .. 1.0)
#define OPT_GODRAYS_STRENGTH        u_PackOptions[1].z  // slot 6 (0.0 .. 2.5)
#define OPT_ATMOS_HAZINESS          u_PackOptions[1].w  // slot 7 (0.0 .. 2.0)

#define OPT_WATER_WAVE_HEIGHT       u_PackOptions[2].x  // slot 8 (0.0 .. 2.0)
#define OPT_WATER_CLARITY           u_PackOptions[2].y  // slot 9 (0.1 .. 3.0)
#define OPT_WATER_CAUSTICS          u_PackOptions[2].z  // slot 10 (0.0 .. 2.0)
#define OPT_RAIN_PUDDLES            u_PackOptions[2].w  // slot 11 (0.0 .. 1.0)

#define OPT_SUBSURFACE_SCATTERING   u_PackOptions[3].x  // slot 12 (0.0 .. 2.0)
#define OPT_NORMAL_BUMP_STRENGTH    u_PackOptions[3].y  // slot 13 (0.0 .. 2.0)
#define OPT_WIND_SPEED              u_PackOptions[3].z  // slot 14 (0.0 .. 3.0)
#define OPT_SHADOW_SOFTNESS         u_PackOptions[3].w  // slot 15 (0.5 .. 3.0)
```

---

## 3. Pass Interfaces & Resource Bindings

### 3.1 Stage `terrain`: Opaque Terrain G-Buffer (`gbuffer/terrain_gbuffer.frag`)
* **Type:** Mesh-Shader Fragment Stage.
* **Samplers:**
  * `layout(binding = 0) uniform sampler2D tex_diffuse;` (Minecraft Block Atlas)
  * `layout(binding = 1) uniform sampler2D tex_light;` (Minecraft Lightmap)
  * `layout(binding = 2) uniform sampler2D tex_normal;` (LabPBR normal atlas `_n`)
  * `layout(binding = 3) uniform sampler2D tex_specular;` (LabPBR specular atlas `_s`)
* **Vertex Inputs:**
  ```glsl
  layout(location = 5) in NvidiumVertexData {
      vec2 v_uv;
      vec3 v_color;
      vec2 v_lightUV;
  } nv_in;
  ```
* **Color Attachments (MRT):**
  * `layout(location = 0) out vec4 outAlbedoRoughness;` (RGB = Linear albedo, A = Roughness)
  * `layout(location = 1) out vec4 outNormalMetallic;` (RGB = World normal, A = Packed metallic + materialId)
  * `layout(location = 2) out vec4 outLightEmission;` (R = Block light, G = Sky light, B = Emissive, A = AO)
* **Depth Attachment:** Hardware depth shared with Minecraft's `mainRenderTarget`.

---

### 3.2 Stage `shadow`: Shadow Map Alpha Cutoff (`shadow/shadow.frag`)
* **Type:** Mesh-Shader Depth Fragment Stage.
* **Samplers:**
  * `layout(binding = 0) uniform sampler2D tex_diffuse;`
* **Vertex Inputs:**
  ```glsl
  layout(location = 5) in NvidiumVertexData {
      vec2 v_uv;
      vec3 v_color;
      vec2 v_lightUV;
  } nv_in;
  ```
* **Behavior:** Tests alpha against `getVertexAlphaCutoff(uint(gl_PrimitiveID) & 3u)`. Calls `discard` if cutout pixel fails test.

---

### 3.3 Stage `deferred`: Deferred Lighting Pass (`deferred/deferred_lighting.frag`)
* **Type:** Fullscreen Quad Fragment Stage.
* **Framebuffer:** HDR Scene FBO (`GL_RGBA16F`, attachment 0). Depth test disabled.
* **Samplers:**
  * `layout(binding = 0) uniform sampler2D gAlbedoRoughness;`
  * `layout(binding = 1) uniform sampler2D gNormalMetallic;`
  * `layout(binding = 2) uniform sampler2D gLightEmission;`
  * `layout(binding = 3) uniform sampler2D gDepth;` (Hardware depth $[0.0, 1.0]$)
  * `layout(binding = 4) uniform sampler2DArray shadowMapArray;` (3-layer cascaded shadow map)
* **Input / Output:**
  * `in vec2 v_TexCoord;`
  * `layout(location = 0) out vec4 outHdrColor;`

---

### 3.4 Stage `fog`: Volumetric Fog Pass (`deferred/volumetric_fog.frag`)
* **Type:** Fullscreen Quad Fragment Stage.
* **Framebuffer:** Fog FBO (`GL_RGBA16F`).
* **Samplers:**
  * `layout(binding = 0) uniform sampler2D gDepth;`
  * `layout(binding = 1) uniform sampler2DArray shadowMapArray;`
* **Input / Output:**
  * `in vec2 v_TexCoord;`
  * `layout(location = 0) out vec4 outVolumetricFog;` (RGB = inscatter, A = transmittance)

---

### 3.5 Stage `translucent`: Translucent Terrain & Water Surface (`water/water_surface.frag`)
* **Type:** Mesh-Shader Translucent Fragment Stage.
* **Framebuffer:** Minecraft `mainRenderTarget` (Depth test `GL_LEQUAL`, depth write `false`).
* **Samplers:**
  * `layout(binding = 0) uniform sampler2D tex_diffuse;` (Block Atlas)
  * `layout(binding = 1) uniform sampler2D tex_light;` (Lightmap)
  * `layout(binding = 2) uniform sampler2D tex_normal;` (LabPBR normal)
  * `layout(binding = 3) uniform sampler2D tex_specular;` (LabPBR specular)
  * `layout(binding = 4) uniform sampler2D tex_scene_color;` (Snapshot copy of opaque scene color with mipmaps)
  * `layout(binding = 5) uniform sampler2D tex_scene_depth;` (Snapshot copy of opaque scene depth)
  * `layout(binding = 6) uniform sampler2DArray tex_shadow_map;` (Cascaded shadow map array)
* **Vertex Inputs:**
  ```glsl
  layout(location = 5) in NvidiumVertexData {
      vec2 v_uv;
      vec3 v_color;
      vec2 v_lightUV;
  } nv_in;
  ```
* **Output:**
  * `layout(location = 0) out vec4 outFragColor;`

---

### 3.6 Stage `post`: Post-Process & Tonemapping (`post/tonemap_bloom.frag`)
* **Type:** Fullscreen Quad Fragment Stage.
* **Framebuffer:** History FBO (`GL_RGBA16F`) and blitted to Minecraft `mainRenderTarget`.
* **Samplers:**
  * `layout(binding = 0) uniform sampler2D hdrSceneTex;` (Mip-mapped HDR scene color)
  * `layout(binding = 1) uniform sampler2D volumetricFogTex;` (RGBA16F fog inscatter & transmittance)
  * `layout(binding = 2) uniform sampler2D gLightEmission;`
  * `layout(binding = 3) uniform sampler2D gDepth;`
  * `layout(binding = 4) uniform sampler2D prevHistoryTex;` (Previous frame's resolved output for TAA)
  * `layout(binding = 5) uniform sampler2D gNormalMetallic;`
* **Output:**
  * `layout(location = 0) out vec4 outColor;`

---

### 3.7 Stage `final`: Final Composite (`post/final_composite.frag`)
* **Type:** Fullscreen Quad Fragment Stage.
* **Framebuffer:** Display Framebuffer.
* **Samplers:**
  * `layout(binding = 0) uniform sampler2D sceneColorTex;`
  * `layout(binding = 1) uniform sampler2D sceneDepthTex;`
  * `layout(binding = 2) uniform sampler2D gNormalMetallic;`
  * `layout(binding = 3) uniform sampler2D gLightEmission;`
* **Output:**
  * `layout(location = 0) out vec4 outFinalColor;`

---

## 4. Semantic Material Classification & Packing

The CPU classifier assigns every block and fluid a 5-bit semantic material ID (`0..31`), encoded into Nvidium vertex data and decoded in GLSL via `decodeVertexMaterial(Vertex v)`.

### 4.1 Material Constants (`materials.glsl`)
```glsl
const uint MAT_SOLID          = 0u;
const uint MAT_WATER          = 1u;
const uint MAT_LAVA           = 2u;
const uint MAT_FOLIAGE_WAVING = 3u;
const uint MAT_LEAVES         = 4u;
const uint MAT_GLASS          = 5u;
const uint MAT_ICE            = 6u;
const uint MAT_EMISSIVE       = 7u;
const uint MAT_METAL          = 8u;
const uint MAT_GEM            = 9u;
const uint MAT_POLISHED       = 10u;
const uint MAT_WET            = 11u;
const uint MAT_SAND_SNOW      = 12u;
const uint MAT_PORTAL         = 13u;
const uint MAT_SLIME_HONEY    = 14u;
const uint MAT_ORE            = 15u;
```

### 4.2 Material & Metallic Packing (`Attachment 1 Alpha`)
Continuous metallic $[0.0, 1.0]$ and integer material ID $[0, 31]$ are packed into a single float:
```glsl
float packMetallicAndMaterial(float metallic, uint materialId) {
    float m = clamp(metallic, 0.0, 0.98);
    return float(materialId & 31u) + m;
}

void unpackMetallicAndMaterial(float packedVal, out float metallic, out uint materialId) {
    float floored = floor(max(0.0, packedVal) + 1.0e-4);
    materialId = uint(clamp(floored, 0.0, 31.0));
    metallic = clamp((packedVal - floored) / 0.98, 0.0, 1.0);
}
```

---

## 5. Mathematical Helper Functions

Included via `#include "../common/scene_uniforms.glsl"`:

### 5.1 Camera-Relative & World Coordinate Unprojection
```glsl
// Reconstructs camera-relative position (camera sits at 0, 0, 0)
vec3 reconstructCameraRelativePos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 relPos = u_InvViewProjMatrix * clip;
    return relPos.xyz / max(relPos.w, 1.0e-7);
}

// Reconstructs absolute world position (for noise, cloud planes, and wave coordinates)
vec3 reconstructWorldPos(vec2 uv, float depth) {
    return reconstructCameraRelativePos(uv, depth) + u_CameraPos.xyz;
}

// Projects camera-relative position back to screen UV and depth [0..1]
vec3 projectCameraRelativeToUVDepth(vec3 camRelPos) {
    vec4 clip = u_ViewProjMatrix * vec4(camRelPos, 1.0);
    if (clip.w <= 1.0e-5) return vec3(-1.0);
    vec3 ndc = clip.xyz / clip.w;
    return ndc * 0.5 + 0.5;
}
```

### 5.2 Interleaved Gradient Noise (IGN)
```glsl
float interleavedGradientNoise(vec2 pixelCoord, float frameOffset) {
    vec2 p = pixelCoord + frameOffset * 5.588238;
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}
```

### 5.3 Cascaded Shadow Map Matrix Access
```glsl
mat4 getCascadeViewProj(int cascadeIdx) {
    if (cascadeIdx == 0) return u_CascadeViewProj0;
    if (cascadeIdx == 1) return u_CascadeViewProj1;
    return u_CascadeViewProj2;
}
```

---

## 6. Shader Pack ZIP Archive Structure

Shader packs are packaged as standard `.zip` files (or uncompressed folders) placed in either:
* `.minecraft/shaderpacks/<PackName>.zip`
* `.minecraft/config/nvidium_shaders/<PackName>.zip`

### 6.1 Directory Tree Inside the ZIP
```
<PackName>.zip
│
├── pack.json                          # Shader pack metadata, stages, and options schema
│
├── common/
│   ├── fullscreen.vert                # Fullscreen quad vertex stage
│   ├── scene_uniforms.glsl            # Global SceneUniforms UBO definition & unprojection helpers
│   ├── options.glsl                   # PackOptions UBO definition & macro bindings
│   ├── materials.glsl                 # Semantic block material constants & packing
│   ├── pbr_brdf.glsl                  # Cook-Torrance GGX, Burley diffuse, and Jimenez GTAO multi-bounce
│   ├── shadows.glsl                   # 3-cascade PCF filtering and volumetric shadow tap
│   ├── atmosphere.glsl                # Physical sky gradient, clouds, and aerial perspective haze
│   └── post_tonemap.glsl              # AgX, ACES, and Khronos Neutral tonemapping curves
│
├── gbuffer/
│   └── terrain_gbuffer.frag           # Phase 2: Mesh-shader opaque terrain G-Buffer MRT fragment shader
│
├── shadow/
│   └── shadow.frag                    # Phase 1: Mesh-shader shadow map alpha-cutoff fragment shader
│
├── deferred/
│   ├── deferred_lighting.frag         # Phase 3: PBR lighting, GTAO, handheld light, and cascaded shadows
│   └── volumetric_fog.frag            # Phase 4: Raymarched crepuscular sun/moon shafts and height fog
│
├── water/
│   └── water_surface.frag             # Phase 6: Gerstner waves, caustics, SSR, and dielectric translucents
│
└── post/
    ├── tonemap_bloom.frag             # Phase 5: Multi-octave tent bloom, TAA, and exposure tonemapping
    └── final_composite.frag           # Phase 7: Geometric-edge FXAA and Contrast Adaptive Sharpening (CAS)
```

---

## 7. Condensed Outlines of Default Shader Files

The outlines below summarize the core structure, inputs, outputs, algorithms, and logic flow of each default shader file.

---

### 7.1 `pack.json`
* **Role:** Pack definition manifest and configuration schema.
* **Sections:**
  1. **Metadata:** `name`, `version`, `author`, `description`.
  2. **`stages`**: Maps stage IDs (`terrain`, `shadow`, `deferred`, `fog`, `translucent`, `post`, `final`, `fullscreen_vert`) to their relative file paths inside the archive.
  3. **`options`**: Array of user settings exposed in Video Settings. Each option declares `id`, `name`, `category`, `type` (`float`, `bool`, `enum`), default values, ranges, and a unique `slot` index (`0..15`) mapped to `u_PackOptions[slot / 4][slot % 4]`.

---

### 7.2 `common/fullscreen.vert`
* **Role:** Generates fullscreen triangle without vertex buffers.
* **Logic:**
  * Uses `gl_VertexID` bitwise manipulation:
    * `v_TexCoord = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);`
    * `gl_Position = vec4(v_TexCoord * 2.0 - 1.0, 0.0, 1.0);`
  * Emits 3 vertices that cover the entire $[-1.0, 1.0]$ clip-space viewport.

---

### 7.3 `common/scene_uniforms.glsl`
* **Role:** Defines standard UBO `SceneUniforms` (`binding = 3`) and world/clip transformations.
* **Key Components:**
  * `layout(std140, binding = 3) uniform SceneUniforms` block (848 bytes).
  * `reconstructCameraRelativePos(uv, depth)`: Unprojects screen UV and depth $[0, 1]$ into camera-relative coordinates using `u_InvViewProjMatrix`.
  * `reconstructWorldPos(uv, depth)`: Unprojects and adds `u_CameraPos.xyz`.
  * `projectCameraRelativeToUVDepth(camRelPos)`: Projects camera-relative coordinates into screen UV and $[0, 1]$ depth via `u_ViewProjMatrix`.
  * Noise helpers: `interleavedGradientNoise()`, `hash12()`, `hash13()`, `noise2D()`, `fbm2D()`.

---

### 7.4 `common/options.glsl`
* **Role:** Defines UBO `PackOptions` (`binding = 4`) and option access macros.
* **Key Components:**
  * `layout(std140, binding = 4) uniform PackOptions { vec4 u_PackOptions[16]; };`
  * `#define` shortcuts mapping sliders to specific array indices:
    * Slots 0..3: `OPT_SUNLIGHT_INTENSITY`, `OPT_AMBIENT_INTENSITY`, `OPT_EMISSIVE_STRENGTH`, `OPT_HANDHELD_DYNAMIC_LIGHT`.
    * Slots 4..7: `OPT_CLOUD_MODE`, `OPT_CLOUD_COVERAGE`, `OPT_GODRAYS_STRENGTH`, `OPT_ATMOS_HAZINESS`.
    * Slots 8..11: `OPT_WATER_WAVE_HEIGHT`, `OPT_WATER_CLARITY`, `OPT_WATER_CAUSTICS`, `OPT_RAIN_PUDDLES`.
    * Slots 12..15: `OPT_SUBSURFACE_SCATTERING`, `OPT_NORMAL_BUMP_STRENGTH`, `OPT_WIND_SPEED`, `OPT_SHADOW_SOFTNESS`.

---

### 7.5 `common/materials.glsl`
* **Role:** Semantic block/fluid classification and PBR material synthesis.
* **Key Components:**
  * Semantic material constants: `MAT_SOLID` (0) through `MAT_ORE` (15).
  * `packMetallicAndMaterial(metallic, materialId)` / `unpackMetallicAndMaterial(...)`: Stores metallic $[0, 1]$ and material ID $[0, 31]$ in Attachment 1 alpha channel.
  * `evaluateBlockMaterial(materialId, linearAlbedo, labPbrSpec, lightUV)`:
    * Evaluates roughness, metallic, emissive, subsurface, and porosity based on the decoded material ID.
    * Incorporates micro-texture luminance variation to prevent flat plastic appearances.
    * Overrides parameters with LabPBR 1.3 `_s` specular map if present.

---

### 7.6 `common/pbr_brdf.glsl`
* **Role:** Physically-based lighting evaluation and BRDF math.
* **Key Components:**
  * Microfacet specular functions:
    * Normal Distribution: $D_{GGX}(NoH, \alpha)$.
    * Visibility / Masking: $V_{SmithGGXCorrelated}(NoV, NoL, \alpha)$.
    * Fresnel: $F_{Schlick}(LoH, f0)$ and $F_{SchlickRoughness}(NoV, f0, \text{roughness})$.
    * Diffuse: $Fd_{Burley}(NoV, NoL, LoH, \text{roughness})$.
  * `evaluateDirectPBR(...)`: Evaluates combined diffuse and specular direct lighting for directional/point lights, including forward subsurface scattering for foliage, leaves, and ice.
  * `gtaoMultiBounce(visibility, albedo)`: Multi-bounce colored ambient interreflection.

---

### 7.7 `common/shadows.glsl`
* **Role:** Cascaded shadow map sampling, filtering, and blending.
* **Key Components:**
  * `getCascadeViewProj(cascadeIdx)`: Returns light View-Projection matrix for cascade 0, 1, or 2.
  * `sampleShadowBilinear(...)`: Performs manual 4-tap hardware bilinear depth comparison on `sampler2DArray`.
  * `sampleSingleCascadePCF(...)`: 9-tap Vogel-disk PCF with slope-scale depth bias and normal offset bias.
  * `evaluateCascadedShadow(...)`: Selects cascade based on view distance (`u_CascadeSplits`), smooths seams across cascade transitions (15% overlap zone), and fades out at maximum shadow distance.
  * `sampleVolumetricShadowTap(...)`: Fast single-tap shadow lookup for volumetric raymarching.

---

### 7.8 `common/atmosphere.glsl`
* **Role:** Procedural sky, clouds, and aerial perspective haze.
* **Key Components:**
  * `phaseHenyeyGreenstein(cosTheta, g)`: Single-parameter scattering phase function.
  * `evaluateSkyClouds(...)`: 2D cirrus layer plus 5-step volumetric FBM cumulus cloud slab with self-shadowing and silver lining.
  * `evaluateAtmosphericHazeSky(V, haziness)`: Disk-free Rayleigh + Mie scattering gradient for aerial perspective on terrain (prevents sun/moon disks bleeding through solid blocks).
  * `evaluatePhysicalSky(...)`: Complete sky background with Rayleigh gradient, solar/lunar disks, limb darkening, procedural moon craters, stars, Milky Way, and cloud compositing. Supports Nether smoke and End nebula environments.

---

### 7.9 `common/post_tonemap.glsl`
* **Role:** Color spaces, tonemapping operators, and grading.
* **Key Components:**
  * `srgbToLinear()` and `linearToSrgb()` transfer functions.
  * Tonemapping curves:
    * `tonemapAgX(color)`: Blender 4.0 / Troy Sobotka AgX tonemapper with punchy look.
    * `tonemapACES(color)`: Stephen Hill fitted ACES RRT + ODT curve.
    * `tonemapKhronosNeutral(color)`: Khronos PBR neutral tonemapper.
  * `applySelectedTonemap(hdrLinear, operatorId)`: Dispatches active operator (`0=AgX`, `1=ACES`, `2=Neutral`).
  * `applyColorGrading(linColor, saturation)`: Adjusts saturation in linear space.

---

### 7.10 `gbuffer/terrain_gbuffer.frag`
* **Stage:** `terrain` (Opaque Terrain G-Buffer MRT).
* **Inputs:**
  * Samplers: `tex_diffuse` (0), `tex_light` (1), `tex_normal` (2), `tex_specular` (3).
  * Vertex Data: `NvidiumVertexData` (`v_uv`, `v_color`, `v_lightUV`).
* **Execution Flow:**
  1. Decodes terrain quad vertex positions and exact face normals analytically from `terrainData[idx]` (`cross(pos1 - pos0, pos2 - pos0)`). (CRITICAL: Do NOT compute geometric normals via screen-space derivatives `dFdxFine(camRelPos)` across block edges—doing so evaluates across depth boundaries and causes normal explosion, producing a thin black outline on every block).
  2. Evaluates alpha cutoff; discards cutout transparent pixels.
  3. Separates vertex color into biome tint and smooth ambient occlusion.
  4. Decodes 5-bit block material ID from vertex metadata (`decodeVertexMaterial`).
  5. Evaluates PBR material parameters (roughness, metallic, emissive, subsurface) using `evaluateBlockMaterial`.
  6. Applies LabPBR normal tangent-space bump mapping if texture is present.
  7. Writes G-Buffer MRT:
     * Attachment 0: Linear Albedo (RGB) + Roughness (A).
     * Attachment 1: World Normal (RGB) + Packed Metallic/MaterialId (A).
     * Attachment 2: Block Light (R) + Sky Light (G) + Emissive (B) + Smooth AO (A).

---

### 7.11 `shadow/shadow.frag`
* **Stage:** `shadow` (Cascaded Shadow Map Alpha Cutoff).
* **Inputs:**
  * Samplers: `tex_diffuse` (0).
  * Vertex Data: `NvidiumVertexData` (`v_uv`).
* **Execution Flow:**
  1. Evaluates vertex alpha cutoff (`getVertexAlphaCutoff`).
  2. Samples diffuse alpha; calls `discard` if cutoff test fails. Depth is written automatically.

---

### 7.12 `deferred/deferred_lighting.frag`
* **Stage:** `deferred` (Deferred PBR Lighting & Atmosphere).
* **Inputs:**
  * Fullscreen UV: `v_TexCoord`.
  * Samplers: `gAlbedoRoughness` (0), `gNormalMetallic` (1), `gLightEmission` (2), `gDepth` (3), `shadowMapArray` (4).
* **Execution Flow:**
  1. Tests depth: if fragment is sky (`depth >= 0.99999`), evaluates physical sky and exits.
  2. Reconstructs camera-relative position from depth buffer.
  3. Unpacks G-Buffer attachments (albedo, roughness, normal, metallic, materialId, block/sky light, emissive, vertex AO).
  4. Simulates dynamic rain puddles and raindrop ripple normals during wet weather.
  5. Computes 4-direction GTAO horizon ambient occlusion with multi-bounce color bleeding.
  6. Samples 3-cascade sun/moon shadows with PCF filtering.
  7. Evaluates direct Cook-Torrance GGX PBR lighting from sun and moon.
  8. Evaluates dynamic handheld light with inverse-square range falloff.
  9. Evaluates hemispherical sky ambient irradiance + indirect ground bounce.
  10. Evaluates non-linear warm blocklight (torches, lanterns).
  11. Evaluates environment specular reflections (IBL) and Screen-Space Reflections (SSR) for smooth surfaces and puddles.
  12. Adds emissive glow and blends distance aerial perspective atmospheric haze.
  13. Outputs linear HDR color to `outHdrColor`.

---

### 7.13 `deferred/volumetric_fog.frag`
* **Stage:** `fog` (Volumetric Fog & Light Shafts).
* **Inputs:**
  * Fullscreen UV: `v_TexCoord`.
  * Samplers: `gDepth` (0), `shadowMapArray` (1).
* **Execution Flow:**
  1. Checks if volumetric fog and godrays are enabled.
  2. Unprojects ray endpoint; clamps march distance to 128 meters.
  3. Raymarches 16 uniform steps with temporal IGN jittering along the view vector.
  4. At each step, samples shadow visibility via `sampleVolumetricShadowTap`.
  5. Evaluates Henyey-Greenstein forward phase function toward the active light source.
  6. Computes exponential height fog falloff based on world Y coordinate.
  7. Integrates in-scattered light and Beer-Lambert transmittance.
  8. Outputs `vec4(inscatter.rgb, transmittance)` to Fog FBO.

---

### 7.14 `water/water_surface.frag`
* **Stage:** `translucent` (Translucent Terrain & Water Surface).
* **Inputs:**
  * Samplers: `tex_diffuse` (0), `tex_light` (1), `tex_normal` (2), `tex_specular` (3), `tex_scene_color` (4), `tex_scene_depth` (5), `tex_shadow_map` (6).
  * Vertex Data: `NvidiumVertexData` (`v_uv`, `v_color`, `v_lightUV`).
* **Execution Flow:**
  * Computes exact geometry face normals analytically from `terrainData[idx]` (`cross(pos1 - pos0, pos2 - pos0)`), avoiding screen-space `dFdxFine` edge explosion.
  * Checks material ID from vertex metadata:
  * **Path A: Water (`MAT_WATER`)**:
    1. Computes organic wave normals using domain-warped FBM Gerstner waves.
    2. Computes refracted screen UV with depth rejection to prevent foreground object bleeding.
    3. Calculates optical water depth: `opaqueDist - surfaceDist`.
    4. Evaluates Beer-Lambert wavelength absorption (`exp(-sigma * waterDepth)`) and deep water ambient in-scattering.
    5. Traces Screen-Space Reflections (SSR) along reflected ray $R$, falling back to physical sky reflection.
    6. Blends refraction and reflection using Schlick Fresnel ($F_0 = 0.02$).
    7. Adds direct sun/moon Cook-Torrance GGX specular highlights and underwater seabed caustics.
    8. Adds shoreline contact foam and wave crest foam.
    9. Applies aerial perspective haze and tonemaps output into Minecraft main FBO.
  * **Path B: Dielectric Translucents (Stained Glass, Ice, Slime, Honey, Portal)**:
    1. Evaluates background refraction with roughness-based mip blur.
    2. Filters light through tinted albedo and blends Fresnel reflections.
    3. Adds portal emissive boosts and tonemaps output into Minecraft main FBO.

---

### 7.15 `post/tonemap_bloom.frag`
* **Stage:** `post` (Post-Processing, Bloom & Tonemapping).
* **Inputs:**
  * Fullscreen UV: `v_TexCoord`.
  * Samplers: `hdrSceneTex` (0), `volumetricFogTex` (1), `gLightEmission` (2), `gDepth` (3), `prevHistoryTex` (4), `gNormalMetallic` (5).
* **Execution Flow:**
  1. Composites HDR scene color with volumetric fog: `hdr * fog.a + fog.rgb`.
  2. Samples 5-octave tent-filter bloom from the hardware mip pyramid of `hdrSceneTex` with a soft threshold.
  3. If Temporal Anti-Aliasing (TAA) is enabled:
     * Reprojects pixel to previous frame's UV using `u_PrevViewProjMatrix` and `u_CameraDelta`.
     * Samples previous frame from `prevHistoryTex`.
     * Clamps previous sample within current 5-tap neighborhood YCoCg color bounding box.
     * Blends current and history samples based on motion velocity.
  4. Applies exposure scaling and selected tonemapping operator (AgX / ACES / Neutral).
  5. Converts to sRGB gamma and writes to history FBO (blitted to Minecraft `mainRenderTarget`).

---

### 7.16 `post/final_composite.frag`
* **Stage:** `final` (Final Display Composite).
* **Inputs:**
  * Fullscreen UV: `v_TexCoord`.
  * Samplers: `sceneColorTex` (0), `sceneDepthTex` (1), `gNormalMetallic` (2), `gLightEmission` (3).
* **Execution Flow:**
  1. Outputs clean scene color directly to the display framebuffer (or applies artifact-free sharpening without depth-gradient silhouette darkening).
