# Nvidium Shader Pipeline (NSP) Technical Specification & Interface Guide

This document is the authoritative technical reference for the Nvidium Shader Pipeline (NSP). It specifies the accessible GPU resources, uniform buffer layouts, texture binding slots, render passes, vertex decoding interfaces, shader pack archive structure, and full reference shader source files.

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

### 6.2 `pack.json` Specification
The `pack.json` file in the archive root defines the pack's name, description, stage path overrides, and configurable slider options:
* **`name`**: Display name of the shader pack.
* **`description`**: Summary shown in the shader selection menu.
* **`author`**: Author / team string.
* **`version`**: Pack semantic version string.
* **`stages`**: Map linking stage IDs (`terrain`, `shadow`, `deferred`, `fog`, `translucent`, `post`, `final`, `fullscreen_vert`) to relative shader file paths.
* **`options`**: Array of customizable options exposed in Nvidium Video Settings. Each option must map to a unique `slot` index (`0..15`), matching `u_PackOptions[slot / 4][slot % 4]` in `common/options.glsl`.

---

## 7. Complete Default Shader Source Files

Below are the exact default shader source files provided by the built-in Nvidium Shader Pack.

### 7.1 `pack.json`
```json
{
  "name": "Nvidium Frontier Default",
  "description": "Physically-based mesh-shader deferred pipeline featuring per-block material shading, 3-cascade sun/moon shadows, Horizon GTAO, volumetric light shafts & clouds, Gerstner water with caustics & SSR, dynamic handheld lighting, rain puddles, TAA, and AgX/ACES tonemapping.",
  "author": "Nvidium Team",
  "version": "2.0.0",
  "stages": {
    "terrain": "gbuffer/terrain_gbuffer.frag",
    "shadow": "shadow/shadow.frag",
    "deferred": "deferred/deferred_lighting.frag",
    "fog": "deferred/volumetric_fog.frag",
    "translucent": "water/water_surface.frag",
    "post": "post/tonemap_bloom.frag",
    "final": "post/final_composite.frag",
    "fullscreen_vert": "common/fullscreen.vert"
  },
  "options": [
    {
      "id": "SUNLIGHT_INTENSITY",
      "name": "Sunlight Intensity",
      "description": "Direct sun and moon directional illuminance multiplier",
      "category": "Lighting",
      "type": "float",
      "default": 1.0,
      "min": 0.2,
      "max": 2.5,
      "step": 0.05,
      "slot": 0
    },
    {
      "id": "AMBIENT_INTENSITY",
      "name": "Sky Ambient Intensity",
      "description": "Hemispherical sky and indirect ground-bounce ambient brightness",
      "category": "Lighting",
      "type": "float",
      "default": 1.0,
      "min": 0.25,
      "max": 2.5,
      "step": 0.05,
      "slot": 1
    },
    {
      "id": "EMISSIVE_STRENGTH",
      "name": "Emissive Block Glow",
      "description": "HDR luminance multiplier for torches, lanterns, lava, glowstone, sculk, and glowing ores",
      "category": "Lighting",
      "type": "float",
      "default": 1.25,
      "min": 0.2,
      "max": 3.5,
      "step": 0.05,
      "slot": 2
    },
    {
      "id": "HANDHELD_DYNAMIC_LIGHT",
      "name": "Handheld Dynamic Light",
      "description": "Casts colored point light from held torches, soul lanterns, lava buckets, and glowing items",
      "category": "Lighting",
      "type": "bool",
      "default": 1.0,
      "slot": 3
    },
    {
      "id": "CLOUD_MODE",
      "name": "Clouds Style",
      "description": "Selects sky cloud rendering mode",
      "category": "Atmosphere",
      "type": "enum",
      "default": 2.0,
      "choices": ["Off", "2D Cirrus", "Volumetric Multi-Layer"],
      "slot": 4
    },
    {
      "id": "CLOUD_COVERAGE",
      "name": "Cloud Coverage",
      "description": "Fraction of sky covered by dynamic clouds (automatically increases during rain)",
      "category": "Atmosphere",
      "type": "float",
      "default": 0.52,
      "min": 0.0,
      "max": 1.0,
      "step": 0.05,
      "slot": 5
    },
    {
      "id": "GODRAYS_STRENGTH",
      "name": "Volumetric Godrays",
      "description": "Intensity of raymarched crepuscular sun/moon light shafts",
      "category": "Atmosphere",
      "type": "float",
      "default": 1.0,
      "min": 0.0,
      "max": 2.5,
      "step": 0.05,
      "slot": 6
    },
    {
      "id": "ATMOS_HAZINESS",
      "name": "Atmospheric Haziness",
      "description": "Controls aerial perspective and Mie forward scattering haze",
      "category": "Atmosphere",
      "type": "float",
      "default": 1.0,
      "min": 0.0,
      "max": 2.5,
      "step": 0.05,
      "slot": 7
    },
    {
      "id": "WATER_WAVE_HEIGHT",
      "name": "Water Wave Amplitude",
      "description": "Gerstner surface wave height and normal perturbation strength",
      "category": "Water & Materials",
      "type": "float",
      "default": 1.0,
      "min": 0.0,
      "max": 2.5,
      "step": 0.05,
      "slot": 8
    },
    {
      "id": "WATER_CLARITY",
      "name": "Water Clarity",
      "description": "Controls underwater Beer-Lambert absorption depth and clarity",
      "category": "Water & Materials",
      "type": "float",
      "default": 1.0,
      "min": 0.2,
      "max": 2.5,
      "step": 0.05,
      "slot": 9
    },
    {
      "id": "WATER_CAUSTICS",
      "name": "Underwater Caustics",
      "description": "Animated refractive light patterns projected onto underwater seabed surfaces",
      "category": "Water & Materials",
      "type": "bool",
      "default": 1.0,
      "slot": 10
    },
    {
      "id": "RAIN_PUDDLES",
      "name": "Rain Wetness & Puddles",
      "description": "Dynamic reflective puddles and porous darkening on outdoor surfaces during rain",
      "category": "Water & Materials",
      "type": "bool",
      "default": 1.0,
      "slot": 11
    },
    {
      "id": "SUBSURFACE_SCATTERING",
      "name": "Subsurface Scattering",
      "description": "Transmitted sunlight wrap and translucency through foliage, leaves, ice, and snow",
      "category": "Water & Materials",
      "type": "bool",
      "default": 1.0,
      "slot": 12
    },
    {
      "id": "NORMAL_BUMP_STRENGTH",
      "name": "Surface Relief / Bump",
      "description": "Strength of LabPBR and micro-surface relief normal mapping",
      "category": "Water & Materials",
      "type": "float",
      "default": 1.0,
      "min": 0.0,
      "max": 2.0,
      "step": 0.05,
      "slot": 13
    },
    {
      "id": "WIND_SPEED",
      "name": "Foliage Wind Speed",
      "description": "Speed and amplitude of waving grass, crops, vines, and leaves",
      "category": "Water & Materials",
      "type": "float",
      "default": 1.0,
      "min": 0.0,
      "max": 2.5,
      "step": 0.05,
      "slot": 14
    },
    {
      "id": "SHADOW_SOFTNESS",
      "name": "Shadow Penumbra Softness",
      "description": "Vogel-disk PCF filter radius for sun and moon shadows",
      "category": "Lighting",
      "type": "float",
      "default": 1.0,
      "min": 0.25,
      "max": 2.5,
      "step": 0.05,
      "slot": 15
    }
  ]
}
```

---

### 7.2 `common/fullscreen.vert`
```glsl
#version 460 core

out vec2 v_TexCoord;

void main() {
    v_TexCoord = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(v_TexCoord * 2.0 - 1.0, 0.0, 1.0);
}
```

---

### 7.3 `common/scene_uniforms.glsl`
```glsl
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
};

const float PI = 3.14159265359;
const float HALF_PI = 1.57079632679;
const float TAU = 6.28318530718;

vec3 reconstructCameraRelativePos(vec2 uv, float depth) {
    vec4 clip = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 relPos = u_InvViewProjMatrix * clip;
    return relPos.xyz / max(relPos.w, 1.0e-7);
}

vec3 reconstructWorldPos(vec2 uv, float depth) {
    return reconstructCameraRelativePos(uv, depth) + u_CameraPos.xyz;
}

vec3 projectCameraRelativeToUVDepth(vec3 camRelPos) {
    vec4 clip = u_ViewProjMatrix * vec4(camRelPos, 1.0);
    if (clip.w <= 1.0e-5) {
        return vec3(-1.0);
    }
    vec3 ndc = clip.xyz / clip.w;
    return ndc * 0.5 + 0.5;
}

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
    float a = 0.5;
    mat2 rot = mat2(0.8, 0.6, -0.6, 0.8);
    for (int i = 0; i < 4; i++) {
        v += a * noise2D(p);
        p = rot * p * 2.05 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

#endif
```

---

### 7.4 `common/options.glsl`
```glsl
#ifndef NVIDIUM_PACK_OPTIONS_GLSL
#define NVIDIUM_PACK_OPTIONS_GLSL

layout(std140, binding = 4) uniform PackOptions {
    vec4 u_PackOptions[16];
};

float getPackOption(int slot) {
    int vecIdx = clamp(slot >> 2, 0, 15);
    int compIdx = slot & 3;
    return u_PackOptions[vecIdx][compIdx];
}

#define OPT_SUNLIGHT_INTENSITY      u_PackOptions[0].x
#define OPT_AMBIENT_INTENSITY       u_PackOptions[0].y
#define OPT_EMISSIVE_STRENGTH       u_PackOptions[0].z
#define OPT_HANDHELD_DYNAMIC_LIGHT  u_PackOptions[0].w

#define OPT_CLOUD_MODE              u_PackOptions[1].x
#define OPT_CLOUD_COVERAGE          u_PackOptions[1].y
#define OPT_GODRAYS_STRENGTH        u_PackOptions[1].z
#define OPT_ATMOS_HAZINESS          u_PackOptions[1].w

#define OPT_WATER_WAVE_HEIGHT       u_PackOptions[2].x
#define OPT_WATER_CLARITY           u_PackOptions[2].y
#define OPT_WATER_CAUSTICS          u_PackOptions[2].z
#define OPT_RAIN_PUDDLES            u_PackOptions[2].w

#define OPT_SUBSURFACE_SCATTERING   u_PackOptions[3].x
#define OPT_NORMAL_BUMP_STRENGTH    u_PackOptions[3].y
#define OPT_WIND_SPEED              u_PackOptions[3].z
#define OPT_SHADOW_SOFTNESS         u_PackOptions[3].w

#endif
```

---

### 7.5 `common/materials.glsl`
```glsl
#ifndef NVIDIUM_MATERIALS_GLSL
#define NVIDIUM_MATERIALS_GLSL

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

struct MaterialProperties {
    float roughness;
    float metallic;
    float emissive;
    float subsurface;
    float porosity;
    uint  materialId;
};

float packMetallicAndMaterial(float metallic, uint materialId) {
    float m = clamp(metallic, 0.0, 0.98);
    return float(materialId & 31u) + m;
}

void unpackMetallicAndMaterial(float packedVal, out float metallic, out uint materialId) {
    float floored = floor(max(0.0, packedVal) + 1.0e-4);
    materialId = uint(clamp(floored, 0.0, 31.0));
    metallic = clamp((packedVal - floored) / 0.98, 0.0, 1.0);
}

MaterialProperties evaluateBlockMaterial(
        uint materialId,
        vec3 linearAlbedo,
        vec4 labPbrSpec,
        vec2 lightUV) {
    MaterialProperties mat;
    mat.materialId = materialId;
    mat.roughness = 0.82;
    mat.metallic = 0.0;
    mat.emissive = 0.0;
    mat.subsurface = 0.0;
    mat.porosity = 0.35;

    float luma = dot(linearAlbedo, vec3(0.2126, 0.7152, 0.0722));
    float maxC = max(linearAlbedo.r, max(linearAlbedo.g, linearAlbedo.b));
    float minC = min(linearAlbedo.r, min(linearAlbedo.g, linearAlbedo.b));
    float chroma = (maxC - minC) / max(maxC, 1.0e-4);

    float detailVar = clamp((luma - 0.25) * 0.18, -0.08, 0.08);

    if (materialId == MAT_WATER) {
        mat.roughness = 0.03;
        mat.metallic = 0.0;
        mat.subsurface = 0.35;
        mat.porosity = 0.0;
    } else if (materialId == MAT_LAVA) {
        mat.roughness = 0.35;
        mat.metallic = 0.0;
        mat.emissive = clamp(0.85 + luma * 1.4, 0.6, 2.5);
        mat.porosity = 0.0;
    } else if (materialId == MAT_FOLIAGE_WAVING) {
        mat.roughness = clamp(0.68 - detailVar, 0.45, 0.85);
        mat.metallic = 0.0;
        mat.subsurface = 0.78;
        mat.porosity = 0.25;
    } else if (materialId == MAT_LEAVES) {
        mat.roughness = clamp(0.58 - detailVar, 0.38, 0.78);
        mat.metallic = 0.0;
        mat.subsurface = 0.65;
        mat.porosity = 0.20;
    } else if (materialId == MAT_GLASS) {
        mat.roughness = 0.05;
        mat.metallic = 0.0;
        mat.subsurface = 0.10;
        mat.porosity = 0.0;
    } else if (materialId == MAT_ICE) {
        mat.roughness = clamp(0.12 - detailVar * 0.5, 0.05, 0.24);
        mat.metallic = 0.0;
        mat.subsurface = 0.55;
        mat.porosity = 0.05;
    } else if (materialId == MAT_EMISSIVE) {
        mat.roughness = 0.35;
        mat.metallic = 0.0;
        float glowMask = smoothstep(0.08, 0.55, luma + chroma * 0.35);
        mat.emissive = mix(0.22, 1.65, glowMask);
        mat.porosity = 0.10;
    } else if (materialId == MAT_METAL) {
        mat.roughness = clamp(0.24 - detailVar * 0.8, 0.10, 0.42);
        mat.metallic = 0.88;
        mat.porosity = 0.02;
    } else if (materialId == MAT_GEM) {
        mat.roughness = clamp(0.11 - detailVar * 0.5, 0.04, 0.22);
        mat.metallic = 0.18;
        mat.subsurface = 0.32;
        mat.porosity = 0.0;
        if (chroma > 0.45 && luma > 0.25) {
            mat.emissive = 0.12;
        }
    } else if (materialId == MAT_POLISHED) {
        mat.roughness = clamp(0.22 - detailVar * 0.7, 0.09, 0.38);
        mat.metallic = 0.04;
        mat.porosity = 0.12;
    } else if (materialId == MAT_WET) {
        mat.roughness = clamp(0.28 - detailVar * 0.6, 0.12, 0.45);
        mat.metallic = 0.0;
        mat.porosity = 0.75;
    } else if (materialId == MAT_SAND_SNOW) {
        mat.roughness = clamp(0.76 - detailVar * 0.5, 0.55, 0.90);
        mat.metallic = 0.0;
        mat.subsurface = 0.42;
        mat.porosity = 0.65;
    } else if (materialId == MAT_PORTAL) {
        mat.roughness = 0.08;
        mat.metallic = 0.25;
        mat.emissive = 1.60;
        mat.porosity = 0.0;
    } else if (materialId == MAT_SLIME_HONEY) {
        mat.roughness = 0.14;
        mat.metallic = 0.0;
        mat.subsurface = 0.72;
        mat.porosity = 0.0;
    } else if (materialId == MAT_ORE) {
        float veinMask = smoothstep(0.22, 0.48, chroma + max(0.0, luma - 0.32));
        mat.roughness = mix(0.80, 0.18, veinMask);
        mat.metallic = mix(0.0, 0.75, veinMask);
        mat.emissive = veinMask * 0.28;
        mat.porosity = mix(0.40, 0.02, veinMask);
    } else {
        mat.roughness = clamp(0.80 - detailVar, 0.48, 0.94);
        mat.metallic = 0.0;
        mat.porosity = 0.45;
    }

    if (dot(labPbrSpec, labPbrSpec) > 0.0005) {
        float perceptualSmoothness = labPbrSpec.r;
        mat.roughness = clamp(pow(1.0 - perceptualSmoothness, 2.0), 0.02, 1.0);
        if (labPbrSpec.g * 255.0 >= 229.5) {
            mat.metallic = 1.0;
        } else {
            mat.metallic = clamp(labPbrSpec.g, 0.0, 0.95);
        }
        if (labPbrSpec.b * 255.0 <= 64.0) {
            mat.porosity = (labPbrSpec.b * 255.0) / 64.0;
        } else {
            mat.subsurface = ((labPbrSpec.b * 255.0) - 65.0) / 190.0;
        }
        if (labPbrSpec.a < (254.5 / 255.0) && labPbrSpec.a > 0.004) {
            mat.emissive = max(mat.emissive, labPbrSpec.a * 2.0);
        }
    }

    return mat;
}

#endif
```

---

### 7.6 `common/pbr_brdf.glsl`
```glsl
#ifndef NVIDIUM_PBR_BRDF_GLSL
#define NVIDIUM_PBR_BRDF_GLSL

float D_GGX(float NoH, float alphaRoughness) {
    float a2 = alphaRoughness * alphaRoughness;
    float d = (NoH * a2 - NoH) * NoH + 1.0;
    return a2 / max(PI * d * d, 1.0e-6);
}

float V_SmithGGXCorrelated(float NoV, float NoL, float alphaRoughness) {
    float a2 = alphaRoughness * alphaRoughness;
    float ggxV = NoL * sqrt(NoV * NoV * (1.0 - a2) + a2);
    float ggxL = NoV * sqrt(NoL * NoL * (1.0 - a2) + a2);
    return 0.5 / max(ggxV + ggxL, 1.0e-5);
}

vec3 F_Schlick(float u, vec3 f0) {
    float f = pow(clamp(1.0 - u, 0.0, 1.0), 5.0);
    return f0 + (1.0 - f0) * f;
}

vec3 F_SchlickRoughness(float cosTheta, vec3 f0, float roughness) {
    return f0 + (max(vec3(1.0 - roughness), f0) - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

float Fd_Burley(float NoV, float NoL, float LoH, float roughness) {
    float f90 = 0.5 + 2.0 * roughness * LoH * LoH;
    float lightScatter = 1.0 + (f90 - 1.0) * pow(1.0 - NoL, 5.0);
    float viewScatter  = 1.0 + (f90 - 1.0) * pow(1.0 - NoV, 5.0);
    return (lightScatter * viewScatter) * (1.0 / PI);
}

vec3 evaluateDirectPBR(
        vec3 N,
        vec3 V,
        vec3 L,
        vec3 albedo,
        float roughness,
        float metallic,
        float subsurface,
        vec3 lightRadiance) {
    vec3 H = normalize(V + L);
    float NoV = abs(dot(N, V)) + 1.0e-5;
    float NoL = clamp(dot(N, L), 0.0, 1.0);
    float NoH = clamp(dot(N, H), 0.0, 1.0);
    float LoH = clamp(dot(L, H), 0.0, 1.0);

    float alphaRoughness = max(0.045, roughness * roughness);
    vec3 f0 = mix(vec3(0.04), albedo, metallic);

    float D = D_GGX(NoH, alphaRoughness);
    float Vis = V_SmithGGXCorrelated(NoV, NoL, alphaRoughness);
    vec3 F = F_Schlick(LoH, f0);

    vec3 specular = (D * Vis) * F;

    vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);
    vec3 diffuse = kD * albedo * Fd_Burley(NoV, NoL, LoH, roughness);

    vec3 direct = (diffuse + specular) * NoL * lightRadiance;

    if (subsurface > 0.01) {
        float wrapNoL = clamp((dot(N, L) + 0.45) / 1.45, 0.0, 1.0);
        float VoL = clamp(dot(V, -L), 0.0, 1.0);
        float forwardTrans = pow(VoL, 3.5) * 0.65 + wrapNoL * 0.45;
        vec3 sssTint = pow(max(albedo, vec3(0.02)), vec3(0.75));
        direct += sssTint * forwardTrans * subsurface * (1.0 - metallic) * lightRadiance * 0.48;
    }

    return direct;
}

vec3 gtaoMultiBounce(float visibility, vec3 albedo) {
    vec3 a =  2.0404 * albedo - 0.3324;
    vec3 b = -4.7951 * albedo + 0.6417;
    vec3 c =  2.7552 * albedo + 0.6903;
    return max(vec3(visibility), ((visibility * a + b) * visibility + c) * visibility);
}

#endif
```

---

### 7.7 `common/shadows.glsl`
```glsl
#ifndef NVIDIUM_SHADOWS_GLSL
#define NVIDIUM_SHADOWS_GLSL

const vec2 PCF_DISK_9[9] = vec2[](
    vec2( 0.0000,  0.0000),
    vec2(-0.7071, -0.7071),
    vec2( 0.7071, -0.7071),
    vec2(-0.7071,  0.7071),
    vec2( 0.7071,  0.7071),
    vec2( 0.0000, -1.0000),
    vec2( 0.0000,  1.0000),
    vec2(-1.0000,  0.0000),
    vec2( 1.0000,  0.0000)
);

const float PCF_WEIGHTS_9[9] = float[](
    0.20,
    0.09, 0.09, 0.09, 0.09,
    0.11, 0.11, 0.11, 0.11
);

mat4 getCascadeViewProj(int cascadeIdx) {
    if (cascadeIdx == 0) return u_CascadeViewProj0;
    if (cascadeIdx == 1) return u_CascadeViewProj1;
    return u_CascadeViewProj2;
}

float sampleShadowBilinear(sampler2DArray csmArray, vec2 uv, float layer, float refDepth) {
    float res = u_ShadowParams.x;
    float invRes = u_ShadowParams.y;
    vec2 st = uv * res - 0.5;
    vec2 base = (floor(st) + 0.5) * invRes;
    vec2 f = fract(st);

    float d00 = (refDepth <= textureLod(csmArray, vec3(base, layer), 0.0).r) ? 1.0 : 0.0;
    float d10 = (refDepth <= textureLod(csmArray, vec3(base + vec2(invRes, 0.0), layer), 0.0).r) ? 1.0 : 0.0;
    float d01 = (refDepth <= textureLod(csmArray, vec3(base + vec2(0.0, invRes), layer), 0.0).r) ? 1.0 : 0.0;
    float d11 = (refDepth <= textureLod(csmArray, vec3(base + vec2(invRes, invRes), layer), 0.0).r) ? 1.0 : 0.0;

    return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
}

float sampleSingleCascadePCF(
        sampler2DArray csmArray,
        vec3 camRelPos,
        vec3 worldNormal,
        vec3 lightDir,
        int cascadeIdx,
        float filterScale,
        float rotAngle) {
    float normalBiasScale = (cascadeIdx == 0) ? 0.045 : ((cascadeIdx == 1) ? 0.095 : 0.19);
    float NoL = clamp(dot(worldNormal, lightDir), 0.0, 1.0);
    vec3 biasedPos = camRelPos + worldNormal * (normalBiasScale * (1.25 - NoL * 0.65));

    mat4 lightVP = getCascadeViewProj(cascadeIdx);
    vec4 lightClip = lightVP * vec4(biasedPos, 1.0);
    vec3 projCoords = lightClip.xyz / max(lightClip.w, 1.0e-6);

    vec2 uv = projCoords.xy * 0.5 + 0.5;
    float currentDepth = projCoords.z * 0.5 + 0.5;

    if (uv.x <= 0.002 || uv.x >= 0.998 || uv.y <= 0.002 || uv.y >= 0.998 || currentDepth <= 0.0 || currentDepth >= 1.0) {
        return 1.0;
    }

    float slopeBias = max(0.00045 * (1.0 - NoL), 0.00014) * float(cascadeIdx + 1);
    float cascadeRadiusScale = (cascadeIdx == 0) ? 1.35 : ((cascadeIdx == 1) ? 1.10 : 0.85);
    float radiusTexels = cascadeRadiusScale * max(0.25, filterScale);
    vec2 texelSize = vec2(u_ShadowParams.y);
    float refDepth = currentDepth - slopeBias;
    float layer = float(cascadeIdx);

    float shadowSum = 0.0;
    for (int i = 0; i < 9; i++) {
        vec2 offset = PCF_DISK_9[i] * texelSize * radiusTexels;
        shadowSum += sampleShadowBilinear(csmArray, uv + offset, layer, refDepth) * PCF_WEIGHTS_9[i];
    }
    return shadowSum;
}

float evaluateCascadedShadow(
        sampler2DArray csmArray,
        vec3 camRelPos,
        vec3 worldNormal,
        vec3 lightDir,
        float filterScale,
        vec2 pixelCoord,
        out int outCascadeIdx) {
    float shadowFade = u_ShadowLightDir.w;
    if (shadowFade <= 0.001) {
        outCascadeIdx = 2;
        return 1.0;
    }

    float dist = length(camRelPos);
    float maxDist = u_CascadeSplits.w;
    if (dist >= maxDist) {
        outCascadeIdx = 2;
        return 1.0;
    }

    int cascadeIdx = 2;
    if (dist < u_CascadeSplits.x) {
        cascadeIdx = 0;
    } else if (dist < u_CascadeSplits.y) {
        cascadeIdx = 1;
    }
    outCascadeIdx = cascadeIdx;

    float rotAngle = 0.0;
    float shadow = sampleSingleCascadePCF(csmArray, camRelPos, worldNormal, lightDir, cascadeIdx, filterScale, rotAngle);

    float splitEnd = (cascadeIdx == 0) ? u_CascadeSplits.x : ((cascadeIdx == 1) ? u_CascadeSplits.y : u_CascadeSplits.z);
    float blendZone = splitEnd * 0.15;
    if (cascadeIdx < 2 && dist > (splitEnd - blendZone)) {
        float blendFactor = clamp((dist - (splitEnd - blendZone)) / max(blendZone, 0.001), 0.0, 1.0);
        float nextShadow = sampleSingleCascadePCF(csmArray, camRelPos, worldNormal, lightDir, cascadeIdx + 1, filterScale, rotAngle);
        shadow = mix(shadow, nextShadow, blendFactor);
    }

    float farFade = smoothstep(maxDist, maxDist * 0.82, dist);
    shadow = mix(1.0, shadow, farFade * shadowFade);

    return shadow;
}

float sampleVolumetricShadowTap(sampler2DArray csmArray, vec3 camRelPos) {
    float dist = length(camRelPos);
    if (dist >= u_CascadeSplits.w) return 1.0;

    int cascadeIdx = (dist < u_CascadeSplits.x) ? 0 : ((dist < u_CascadeSplits.y) ? 1 : 2);
    mat4 lightVP = getCascadeViewProj(cascadeIdx);
    vec4 lightClip = lightVP * vec4(camRelPos, 1.0);
    vec3 projCoords = lightClip.xyz / max(lightClip.w, 1.0e-6);
    vec2 uv = projCoords.xy * 0.5 + 0.5;
    float currentDepth = projCoords.z * 0.5 + 0.5;
    if (uv.x <= 0.0 || uv.x >= 1.0 || uv.y <= 0.0 || uv.y >= 1.0 || currentDepth <= 0.0 || currentDepth >= 1.0) {
        return 1.0;
    }
    float closestDepth = texture(csmArray, vec3(uv, float(cascadeIdx))).r;
    return (currentDepth - 0.0012 <= closestDepth) ? 1.0 : 0.0;
}

#endif
```

---

### 7.8 `common/atmosphere.glsl`
```glsl
#ifndef NVIDIUM_ATMOSPHERE_GLSL
#define NVIDIUM_ATMOSPHERE_GLSL

float phaseHenyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * pow(max(denom, 1.0e-4), 1.5));
}

vec4 evaluateSkyClouds(vec3 V, vec3 sunDir, vec3 sunCol, vec3 moonDir, vec3 moonCol, vec3 skyAmbient, float cloudMode, float coverageOpt) {
    if (cloudMode < 0.5 || V.y <= 0.015) {
        return vec4(0.0);
    }

    float rain = u_Weather.x;
    float coverage = clamp(mix(coverageOpt, 0.88, rain * 0.75), 0.0, 0.96);
    if (coverage <= 0.01) {
        return vec4(0.0);
    }

    float windTime = u_Time.x * 2.2;
    vec2 windOffset = vec2(windTime, windTime * 0.42);

    float cloudHeight = max(80.0, 210.0 - u_CameraPos.y);
    vec2 planeXZ = u_CameraPos.xz + (V.xz / max(V.y, 0.04)) * cloudHeight;
    float horizonFade = smoothstep(0.015, 0.18, V.y);

    vec2 cirrusUV = (planeXZ + windOffset * 1.4) * 0.0014;
    float cirrusNoise = fbm2D(cirrusUV) * fbm2D(cirrusUV * 2.3 + vec2(4.2, -1.9));
    float cirrusDensity = smoothstep(1.0 - coverage * 0.85, 1.0, cirrusNoise * 1.65) * 0.42;

    if (cloudMode < 1.5) {
        vec3 cloudCol = skyAmbient * 1.15 + sunCol * 0.35 + moonCol * 0.25;
        return vec4(cloudCol, cirrusDensity * horizonFade);
    }

    vec3 lightDir = (u_SunDirection.y >= 0.0) ? sunDir : moonDir;
    vec3 activeLightCol = (u_SunDirection.y >= 0.0) ? (sunCol * u_SunColor.w) : (moonCol * u_MoonColor.w * 1.6);
    float cosAngle = dot(V, lightDir);
    float silverLining = phaseHenyeyGreenstein(cosAngle, 0.62) * 2.8 + phaseHenyeyGreenstein(cosAngle, -0.25) * 0.6;

    float accumAlpha = 0.0;
    vec3 accumColor = vec3(0.0);

    for (int i = 0; i < 5; i++) {
        float fi = (float(i) + 0.5) / 5.0;
        float h = mix(cloudHeight, cloudHeight + 55.0, fi);
        vec2 sampleXZ = u_CameraPos.xz + (V.xz / max(V.y, 0.045)) * h + windOffset;
        vec2 uv = sampleXZ * 0.0022;

        float base = fbm2D(uv);
        float detail = fbm2D(uv * 2.75 - windOffset * 0.003);
        float shape = base * 0.72 + detail * 0.28;

        float verticalProfile = smoothstep(0.0, 0.22, fi) * smoothstep(1.0, 0.45, fi);
        float density = smoothstep(1.0 - coverage * 0.78, 0.96, shape * verticalProfile * 1.35);

        if (density > 0.005) {
            vec2 lightSampleUV = uv + lightDir.xz * 0.065;
            float lightOccluder = fbm2D(lightSampleUV) * verticalProfile;
            float lightTrans = exp(-max(0.0, lightOccluder - (1.0 - coverage * 0.75)) * 4.2);

            float powder = 1.0 - exp(-density * 3.2);
            vec3 directLit = activeLightCol * lightTrans * (0.42 + silverLining * powder * 0.55);
            vec3 ambientLit = mix(skyAmbient * 0.52, skyAmbient * 1.15, fi);
            vec3 stepCol = (ambientLit + directLit) * (1.0 - rain * 0.42);

            float stepAlpha = density * 0.34 * (1.0 - accumAlpha);
            accumColor += stepCol * stepAlpha;
            accumAlpha += stepAlpha;
            if (accumAlpha > 0.96) break;
        }
    }

    float totalAlpha = clamp(accumAlpha + cirrusDensity * (1.0 - accumAlpha), 0.0, 0.98) * horizonFade;
    vec3 finalCloudColor = (accumColor + (skyAmbient * 1.2 + activeLightCol * 0.25) * cirrusDensity * (1.0 - accumAlpha))
            / max(accumAlpha + cirrusDensity * (1.0 - accumAlpha), 1.0e-4);

    return vec4(finalCloudColor, totalAlpha);
}

vec3 evaluateAtmosphericHazeSky(vec3 V, float haziness) {
    int dimId = int(round(u_FogParams.w));
    if (dimId == -1) {
        float h = clamp(V.y * 0.5 + 0.5, 0.0, 1.0);
        return mix(vec3(0.38, 0.07, 0.02), vec3(0.14, 0.02, 0.01), h);
    }
    if (dimId == 1) {
        return mix(vec3(0.08, 0.04, 0.14), vec3(0.02, 0.01, 0.04), clamp(abs(V.y), 0.0, 1.0));
    }

    vec3 sunDir = normalize(u_SunDirection.xyz);
    vec3 moonDir = normalize(u_MoonDirection.xyz);
    float sunsetFactor = u_HorizonColor.w;
    float rain = u_Weather.x;

    float zenithT = pow(clamp(max(V.y, 0.0), 0.0, 1.0), 0.52);
    vec3 skyGrad = mix(u_HorizonColor.rgb, u_SkyColor.rgb, zenithT);

    if (V.y < 0.0) {
        float groundT = clamp(-V.y * 3.5, 0.0, 1.0);
        skyGrad = mix(u_HorizonColor.rgb, u_SkyColor.rgb * 0.22, groundT);
    }

    float cosSun = clamp(dot(V, sunDir), 0.0, 1.0);
    float cosMoon = clamp(dot(V, moonDir), 0.0, 1.0);
    float mieHaze = max(0.2, haziness);

    float broadSunGlow = pow(cosSun, 3.0) * 0.18 * mieHaze * (1.0 + sunsetFactor * 0.8);
    skyGrad += u_SunColor.rgb * u_SunColor.w * broadSunGlow * 0.20 * (1.0 - rain * 0.80);

    float broadMoonGlow = pow(cosMoon, 3.0) * 0.14 * mieHaze;
    skyGrad += u_MoonColor.rgb * u_MoonColor.w * broadMoonGlow * 0.22 * (1.0 - rain * 0.80);

    return max(skyGrad, vec3(0.0));
}

vec3 evaluatePhysicalSky(vec3 V, float cloudMode, float cloudCoverage, float haziness) {
    int dimId = int(round(u_FogParams.w));
    if (dimId == -1) {
        float h = clamp(V.y * 0.5 + 0.5, 0.0, 1.0);
        vec3 base = mix(vec3(0.38, 0.07, 0.02), vec3(0.14, 0.02, 0.01), h);
        float smoke = fbm2D(V.xz * 3.0 + u_Time.x * 0.15);
        return base + vec3(0.18, 0.04, 0.01) * smoke;
    }
    if (dimId == 1) {
        vec3 base = mix(vec3(0.08, 0.04, 0.14), vec3(0.02, 0.01, 0.04), clamp(abs(V.y), 0.0, 1.0));
        float nebula = fbm2D(V.xz / max(abs(V.y) + 0.25, 0.25) * 1.8 + u_Time.x * 0.03);
        base += vec3(0.22, 0.09, 0.35) * smoothstep(0.45, 0.85, nebula) * 0.65;
        vec3 starCell = floor(V * 240.0);
        float starHash = hash13(starCell);
        if (starHash > 0.9965) {
            base += vec3(0.75, 0.85, 1.0) * (starHash - 0.9965) * 450.0;
        }
        return base;
    }

    vec3 sunDir = normalize(u_SunDirection.xyz);
    vec3 moonDir = normalize(u_MoonDirection.xyz);
    float dayFactor = u_SkyColor.w;
    float sunsetFactor = u_HorizonColor.w;
    float nightFactor = 1.0 - dayFactor;
    float rain = u_Weather.x;

    float zenithT = pow(clamp(max(V.y, 0.0), 0.0, 1.0), 0.52);
    vec3 skyGrad = mix(u_HorizonColor.rgb, u_SkyColor.rgb, zenithT);

    if (V.y < 0.0) {
        float groundT = clamp(-V.y * 3.5, 0.0, 1.0);
        skyGrad = mix(u_HorizonColor.rgb, u_SkyColor.rgb * 0.22, groundT);
    }

    float cosSun = dot(V, sunDir);
    float cosMoon = dot(V, moonDir);
    float mieHaze = max(0.2, haziness);

    float sunMie = phaseHenyeyGreenstein(cosSun, 0.78) * 0.45 * mieHaze
                 + pow(clamp(cosSun, 0.0, 1.0), 8.0) * 0.35 * (1.0 + sunsetFactor * 1.4);
    skyGrad += u_SunColor.rgb * u_SunColor.w * sunMie * 0.28 * (1.0 - rain * 0.80);

    float moonMie = phaseHenyeyGreenstein(cosMoon, 0.72) * 0.35 * mieHaze;
    skyGrad += u_MoonColor.rgb * u_MoonColor.w * moonMie * 0.35 * (1.0 - rain * 0.80);

    if (V.y > -0.05 && rain < 0.92) {
        float sunDisk = smoothstep(0.9991, 0.99965, cosSun);
        float sunCorona = pow(clamp(cosSun, 0.0, 1.0), 96.0) * 1.4;
        skyGrad += u_SunColor.rgb * (sunDisk * 18.0 + sunCorona) * (1.0 - rain * 0.9);

        float moonDisk = smoothstep(0.9992, 0.99965, cosMoon);
        if (moonDisk > 0.001) {
            float craters = fbm2D(V.xy * 120.0 + V.z * 80.0);
            vec3 moonSurface = u_MoonColor.rgb * mix(0.65, 1.15, craters) * 6.5;
            skyGrad += moonSurface * moonDisk * (1.0 - rain * 0.9);
        }
    }

    if (nightFactor > 0.05 && V.y > 0.02 && rain < 0.85) {
        float starVis = nightFactor * (1.0 - rain) * smoothstep(0.02, 0.25, V.y);
        vec3 starDir = normalize(V + vec3(u_Time.y * 0.25, 0.0, 0.0));
        vec3 cell = floor(starDir * 290.0);
        float h = hash13(cell);
        if (h > 0.9958) {
            float twinkle = 0.65 + 0.35 * sin(u_Time.x * 3.5 + h * 100.0);
            vec3 starTint = mix(vec3(0.75, 0.86, 1.0), vec3(1.0, 0.88, 0.72), fract(h * 73.1));
            skyGrad += starTint * (h - 0.9958) * 380.0 * starVis * twinkle;
        }
        float galaxyBand = exp(-pow((V.x * 0.7 + V.z * 0.7) * 2.8, 2.0));
        float galaxyDust = fbm2D(V.xz * 4.5);
        skyGrad += vec3(0.03, 0.05, 0.10) * galaxyBand * galaxyDust * starVis;
    }

    vec4 clouds = evaluateSkyClouds(V, sunDir, u_SunColor.rgb, moonDir, u_MoonColor.rgb, skyGrad, cloudMode, cloudCoverage);
    skyGrad = mix(skyGrad, clouds.rgb, clouds.a);

    if (u_Weather.w > 0.01) {
        skyGrad += vec3(0.45, 0.55, 0.85) * u_Weather.w;
    }

    return max(skyGrad, vec3(0.0));
}

#endif
```

---

### 7.9 `common/post_tonemap.glsl`
```glsl
#ifndef NVIDIUM_POST_TONEMAP_GLSL
#define NVIDIUM_POST_TONEMAP_GLSL

vec3 srgbToLinear(vec3 srgb) {
    return pow(max(srgb, vec3(0.0)), vec3(2.2));
}

vec3 linearToSrgb(vec3 lin) {
    return pow(max(lin, vec3(0.0)), vec3(1.0 / 2.2));
}

vec3 agxDefaultContrastApprox(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return + 15.5     * x4 * x2
           - 40.14    * x4 * x
           + 31.96    * x4
           - 6.868    * x2 * x
           + 0.4298   * x2
           + 0.1191   * x
           - 0.00232;
}

vec3 tonemapAgX(vec3 color) {
    const mat3 agxMat = mat3(
        0.842479062253094,  0.0423282422610123, 0.0423756549057051,
        0.0784335999999992, 0.878468636469772,  0.0784336,
        0.0792237451477643, 0.0791661274605434, 0.879142973793104
    );
    const mat3 agxMatInv = mat3(
         1.19687900512017,   -0.0528968517574562, -0.0529716355144438,
        -0.0980208811401368,  1.15190312990417,   -0.0980434501171241,
        -0.0990297440797205, -0.0989611768448433,  1.15107367264116
    );
    const float minEv = -12.47393;
    const float maxEv = 4.026069;

    vec3 val = agxMat * max(color, vec3(1.0e-6));
    val = clamp(log2(val), minEv, maxEv);
    val = (val - minEv) / (maxEv - minEv);
    val = agxDefaultContrastApprox(val);

    float luma = dot(val, vec3(0.2126, 0.7152, 0.0722));
    val = luma + (val - luma) * 1.12;
    val = pow(max(val, vec3(0.0)), vec3(1.08));

    val = agxMatInv * val;
    return clamp(pow(max(val, vec3(0.0)), vec3(2.2)), 0.0, 1.0);
}

vec3 tonemapACES(vec3 color) {
    const mat3 acesInput = mat3(
        0.59719, 0.07600, 0.02840,
        0.35458, 0.90834, 0.13383,
        0.04823, 0.01566, 0.83777
    );
    const mat3 acesOutput = mat3(
         1.60475, -0.10208, -0.00327,
        -0.53108,  1.10813, -0.07276,
        -0.07367, -0.00605,  1.07602
    );
    vec3 v = acesInput * color;
    vec3 a = v * (v + 0.0245786) - 0.000090537;
    vec3 b = v * (0.983729 * v + 0.4329510) + 0.238081;
    return clamp(acesOutput * (a / b), 0.0, 1.0);
}

vec3 tonemapKhronosNeutral(vec3 color) {
    const float startCompression = 0.8 - 0.04;
    const float desaturation = 0.15;

    float x = min(color.r, min(color.g, color.b));
    float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
    color -= offset;

    float peak = max(color.r, max(color.g, color.b));
    if (peak < startCompression) return color;

    const float d = 1.0 - startCompression;
    float newPeak = 1.0 - d * d / (peak + d - startCompression);
    color *= newPeak / peak;

    float g = 1.0 - 1.0 / (desaturation * (peak - newPeak) + 1.0);
    return mix(color, vec3(newPeak), g);
}

vec3 applySelectedTonemap(vec3 hdrLinear, int operatorId) {
    if (operatorId == 0) return tonemapAgX(hdrLinear);
    if (operatorId == 1) return tonemapACES(hdrLinear);
    return tonemapKhronosNeutral(hdrLinear);
}

vec3 applyColorGrading(vec3 linColor, float saturation) {
    float luma = dot(linColor, vec3(0.2126, 0.7152, 0.0722));
    return max(mix(vec3(luma), linColor, saturation), vec3(0.0));
}

#endif
```

---

### 7.10 `gbuffer/terrain_gbuffer.frag`
```glsl
#version 460 core
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require
#extension GL_NV_fragment_shader_barycentric : require

#import <nvidium:occlusion/scene.glsl>
#import <nvidium:terrain/vertex_format.glsl>

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"
#include "../common/materials.glsl"

layout(location = 0) out vec4 outAlbedoRoughness;
layout(location = 1) out vec4 outNormalMetallic;
layout(location = 2) out vec4 outLightEmission;

layout(location = 5) in NvidiumVertexData {
    vec2 v_uv;
    vec3 v_color;
    vec2 v_lightUV;
} nv_in;

layout(binding = 0) uniform sampler2D tex_diffuse;
layout(binding = 1) uniform sampler2D tex_light;
layout(binding = 2) uniform sampler2D tex_normal;
layout(binding = 3) uniform sampler2D tex_specular;

void main() {
    uint quadId = uint(gl_PrimitiveID) >> 4;
    bool triangle0 = ((uint(gl_PrimitiveID) >> 3) & 1u) == 0u;
    uvec3 triIndices = triangle0 ? uvec3(0u, 1u, 2u) : uvec3(2u, 3u, 0u);

    uint idx0 = (quadId << 2) + triIndices.x;
    uint idx1 = (quadId << 2) + triIndices.y;
    uint idx2 = (quadId << 2) + triIndices.z;

    Vertex V0 = terrainData[idx0];
    Vertex V1 = terrainData[idx1];
    Vertex V2 = terrainData[idx2];

    vec2 uv = nv_in.v_uv;
    float lodBias = (((gl_PrimitiveID >> 2) & 1) != 0) ? -8.0 : -0.35;
    vec4 texSample = texture(tex_diffuse, uv, lodBias);

    float alphaCutoff = getVertexAlphaCutoff(uint(gl_PrimitiveID) & 3u);
    if (texSample.a < alphaCutoff) {
        discard;
    }

    vec3 pos0 = decodeVertexPosition(V0);
    vec3 pos1 = decodeVertexPosition(V1);
    vec3 pos2 = decodeVertexPosition(V2);

    vec3 edge1 = pos1 - pos0;
    vec3 edge2 = pos2 - pos0;
    vec3 geomNormal = cross(edge1, edge2);
    float geomLen = length(geomNormal);
    geomNormal = (geomLen > 1.0e-6) ? (geomNormal / geomLen) : vec3(0.0, 1.0, 0.0);
    if (!gl_FrontFacing) {
        geomNormal = -geomNormal;
    }

    vec3 rawVCol = nv_in.v_color;
    float maxChannel = max(rawVCol.r, max(rawVCol.g, rawVCol.b));
    vec3 normalizedTint = (maxChannel > 1.0e-4) ? (rawVCol / maxChannel) : vec3(1.0);
    float vanillaFaceFactor = (geomNormal.y < -0.5) ? 0.5 : ((abs(geomNormal.y) > 0.5) ? 1.0 : ((abs(geomNormal.z) > 0.5) ? 0.6 : 0.8));
    float vertexAO = clamp(maxChannel / max(vanillaFaceFactor, 0.5), 0.15, 1.0);

    vec3 srgbAlbedo = texSample.rgb * normalizedTint;
    vec3 linearAlbedo = pow(max(srgbAlbedo, vec3(0.0)), vec3(2.2));

    vec2 lightUV = nv_in.v_lightUV;
    float blockLight = clamp((lightUV.x - 0.03125) / 0.9375, 0.0, 1.0);
    float skyLight   = clamp((lightUV.y - 0.03125) / 0.9375, 0.0, 1.0);

    uint materialId = decodeVertexMaterial(V0);
    vec4 labSpec = texture(tex_specular, uv, lodBias);
    MaterialProperties mat = evaluateBlockMaterial(materialId, linearAlbedo, labSpec, lightUV);

    vec3 worldNormal = geomNormal;
    float bumpStrength = max(0.0, OPT_NORMAL_BUMP_STRENGTH);
    if (bumpStrength > 0.01) {
        vec4 labNorm = texture(tex_normal, uv, lodBias);
        vec2 labXY = labNorm.rg * 2.0 - 1.0;
        if (dot(labXY, labXY) > 0.001) {
            vec3 dp1 = dFdx(pos0);
            vec3 dp2 = dFdy(pos0);
            vec2 duv1 = dFdx(uv);
            vec2 duv2 = dFdy(uv);
            float rcpDet = 1.0 / max(duv1.x * duv2.y - duv1.y * duv2.x, 1.0e-7);
            vec3 T = normalize((dp1 * duv2.y - dp2 * duv1.y) * rcpDet);
            vec3 B = normalize(cross(geomNormal, T));
            T = cross(B, geomNormal);
            float zSign = labNorm.b * 2.0 - 1.0;
            float zComp = sqrt(max(1.0 - dot(labXY, labXY), 0.001)) * ((zSign >= 0.0) ? 1.0 : -1.0);
            vec3 tangentNormal = normalize(vec3(labXY * bumpStrength, zComp));
            worldNormal = normalize(mat3(T, B, geomNormal) * tangentNormal);
        }
    }

    outAlbedoRoughness = vec4(linearAlbedo, mat.roughness);
    outNormalMetallic = vec4(worldNormal, packMetallicAndMaterial(mat.metallic, mat.materialId));
    outLightEmission = vec4(blockLight, skyLight, mat.emissive, vertexAO);
}
```

---

### 7.11 `shadow/shadow.frag`
```glsl
#version 460 core
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require
#extension GL_NV_fragment_shader_barycentric : require

#import <nvidium:occlusion/scene.glsl>
#import <nvidium:terrain/vertex_format.glsl>

layout(binding = 0) uniform sampler2D tex_diffuse;

layout(location = 5) in NvidiumVertexData {
    vec2 v_uv;
    vec3 v_color;
    vec2 v_lightUV;
} nv_in;

void main() {
    float alphaCutoff = getVertexAlphaCutoff(uint(gl_PrimitiveID) & 3u);
    if (alphaCutoff > 0.001) {
        float alpha = textureLod(tex_diffuse, nv_in.v_uv, 0.0).a;
        if (alpha < alphaCutoff) {
            discard;
        }
    }
}
```

---

### 7.12 `deferred/deferred_lighting.frag`
```glsl
#version 460 core

in vec2 v_TexCoord;
layout(location = 0) out vec4 outHdrColor;

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"
#include "../common/materials.glsl"
#include "../common/pbr_brdf.glsl"
#include "../common/shadows.glsl"
#include "../common/atmosphere.glsl"

layout(binding = 0) uniform sampler2D gAlbedoRoughness;
layout(binding = 1) uniform sampler2D gNormalMetallic;
layout(binding = 2) uniform sampler2D gLightEmission;
layout(binding = 3) uniform sampler2D gDepth;
layout(binding = 4) uniform sampler2DArray shadowMapArray;

float computeHorizonGTAO(vec2 uv, vec3 camRelPos, vec3 worldNormal, float vertexAO) {
    if (u_ShadowParams.w < 0.5) {
        return vertexAO;
    }

    float viewDist = length(camRelPos);
    if (viewDist > 160.0) {
        return vertexAO;
    }

    vec2 texel = u_ScreenSize.zw;
    float radiusPixels = clamp(22.0 / max(viewDist * 0.35, 1.0), 3.0, 18.0);
    float noise = interleavedGradientNoise(gl_FragCoord.xy, 0.0);
    float baseAngle = noise * HALF_PI;

    float occlusion = 0.0;
    const int DIRS = 4;
    const int STEPS = 3;

    for (int d = 0; d < DIRS; d++) {
        float angle = baseAngle + float(d) * (TAU / float(DIRS));
        vec2 dir = vec2(cos(angle), sin(angle));

        for (int s = 1; s <= STEPS; s++) {
            float stepFrac = (float(s) - 0.35 + noise * 0.3) / float(STEPS);
            vec2 sampleUV = uv + dir * (stepFrac * radiusPixels) * texel;
            if (sampleUV.x <= 0.0 || sampleUV.x >= 1.0 || sampleUV.y <= 0.0 || sampleUV.y >= 1.0) {
                continue;
            }

            float sampleDepth = textureLod(gDepth, sampleUV, 0.0).r;
            if (sampleDepth >= 0.9999) continue;

            vec3 sampleRelPos = reconstructCameraRelativePos(sampleUV, sampleDepth);
            vec3 diff = sampleRelPos - camRelPos;
            float dist2 = dot(diff, diff);
            float dist = sqrt(max(dist2, 1.0e-5));

            if (dist < 2.6 && dist > 0.02) {
                vec3 horizonVec = diff / dist;
                float nDotH = clamp(dot(worldNormal, horizonVec) - 0.08, 0.0, 1.0);
                float weight = clamp(1.0 - (dist / 2.6), 0.0, 1.0);
                occlusion += nDotH * weight;
            }
        }
    }

    float ssao = clamp(1.0 - (occlusion / float(DIRS * STEPS)) * 1.45, 0.20, 1.0);
    float distFade = smoothstep(160.0, 96.0, viewDist);
    ssao = mix(1.0, ssao, distFade);

    return clamp(ssao * vertexAO, 0.08, 1.0);
}

float evaluateWaterCaustics(vec3 worldPos, float timeSec) {
    vec2 p1 = worldPos.xz * 0.72 + vec2(timeSec * 0.45, -timeSec * 0.32);
    vec2 p2 = worldPos.xz * 0.95 + vec2(-timeSec * 0.38, timeSec * 0.51);
    float n1 = abs(noise2D(p1 * 2.2) - 0.5);
    float n2 = abs(noise2D(p2 * 2.5) - 0.5);
    float c = clamp(1.0 - (n1 + n2) * 3.4, 0.0, 1.0);
    return pow(c, 2.2) * 1.65;
}

vec4 traceScreenSpaceReflection(vec3 camRelPos, vec3 R, float roughness, vec2 pixelCoord) {
    if (u_ShadowParams.z < 0.5 || roughness > 0.38) {
        return vec4(0.0);
    }

    float jitter = interleavedGradientNoise(pixelCoord, 0.0);
    vec3 rayPos = camRelPos + R * (0.18 + jitter * 0.15);
    vec3 stepVec = R * 0.42;

    for (int i = 0; i < 14; i++) {
        rayPos += stepVec;
        stepVec *= 1.16;

        vec3 uvDepth = projectCameraRelativeToUVDepth(rayPos);
        if (uvDepth.x <= 0.01 || uvDepth.x >= 0.99 || uvDepth.y <= 0.01 || uvDepth.y >= 0.99 || uvDepth.z <= 0.0 || uvDepth.z >= 1.0) {
            break;
        }

        float sceneDepth = textureLod(gDepth, uvDepth.xy, 0.0).r;
        if (sceneDepth >= 0.9999) continue;

        vec3 sceneRelPos = reconstructCameraRelativePos(uvDepth.xy, sceneDepth);
        float depthDiff = length(rayPos) - length(sceneRelPos);

        if (depthDiff > 0.0 && depthDiff < length(stepVec) * 1.85) {
            vec3 hitAlbedo = textureLod(gAlbedoRoughness, uvDepth.xy, 0.0).rgb;
            vec4 hitLight = textureLod(gLightEmission, uvDepth.xy, 0.0);
            vec3 hitColor = hitAlbedo * (u_SkyColor.rgb * hitLight.g * 1.1 + u_BlockLightColor.rgb * hitLight.r * 1.2 + hitAlbedo * hitLight.b * 2.0);
            vec2 edgeDist = smoothstep(vec2(0.0), vec2(0.12), uvDepth.xy) * (1.0 - smoothstep(vec2(0.88), vec2(1.0), uvDepth.xy));
            float confidence = edgeDist.x * edgeDist.y * (1.0 - roughness / 0.38);
            return vec4(hitColor, clamp(confidence, 0.0, 1.0));
        }
    }
    return vec4(0.0);
}

vec3 materialIdDebugColor(uint matId) {
    if (matId == MAT_WATER)          return vec3(0.10, 0.45, 1.00);
    if (matId == MAT_LAVA)           return vec3(1.00, 0.28, 0.02);
    if (matId == MAT_FOLIAGE_WAVING) return vec3(0.25, 0.90, 0.20);
    if (matId == MAT_LEAVES)         return vec3(0.10, 0.55, 0.15);
    if (matId == MAT_GLASS)          return vec3(0.75, 0.95, 1.00);
    if (matId == MAT_ICE)            return vec3(0.45, 0.80, 1.00);
    if (matId == MAT_EMISSIVE)       return vec3(1.00, 0.92, 0.25);
    if (matId == MAT_METAL)          return vec3(0.80, 0.82, 0.88);
    if (matId == MAT_GEM)            return vec3(0.20, 0.95, 0.82);
    if (matId == MAT_POLISHED)       return vec3(0.62, 0.45, 0.78);
    if (matId == MAT_WET)            return vec3(0.38, 0.28, 0.20);
    if (matId == MAT_SAND_SNOW)      return vec3(0.95, 0.88, 0.65);
    if (matId == MAT_PORTAL)         return vec3(0.75, 0.15, 1.00);
    if (matId == MAT_SLIME_HONEY)    return vec3(0.55, 0.95, 0.25);
    if (matId == MAT_ORE)            return vec3(1.00, 0.55, 0.15);
    return vec3(0.45, 0.45, 0.45);
}

void main() {
    float depth = textureLod(gDepth, v_TexCoord, 0.0).r;
    vec3 camRelPos = reconstructCameraRelativePos(v_TexCoord, depth);
    vec3 V = normalize(camRelPos);
    vec3 viewToCam = -V;

    if (depth >= 0.99999) {
        vec3 skyHdr = evaluatePhysicalSky(V, OPT_CLOUD_MODE, OPT_CLOUD_COVERAGE, OPT_ATMOS_HAZINESS);
        outHdrColor = vec4(skyHdr, 1.0);
        return;
    }

    vec3 worldPos = camRelPos + u_CameraPos.xyz;
    float viewDist = length(camRelPos);

    vec4 albedoRoughness = textureLod(gAlbedoRoughness, v_TexCoord, 0.0);
    vec4 normalMetallic  = textureLod(gNormalMetallic, v_TexCoord, 0.0);
    vec4 lightEmission   = textureLod(gLightEmission, v_TexCoord, 0.0);

    if (dot(normalMetallic.xyz, normalMetallic.xyz) < 0.001) {
        outHdrColor = vec4(albedoRoughness.rgb, 1.0);
        return;
    }

    vec3 albedo = albedoRoughness.rgb;
    float roughness = clamp(albedoRoughness.a, 0.03, 1.0);
    vec3 N = normalize(normalMetallic.xyz);

    float metallic;
    uint materialId;
    unpackMetallicAndMaterial(normalMetallic.a, metallic, materialId);

    float blockLight = lightEmission.r;
    float skyLight   = lightEmission.g;
    float emissive   = lightEmission.b;
    float vertexAO   = clamp(lightEmission.a, 0.08, 1.0);

    float subsurface = 0.0;
    if (OPT_SUBSURFACE_SCATTERING > 0.5) {
        if (materialId == MAT_FOLIAGE_WAVING) subsurface = 0.78;
        else if (materialId == MAT_LEAVES)    subsurface = 0.65;
        else if (materialId == MAT_ICE)       subsurface = 0.55;
        else if (materialId == MAT_SAND_SNOW) subsurface = 0.38;
        else if (materialId == MAT_SLIME_HONEY) subsurface = 0.72;
        else if (materialId == MAT_GEM)       subsurface = 0.28;
    }

    float puddleMask = 0.0;
    float wetness = u_Weather.y;
    if (wetness > 0.01 && OPT_RAIN_PUDDLES > 0.5 && N.y > 0.75 && skyLight > 0.70 && materialId != MAT_LAVA && materialId != MAT_EMISSIVE) {
        float puddleNoise = fbm2D(worldPos.xz * 0.32);
        float outdoor = smoothstep(0.70, 0.92, skyLight) * smoothstep(0.75, 0.95, N.y);
        puddleMask = smoothstep(0.54 - wetness * 0.22, 0.68 - wetness * 0.15, puddleNoise) * wetness * outdoor;

        if (puddleMask > 0.01) {
            vec2 dropCell = floor(worldPos.xz * 2.5);
            vec2 dropFrac = fract(worldPos.xz * 2.5) - 0.5;
            float dropPhase = fract(u_Time.x * 1.8 + hash12(dropCell));
            float ringDist = length(dropFrac);
            float ripple = sin((ringDist - dropPhase * 0.45) * 42.0) * exp(-ringDist * 8.0) * (1.0 - dropPhase);
            vec3 rippleNormal = normalize(vec3(dropFrac.x * ripple * 0.18, 1.0, dropFrac.y * ripple * 0.18));

            N = normalize(mix(N, rippleNormal, puddleMask * 0.88));
            roughness = mix(roughness, 0.025, puddleMask * 0.92);
            albedo = mix(albedo, albedo * 0.72, puddleMask * 0.5);
        }
    }

    float combinedAO = computeHorizonGTAO(v_TexCoord, camRelPos, N, vertexAO);
    vec3 multiBounceAO = gtaoMultiBounce(combinedAO, albedo);

    vec3 shadowLightDir = normalize(u_ShadowLightDir.xyz);
    int activeCascadeIdx = 0;
    float shadowFactor = 1.0;

    int dimId = int(round(u_FogParams.w));
    if (dimId == 0 && skyLight > 0.03) {
        shadowFactor = evaluateCascadedShadow(
                shadowMapArray,
                camRelPos,
                N,
                shadowLightDir,
                OPT_SHADOW_SOFTNESS,
                gl_FragCoord.xy,
                activeCascadeIdx
        );
        float caveSkyGate = smoothstep(0.03, 0.35, skyLight);
        shadowFactor *= caveSkyGate;
    }

    int debugMode = int(round(u_DebugParams.x));
    if (debugMode == 1) {
        outHdrColor = vec4(albedo, 1.0);
        return;
    } else if (debugMode == 2) {
        outHdrColor = vec4(N * 0.5 + 0.5, 1.0);
        return;
    } else if (debugMode == 3) {
        outHdrColor = vec4(blockLight, skyLight, emissive, 1.0);
        return;
    } else if (debugMode == 4) {
        vec3 cascTint = (activeCascadeIdx == 0) ? vec3(0.25, 1.0, 0.35)
                      : ((activeCascadeIdx == 1) ? vec3(1.0, 0.85, 0.20) : vec3(1.0, 0.30, 0.35));
        outHdrColor = vec4(albedo * cascTint * (0.25 + 0.75 * shadowFactor), 1.0);
        return;
    } else if (debugMode == 5) {
        outHdrColor = vec4(materialIdDebugColor(materialId), 1.0);
        return;
    } else if (debugMode == 6) {
        outHdrColor = vec4(vec3(combinedAO), 1.0);
        return;
    }

    vec3 sunDir = normalize(u_SunDirection.xyz);
    vec3 moonDir = normalize(u_MoonDirection.xyz);
    float sunIntensity = u_SunColor.w * max(0.1, OPT_SUNLIGHT_INTENSITY);
    float moonIntensity = u_MoonColor.w * max(0.1, OPT_SUNLIGHT_INTENSITY);

    vec3 totalLighting = vec3(0.0);

    if (sunIntensity > 0.001 && sunDir.y > -0.08) {
        float sunShadow = (u_SunDirection.y >= 0.0) ? shadowFactor : 0.0;
        vec3 sunRadiance = u_SunColor.rgb * sunIntensity * sunShadow;
        totalLighting += evaluateDirectPBR(N, viewToCam, sunDir, albedo, roughness, metallic, subsurface, sunRadiance);
    }

    if (moonIntensity > 0.001 && moonDir.y > -0.08) {
        float moonShadow = (u_SunDirection.y < 0.0) ? shadowFactor : 0.0;
        vec3 moonRadiance = u_MoonColor.rgb * moonIntensity * moonShadow;
        totalLighting += evaluateDirectPBR(N, viewToCam, moonDir, albedo, roughness, metallic, subsurface, moonRadiance);
    }

    if (OPT_WATER_CAUSTICS > 0.5 && (u_CameraPos.w > 0.5 && u_CameraPos.w < 1.5)) {
        float caustics = evaluateWaterCaustics(worldPos, u_Time.x);
        totalLighting += albedo * u_SunColor.rgb * caustics * shadowFactor * skyLight * 0.55;
    }

    float heldLightLevel = u_Environment.z;
    if (heldLightLevel > 0.05 && OPT_HANDHELD_DYNAMIC_LIGHT > 0.5) {
        vec3 handOffset = vec3(0.0, -0.15, 0.0);
        vec3 toLight = handOffset - camRelPos;
        float distSq = dot(toLight, toLight);
        float radius = max(5.0, u_HeldLightColor.w);
        if (distSq < radius * radius) {
            float dist = sqrt(max(distSq, 0.04));
            vec3 L_hand = toLight / dist;
            float rangeAtten = pow(clamp(1.0 - pow(dist / radius, 2.0), 0.0, 1.0), 2.0);
            float invSq = 1.0 / (1.0 + 0.18 * dist + 0.08 * distSq);
            float flicker = 0.96 + 0.04 * sin(u_Time.x * 7.5 + sin(u_Time.x * 19.0));
            vec3 handRadiance = u_HeldLightColor.rgb * (heldLightLevel / 15.0) * 3.2 * rangeAtten * invSq * flicker;
            totalLighting += evaluateDirectPBR(N, viewToCam, L_hand, albedo, roughness, metallic, subsurface * 0.5, handRadiance);
        }
    }

    float hemiUp = N.y * 0.5 + 0.5;
    vec3 zenithAmb = u_SkyColor.rgb * 0.95 + u_HorizonColor.rgb * 0.25;
    vec3 groundBounce = mix(vec3(0.04, 0.035, 0.03), u_HorizonColor.rgb * 0.35, u_SkyColor.w);
    vec3 skyAmbientIrradiance = mix(groundBounce, zenithAmb, hemiUp) * (skyLight * skyLight * 0.85 + skyLight * 0.15);

    float minAmbientFloor = mix(0.018, 0.18, u_Environment.w);
    if (dimId == -1) {
        skyAmbientIrradiance = vec3(0.24, 0.09, 0.05) * (0.65 + 0.35 * hemiUp);
    } else if (dimId == 1) {
        skyAmbientIrradiance = vec3(0.14, 0.10, 0.20) * (0.65 + 0.35 * hemiUp);
    }
    skyAmbientIrradiance = max(skyAmbientIrradiance, vec3(minAmbientFloor)) * max(0.1, OPT_AMBIENT_INTENSITY);

    float blockCurve = pow(blockLight, 2.15) * 2.1 + blockLight * 0.22;
    float microCreviceHighlight = 0.85 + 0.15 * clamp(N.y + 0.5, 0.0, 1.0);
    vec3 blockIrradiance = u_BlockLightColor.rgb * u_BlockLightColor.w * blockCurve * microCreviceHighlight;

    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    float NoV = abs(dot(N, viewToCam)) + 1.0e-5;
    vec3 F_env = F_SchlickRoughness(NoV, f0, roughness);
    vec3 kD_env = (vec3(1.0) - F_env) * (1.0 - metallic);

    vec3 diffuseAmbient = kD_env * albedo * (skyAmbientIrradiance + blockIrradiance) * multiBounceAO;
    totalLighting += diffuseAmbient;

    vec3 R = reflect(-viewToCam, N);
    float skyReflMask = clamp(skyLight * skyLight, 0.05, 1.0);
    vec3 envSkyRefl = (roughness < 0.18)
            ? evaluatePhysicalSky(R, 1.0, OPT_CLOUD_COVERAGE, OPT_ATMOS_HAZINESS) * skyReflMask
            : evaluateAtmosphericHazeSky(R, OPT_ATMOS_HAZINESS) * skyReflMask;
    envSkyRefl += u_BlockLightColor.rgb * pow(blockLight, 2.5) * 0.45;

    if (roughness < 0.42 || puddleMask > 0.05 || metallic > 0.3) {
        vec4 ssr = traceScreenSpaceReflection(camRelPos, R, roughness, gl_FragCoord.xy);
        envSkyRefl = mix(envSkyRefl, ssr.rgb, ssr.a);
    }

    float specOcclusion = clamp(pow(combinedAO, 1.5) + metallic * 0.35, 0.1, 1.0);
    float smoothnessFactor = (1.0 - roughness) * (1.0 - roughness);
    totalLighting += envSkyRefl * F_env * smoothnessFactor * specOcclusion * (0.45 + puddleMask * 0.55 + metallic * 0.55);

    if (emissive > 0.001) {
        vec3 emissiveColor = pow(max(albedo, vec3(0.04)), vec3(0.82)) * emissive * (2.6 * OPT_EMISSIVE_STRENGTH);
        totalLighting += emissiveColor;
    }

    float fogStart = max(32.0, u_FogParams.x * 0.85);
    float fogEnd = max(fogStart + 48.0, u_FogParams.y * 1.15);
    float distHaze = smoothstep(fogStart, fogEnd, viewDist);
    float heightHaze = exp(-max(worldPos.y - 58.0, 0.0) * 0.018) * (1.0 - exp(-viewDist * 0.0028 * OPT_ATMOS_HAZINESS));
    float totalHaze = clamp(distHaze * 0.85 + heightHaze * 0.25 * (1.0 + u_Weather.x), 0.0, 0.96);

    vec3 horizonSky = evaluateAtmosphericHazeSky(normalize(vec3(V.x, max(V.y, 0.04), V.z)), OPT_ATMOS_HAZINESS);
    totalLighting = mix(totalLighting, horizonSky, totalHaze);

    outHdrColor = vec4(max(totalLighting, vec3(0.0)), 1.0);
}
```

---

### 7.13 `deferred/volumetric_fog.frag`
```glsl
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

    float rain = u_Weather.x;
    float baseExtinction = mix(0.0065, 0.024, rain) * (0.45 + 0.55 * eyeSkyExposure) * OPT_ATMOS_HAZINESS;

    vec3 accumScattering = vec3(0.0);
    float transmittance = 1.0;

    for (int i = 0; i < VOL_STEPS; i++) {
        float t = (float(i) + jitter) * stepSize;
        if (t >= rayLen) break;

        vec3 stepCamRelPos = rayDir * t;
        vec3 stepWorldPos = stepCamRelPos + u_CameraPos.xyz;

        float shadowVisibility = sampleVolumetricShadowTap(shadowMapArray, stepCamRelPos);
        if (shadowVisibility <= 0.001) {
            transmittance *= exp(-baseExtinction * stepSize * 0.45);
            continue;
        }

        float heightDensity = exp(-max(stepWorldPos.y - 62.0, 0.0) * 0.024);
        float stepExtinction = baseExtinction * (0.35 + 0.65 * heightDensity);

        vec3 stepInscatter = lightColor * (stepExtinction * shadowVisibility * hgPhase);
        float stepTransmittance = exp(-stepExtinction * stepSize);

        accumScattering += stepInscatter * (transmittance * (1.0 - stepTransmittance));
        transmittance *= stepTransmittance;
        if (transmittance < 0.015) break;
    }

    accumScattering *= godraysStrength;
    outVolumetricFog = vec4(accumScattering, clamp(transmittance, 0.0, 1.0));
}
```

---

### 7.14 `water/water_surface.frag`
```glsl
#version 460 core
#extension GL_ARB_shading_language_include : enable
#pragma optionNV(unroll all)
#define UNROLL_LOOP
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : require
#extension GL_NV_shader_buffer_load : require
#extension GL_NV_fragment_shader_barycentric : require

#import <nvidium:occlusion/scene.glsl>
#import <nvidium:terrain/vertex_format.glsl>

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"
#include "../common/materials.glsl"
#include "../common/pbr_brdf.glsl"
#include "../common/shadows.glsl"
#include "../common/atmosphere.glsl"
#include "../common/post_tonemap.glsl"

layout(location = 0) out vec4 outFragColor;

layout(binding = 0) uniform sampler2D tex_diffuse;
layout(binding = 1) uniform sampler2D tex_light;
layout(binding = 2) uniform sampler2D tex_normal;
layout(binding = 3) uniform sampler2D tex_specular;
layout(binding = 4) uniform sampler2D tex_scene_color;
layout(binding = 5) uniform sampler2D tex_scene_depth;
layout(binding = 6) uniform sampler2DArray tex_shadow_map;

layout(location = 5) in NvidiumVertexData {
    vec2 v_uv;
    vec3 v_color;
    vec2 v_lightUV;
} nv_in;

float sampleWaterWaveHeight(vec2 p, float t) {
    vec2 flowA = p * 0.62 + vec2(t * 0.42, -t * 0.29);
    vec2 flowB = vec2(p.x * 0.48 - p.y * 0.52, p.x * 0.52 + p.y * 0.48) + vec2(-t * 0.34, t * 0.46);
    vec2 warp = vec2(noise2D(flowA * 0.85) - 0.5, noise2D(flowB * 0.85) - 0.5) * 0.55;

    float hA = fbm2D(flowA + warp);
    float hB = fbm2D(flowB - warp * 0.8);
    float capillary = (noise2D(p * 2.85 + vec2(t * 0.95, t * 0.72)) - 0.5) * 0.16;
    return (hA * 0.55 + hB * 0.45) + capillary;
}

vec3 computeWaterWaveNormal(vec3 worldPos, vec3 geomNormal, float waveAmpOpt, out float outCrestFoam) {
    float t = u_Time.x;
    vec2 p = worldPos.xz;
    const float eps = 0.14;

    float hC = sampleWaterWaveHeight(p, t);
    float hX = sampleWaterWaveHeight(p + vec2(eps, 0.0), t);
    float hZ = sampleWaterWaveHeight(p + vec2(0.0, eps), t);

    float dHx = ((hX - hC) / eps) * 0.065 * waveAmpOpt;
    float dHz = ((hZ - hC) / eps) * 0.065 * waveAmpOpt;

    outCrestFoam = 0.0;

    if (abs(geomNormal.y) > 0.5) {
        return normalize(vec3(-dHx, 1.0, -dHz));
    }
    return normalize(geomNormal + vec3(-dHx, 0.0, -dHz) * 0.5);
}

float sampleSeabedCaustics(vec3 worldPos, float timeSec) {
    vec2 p1 = worldPos.xz * 0.75 + vec2(timeSec * 0.48, -timeSec * 0.35);
    vec2 p2 = worldPos.xz * 0.98 + vec2(-timeSec * 0.41, timeSec * 0.52);
    float n1 = abs(noise2D(p1 * 2.2) - 0.5);
    float n2 = abs(noise2D(p2 * 2.5) - 0.5);
    float c = clamp(1.0 - (n1 + n2) * 3.4, 0.0, 1.0);
    return pow(c, 2.2) * 1.75;
}

vec4 traceTranslucentSSR(vec3 camRelPos, vec3 R) {
    if (u_ShadowParams.z < 0.5) {
        return vec4(0.0);
    }
    float jitter = interleavedGradientNoise(gl_FragCoord.xy, 0.0);
    vec3 rayPos = camRelPos + R * (0.25 + jitter * 0.20);
    vec3 stepVec = R * 0.55;

    for (int i = 0; i < 12; i++) {
        rayPos += stepVec;
        stepVec *= 1.18;

        vec3 uvDepth = projectCameraRelativeToUVDepth(rayPos);
        if (uvDepth.x <= 0.01 || uvDepth.x >= 0.99 || uvDepth.y <= 0.01 || uvDepth.y >= 0.99 || uvDepth.z <= 0.0 || uvDepth.z >= 1.0) {
            break;
        }
        float sceneDepth = textureLod(tex_scene_depth, uvDepth.xy, 0.0).r;
        if (sceneDepth >= 0.9999) continue;

        vec3 sceneRelPos = reconstructCameraRelativePos(uvDepth.xy, sceneDepth);
        float diff = length(rayPos) - length(sceneRelPos);
        if (diff > 0.0 && diff < length(stepVec) * 1.9) {
            vec3 hitSrgb = textureLod(tex_scene_color, uvDepth.xy, 0.0).rgb;
            vec2 edgeFade = smoothstep(vec2(0.0), vec2(0.12), uvDepth.xy) * (1.0 - smoothstep(vec2(0.88), vec2(1.0), uvDepth.xy));
            return vec4(srgbToLinear(hitSrgb), edgeFade.x * edgeFade.y);
        }
    }
    return vec4(0.0);
}

void main() {
    uint quadId = uint(gl_PrimitiveID) >> 4;
    bool triangle0 = ((uint(gl_PrimitiveID) >> 3) & 1u) == 0u;
    uvec3 triIndices = triangle0 ? uvec3(0u, 1u, 2u) : uvec3(2u, 3u, 0u);

    Vertex V0 = terrainData[(quadId << 2) + triIndices.x];
    Vertex V1 = terrainData[(quadId << 2) + triIndices.y];
    Vertex V2 = terrainData[(quadId << 2) + triIndices.z];

    vec2 uv = nv_in.v_uv;
    vec4 texSample = texture(tex_diffuse, uv, 0.0);
    if (texSample.a < 0.02) {
        discard;
    }

    vec3 pos0 = decodeVertexPosition(V0);
    vec3 pos1 = decodeVertexPosition(V1);
    vec3 pos2 = decodeVertexPosition(V2);
    vec3 geomNormal = cross(pos1 - pos0, pos2 - pos0);
    float geomLen = length(geomNormal);
    geomNormal = (geomLen > 1.0e-6) ? (geomNormal / geomLen) : vec3(0.0, 1.0, 0.0);
    if (!gl_FrontFacing) {
        geomNormal = -geomNormal;
    }

    vec3 rawVCol = nv_in.v_color;
    float maxChannel = max(rawVCol.r, max(rawVCol.g, rawVCol.b));
    vec3 biomeTint = (maxChannel > 1.0e-4) ? (rawVCol / maxChannel) : vec3(1.0);

    vec2 lightUV = nv_in.v_lightUV;
    float blockLight = clamp((lightUV.x - 0.03125) / 0.9375, 0.0, 1.0);
    float skyLight   = clamp((lightUV.y - 0.03125) / 0.9375, 0.0, 1.0);

    vec2 screenUV = gl_FragCoord.xy * u_ScreenSize.zw;
    vec3 waterRelPos = reconstructCameraRelativePos(screenUV, gl_FragCoord.z);
    vec3 worldPos = waterRelPos + u_CameraPos.xyz;
    float surfaceDist = length(waterRelPos);
    vec3 V = normalize(waterRelPos);
    vec3 viewToCam = -V;

    uint materialId = decodeVertexMaterial(V0);
    int toneOp = int(round(u_PostParams.w));
    float exposure = u_PostParams.x;

    if (materialId == MAT_WATER) {
        float crestFoam = 0.0;
        vec3 N = computeWaterWaveNormal(worldPos, geomNormal, OPT_WATER_WAVE_HEIGHT, crestFoam);

        vec2 refrOffset = N.xz * (0.032 * OPT_WATER_WAVE_HEIGHT) / max(1.0 + surfaceDist * 0.08, 1.0);
        vec2 refrUV = clamp(screenUV + refrOffset, vec2(0.002), vec2(0.998));

        float opaqueDepth = textureLod(tex_scene_depth, refrUV, 0.0).r;
        vec3 opaqueRelPos = reconstructCameraRelativePos(refrUV, opaqueDepth);
        float opaqueDist = length(opaqueRelPos);

        if (opaqueDist < surfaceDist) {
            refrUV = screenUV;
            opaqueDepth = textureLod(tex_scene_depth, screenUV, 0.0).r;
            opaqueRelPos = reconstructCameraRelativePos(screenUV, opaqueDepth);
            opaqueDist = length(opaqueRelPos);
        }

        float waterDepth = (opaqueDepth >= 0.9999) ? 48.0 : max(0.0, opaqueDist - surfaceDist);
        vec3 refractedLinear = srgbToLinear(textureLod(tex_scene_color, refrUV, 0.0).rgb) / max(exposure, 0.25);

        int dummyCasc = 0;
        float shadowVis = evaluateCascadedShadow(
                tex_shadow_map,
                waterRelPos,
                geomNormal,
                normalize(u_ShadowLightDir.xyz),
                OPT_SHADOW_SOFTNESS,
                gl_FragCoord.xy,
                dummyCasc
        );

        if (OPT_WATER_CAUSTICS > 0.5 && opaqueDepth < 0.9999 && waterDepth < 28.0) {
            vec3 seabedWorldPos = opaqueRelPos + u_CameraPos.xyz;
            float caustics = sampleSeabedCaustics(seabedWorldPos, u_Time.x);
            float depthFade = exp(-waterDepth * 0.14);
            refractedLinear += refractedLinear * u_SunColor.rgb * caustics * shadowVis * depthFade * 0.75;
        }

        float clarity = max(0.2, OPT_WATER_CLARITY);
        vec3 sigma = vec3(0.38, 0.11, 0.055) / clarity;
        vec3 transmittance = exp(-sigma * waterDepth);

        vec3 waterBiomeTint = mix(vec3(0.04, 0.26, 0.36), srgbToLinear(biomeTint) * 0.42, 0.55);
        vec3 sunDir = normalize(u_SunDirection.xyz);
        vec3 moonDir = normalize(u_MoonDirection.xyz);
        vec3 ambientScatter = waterBiomeTint * (u_SkyColor.rgb * skyLight * 0.85 + u_SunColor.rgb * u_SunColor.w * shadowVis * 0.22 + u_BlockLightColor.rgb * pow(blockLight, 2.0));
        vec3 underwaterBody = mix(ambientScatter, refractedLinear, transmittance);

        float NoV = clamp(dot(N, viewToCam), 0.001, 1.0);
        float fresnel = 0.02 + 0.98 * pow(1.0 - NoV, 5.0);

        vec3 R = reflect(V, N);
        R.y = max(R.y, 0.02);
        vec3 skyRefl = evaluatePhysicalSky(normalize(R), 1.0, OPT_CLOUD_COVERAGE, OPT_ATMOS_HAZINESS) * clamp(skyLight * skyLight, 0.08, 1.0);
        vec4 ssr = traceTranslucentSSR(waterRelPos, R);
        vec3 reflLinear = mix(skyRefl, ssr.rgb / max(exposure, 0.25), ssr.a);

        vec3 H_sun = normalize(viewToCam + sunDir);
        float D_sun = D_GGX(clamp(dot(N, H_sun), 0.0, 1.0), 0.032);
        vec3 sunSpec = u_SunColor.rgb * u_SunColor.w * D_sun * shadowVis * clamp(dot(N, sunDir), 0.0, 1.0) * 0.35;

        vec3 H_moon = normalize(viewToCam + moonDir);
        float D_moon = D_GGX(clamp(dot(N, H_moon), 0.0, 1.0), 0.038);
        vec3 moonSpec = u_MoonColor.rgb * u_MoonColor.w * D_moon * shadowVis * clamp(dot(N, moonDir), 0.0, 1.0) * 0.35;

        vec3 surfaceLinear = mix(underwaterBody, reflLinear, fresnel) + sunSpec + moonSpec;

        float vertDist = abs(opaqueRelPos.y - waterRelPos.y);
        float shoreRaw = clamp(1.0 - waterDepth / 0.72, 0.0, 1.0) * clamp(1.0 - vertDist / 0.45, 0.0, 1.0);
        float foamPattern = fbm2D(worldPos.xz * 3.8 + vec2(u_Time.x * 0.45, -u_Time.x * 0.35));
        float shoreFoam = smoothstep(0.32, 0.78, shoreRaw * (0.65 + 0.65 * foamPattern));
        float totalFoam = clamp(shoreFoam + crestFoam * 0.35 * smoothstep(0.2, 1.0, skyLight), 0.0, 0.92);

        if (totalFoam > 0.01) {
            vec3 foamLit = (u_SkyColor.rgb * skyLight + u_SunColor.rgb * u_SunColor.w * shadowVis * 0.42 + u_BlockLightColor.rgb * blockLight) * 0.85;
            surfaceLinear = mix(surfaceLinear, foamLit, totalFoam);
        }

        float fogStart = max(32.0, u_FogParams.x * 0.85);
        float fogEnd = max(fogStart + 48.0, u_FogParams.y * 1.15);
        float distHaze = smoothstep(fogStart, fogEnd, surfaceDist) * 0.85;
        vec3 horizonSky = evaluateAtmosphericHazeSky(normalize(vec3(V.x, max(V.y, 0.04), V.z)), OPT_ATMOS_HAZINESS);
        surfaceLinear = mix(surfaceLinear, horizonSky, distHaze);

        vec3 tonemapped = applySelectedTonemap(applyColorGrading(surfaceLinear * exposure, u_PostParams.z), toneOp);
        outFragColor = vec4(linearToSrgb(tonemapped), 1.0);
        return;
    }

    vec3 N = geomNormal;
    float roughness = (materialId == MAT_ICE || materialId == MAT_SLIME_HONEY) ? 0.16 : 0.04;
    float mipBlur = (materialId == MAT_ICE) ? 1.6 : ((materialId == MAT_SLIME_HONEY) ? 2.4 : 0.0);

    vec2 refrUV = clamp(screenUV + N.xz * 0.008, vec2(0.002), vec2(0.998));
    vec3 bgLinear = srgbToLinear(textureLod(tex_scene_color, refrUV, mipBlur).rgb) / max(exposure, 0.25);

    vec3 tintLinear = srgbToLinear(texSample.rgb * biomeTint);
    float alpha = clamp(texSample.a, 0.08, 0.96);

    float NoV = clamp(dot(N, viewToCam), 0.001, 1.0);
    float fresnel = 0.04 + 0.96 * pow(1.0 - NoV, 5.0);

    vec3 R = reflect(V, N);
    vec3 skyRefl = evaluatePhysicalSky(normalize(vec3(R.x, max(R.y, 0.02), R.z)), 1.0, OPT_CLOUD_COVERAGE, OPT_ATMOS_HAZINESS) * skyLight;
    vec3 sunDir = normalize(u_SunDirection.xyz);
    vec3 H_sun = normalize(viewToCam + sunDir);
    float spec = D_GGX(clamp(dot(N, H_sun), 0.0, 1.0), max(0.04, roughness * roughness)) * clamp(dot(N, sunDir), 0.0, 1.0);
    vec3 specGlow = u_SunColor.rgb * u_SunColor.w * spec * 0.25;

    vec3 litTint = tintLinear * (u_SkyColor.rgb * skyLight * 0.9 + u_BlockLightColor.rgb * pow(blockLight, 2.0) * 1.3 + vec3(0.12));
    if (materialId == MAT_PORTAL) {
        litTint = tintLinear * 3.2 * OPT_EMISSIVE_STRENGTH;
    }

    vec3 transmitted = bgLinear * mix(vec3(1.0), tintLinear * 1.35, alpha * 0.78);
    vec3 combinedLinear = mix(transmitted, litTint, alpha * 0.55) + (skyRefl * fresnel + specGlow) * (1.0 - roughness * 0.5);

    vec3 tonemapped = applySelectedTonemap(applyColorGrading(combinedLinear * exposure, u_PostParams.z), toneOp);
    outFragColor = vec4(linearToSrgb(tonemapped), 1.0);
}
```

---

### 7.15 `post/tonemap_bloom.frag`
```glsl
#version 460 core

in vec2 v_TexCoord;
layout(location = 0) out vec4 outColor;

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"
#include "../common/post_tonemap.glsl"

layout(binding = 0) uniform sampler2D hdrSceneTex;
layout(binding = 1) uniform sampler2D volumetricFogTex;
layout(binding = 2) uniform sampler2D gLightEmission;
layout(binding = 3) uniform sampler2D gDepth;
layout(binding = 4) uniform sampler2D prevHistoryTex;
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
         c.r * 0.25 + c.g * 0.50 + c.b * 0.25,
         c.r * 0.50             - c.b * 0.50,
        -c.r * 0.25 + c.g * 0.50 - c.b * 0.25
    );
}

vec3 yCoCgToRGB(vec3 c) {
    float y  = c.x;
    float co = c.y;
    float cg = c.z;
    return vec3(
        y + co - cg,
        y + cg,
        y - co - cg
    );
}

void main() {
    vec3 hdrColor = textureLod(hdrSceneTex, v_TexCoord, 0.0).rgb;

    vec4 volFog = textureLod(volumetricFogTex, v_TexCoord, 0.0);
    hdrColor = hdrColor * volFog.a + volFog.rgb;

    float bloomOpt = u_PostParams.y;
    if (bloomOpt > 0.01) {
        vec3 bloom = computeMultiOctaveBloom(v_TexCoord);
        hdrColor += bloom * (0.045 * bloomOpt);
    }

    float exposure = u_PostParams.x;
    float saturation = u_PostParams.z;
    int toneOperator = int(round(u_PostParams.w));

    vec3 gradedLinear = applyColorGrading(hdrColor * exposure, saturation);
    vec3 tonemappedCurrent = applySelectedTonemap(gradedLinear, toneOperator);

    bool taaEnabled = (u_DebugParams.y > 0.5) && (u_CameraDelta.w > 0.5);
    if (!taaEnabled) {
        outColor = vec4(tonemappedCurrent, 1.0);
        return;
    }

    float depth = textureLod(gDepth, v_TexCoord, 0.0).r;
    vec3 camRelPos = reconstructCameraRelativePos(v_TexCoord, depth);

    vec4 prevClip = u_PrevViewProjMatrix * vec4(camRelPos + u_CameraDelta.xyz, 1.0);
    if (prevClip.w <= 1.0e-5) {
        outColor = vec4(tonemappedCurrent, 1.0);
        return;
    }
    vec2 prevUV = (prevClip.xy / prevClip.w) * 0.5 + 0.5;

    if (prevUV.x <= 0.002 || prevUV.x >= 0.998 || prevUV.y <= 0.002 || prevUV.y >= 0.998) {
        outColor = vec4(tonemappedCurrent, 1.0);
        return;
    }

    vec2 texel = u_ScreenSize.zw;
    vec3 c0 = tonemappedCurrent;
    vec3 cN = applySelectedTonemap(applyColorGrading(textureLod(hdrSceneTex, v_TexCoord + vec2( 0.0,  texel.y), 0.0).rgb * exposure, saturation), toneOperator);
    vec3 cS = applySelectedTonemap(applyColorGrading(textureLod(hdrSceneTex, v_TexCoord + vec2( 0.0, -texel.y), 0.0).rgb * exposure, saturation), toneOperator);
    vec3 cE = applySelectedTonemap(applyColorGrading(textureLod(hdrSceneTex, v_TexCoord + vec2( texel.x,  0.0), 0.0).rgb * exposure, saturation), toneOperator);
    vec3 cW = applySelectedTonemap(applyColorGrading(textureLod(hdrSceneTex, v_TexCoord + vec2(-texel.x,  0.0), 0.0).rgb * exposure, saturation), toneOperator);

    vec3 y0 = rgbToYCoCg(c0);
    vec3 yN = rgbToYCoCg(cN);
    vec3 yS = rgbToYCoCg(cS);
    vec3 yE = rgbToYCoCg(cE);
    vec3 yW = rgbToYCoCg(cW);

    vec3 yMin = min(y0, min(min(yN, yS), min(yE, yW)));
    vec3 yMax = max(y0, max(max(yN, yS), max(yE, yW)));

    vec3 yAvg = (y0 + yN + yS + yE + yW) * 0.20;
    vec3 halfBox = (yMax - yMin) * 0.55;
    yMin = yAvg - halfBox;
    yMax = yAvg + halfBox;

    vec3 prevColor = textureLod(prevHistoryTex, prevUV, 0.0).rgb;
    vec3 prevY = rgbToYCoCg(prevColor);

    vec3 clampedY = clamp(prevY, yMin, yMax);
    vec3 clampedHistory = yCoCgToRGB(clampedY);

    float velWeight = clamp(length((v_TexCoord - prevUV) * u_ScreenSize.xy) * 0.12, 0.0, 1.0);
    float taaBlend = mix(clamp(u_DebugParams.w, 0.80, 0.94), 0.55, velWeight);

    vec3 resolved = mix(tonemappedCurrent, clampedHistory, taaBlend);
    outColor = vec4(resolved, 1.0);
}
```

---

### 7.16 `post/final_composite.frag`
```glsl
#version 460 core

in vec2 v_TexCoord;
layout(location = 0) out vec4 outFinalColor;

#include "../common/scene_uniforms.glsl"
#include "../common/options.glsl"

layout(binding = 0) uniform sampler2D sceneColorTex;
layout(binding = 1) uniform sampler2D sceneDepthTex;
layout(binding = 2) uniform sampler2D gNormalMetallic;
layout(binding = 3) uniform sampler2D gLightEmission;

float luma(vec3 rgb) {
    return dot(rgb, vec3(0.299, 0.587, 0.114));
}

vec3 evaluateFxaaAndCas(vec2 uv, float casStrength) {
    vec2 texel = u_ScreenSize.zw;

    vec3 cM = textureLod(sceneColorTex, uv, 0.0).rgb;
    vec3 cN = textureLod(sceneColorTex, uv + vec2( 0.0,  texel.y), 0.0).rgb;
    vec3 cS = textureLod(sceneColorTex, uv + vec2( 0.0, -texel.y), 0.0).rgb;
    vec3 cE = textureLod(sceneColorTex, uv + vec2( texel.x, 0.0), 0.0).rgb;
    vec3 cW = textureLod(sceneColorTex, uv + vec2(-texel.x, 0.0), 0.0).rgb;

    float lM = luma(cM);
    float lN = luma(cN);
    float lS = luma(cS);
    float lE = luma(cE);
    float lW = luma(cW);

    float lMin = min(lM, min(min(lN, lS), min(lE, lW)));
    float lMax = max(lM, max(max(lN, lS), max(lE, lW)));
    float range = lMax - lMin;

    float dM = textureLod(sceneDepthTex, uv, 0.0).r;
    float dN = textureLod(sceneDepthTex, uv + vec2( 0.0,  texel.y), 0.0).r;
    float dS = textureLod(sceneDepthTex, uv + vec2( 0.0, -texel.y), 0.0).r;
    float dE = textureLod(sceneDepthTex, uv + vec2( texel.x, 0.0), 0.0).r;
    float dW = textureLod(sceneDepthTex, uv + vec2(-texel.x, 0.0), 0.0).r;
    float depthDelta = max(max(abs(dM - dN), abs(dM - dS)), max(abs(dM - dE), abs(dM - dW)));

    vec3 nM = textureLod(gNormalMetallic, uv, 0.0).xyz;
    vec3 nN = textureLod(gNormalMetallic, uv + vec2( 0.0,  texel.y), 0.0).xyz;
    vec3 nE = textureLod(gNormalMetallic, uv + vec2( texel.x, 0.0), 0.0).xyz;
    float normalEdge = max(length(nM - nN), length(nM - nE));
    bool isGeometricSilhouette = (depthDelta > 0.0008) || (normalEdge > 0.25);

    vec3 result = cM;

    if (isGeometricSilhouette && range > max(0.0312, lMax * 0.125)) {
        vec3 cNW = textureLod(sceneColorTex, uv + vec2(-texel.x,  texel.y), 0.0).rgb;
        vec3 cNE = textureLod(sceneColorTex, uv + vec2( texel.x,  texel.y), 0.0).rgb;
        vec3 cSW = textureLod(sceneColorTex, uv + vec2(-texel.x, -texel.y), 0.0).rgb;
        vec3 cSE = textureLod(sceneColorTex, uv + vec2( texel.x, -texel.y), 0.0).rgb;

        float lNW = luma(cNW);
        float lNE = luma(cNE);
        float lSW = luma(cSW);
        float lSE = luma(cSE);

        vec2 dir;
        dir.x = -((lNW + lNE) - (lSW + lSE));
        dir.y =  ((lNW + lSW) - (lNE + lSE));

        float dirReduce = max((lN + lS + lE + lW) * 0.03125, 1.0 / 128.0);
        float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
        dir = clamp(dir * rcpDirMin, vec2(-6.0), vec2(6.0)) * texel;

        vec3 rgbA = 0.5 * (
            textureLod(sceneColorTex, uv + dir * (1.0 / 3.0 - 0.5), 0.0).rgb +
            textureLod(sceneColorTex, uv + dir * (2.0 / 3.0 - 0.5), 0.0).rgb
        );
        vec3 rgbB = rgbA * 0.5 + 0.25 * (
            textureLod(sceneColorTex, uv + dir * -0.5, 0.0).rgb +
            textureLod(sceneColorTex, uv + dir *  0.5, 0.0).rgb
        );
        float lB = luma(rgbB);
        result = (lB < lMin || lB > lMax) ? rgbA : rgbB;
    } else if (casStrength > 0.01) {
        vec3 minRGB = min(cM, min(min(cN, cS), min(cE, cW)));
        vec3 maxRGB = max(cM, max(max(cN, cS), max(cE, cW)));
        vec3 amp = clamp(min(minRGB, 1.0 - maxRGB) / max(maxRGB, vec3(1.0e-4)), 0.0, 1.0);
        amp = sqrt(amp);
        float peak = -1.0 / mix(8.0, 5.0, clamp(casStrength, 0.0, 1.0));
        vec3 w = amp * peak;
        vec3 rcpWeight = 1.0 / (1.0 + 4.0 * w);
        result = clamp((cM + (cN + cS + cE + cW) * w) * rcpWeight, 0.0, 1.0);
    }

    return result;
}

void main() {
    vec3 color = evaluateFxaaAndCas(v_TexCoord, u_DebugParams.z);
    outFinalColor = vec4(color, 1.0);
}
```
