# Nvidium Shader Pipeline (NSP)

> **Dedicated high-performance shader pipeline harness engineered specifically for Nvidium's GPU-driven Turing+ Task and Mesh Shader architecture.**

---

## 1. Executive Summary & Core Philosophy

### Why This Mod Exists
Traditional Minecraft shader loaders like **Iris** and **Oculus** (implementing the OptiFine shaderpack specification) assume a CPU-orchestrated, fixed-attribute rendering pipeline:
* The CPU traverses chunk trees, prepares vertex arrays with standard layout attributes (position, normals, light UVs, entity IDs), and issues standard draw calls (`glDrawElementsIndirect`).
* Iris intercepts these calls, executing vertex shaders (`gbuffers_terrain.vsh`) and fragment shaders (`gbuffers_terrain.fsh`) writing to Multiple Render Targets (`colortex0` through `colortex15`).

**Nvidium fundamentally breaks these assumptions:**
* Nvidium replaces the entire chunk pipeline with **100% GPU-driven Task and Mesh Shaders** (`GL_NV_mesh_shader`).
* Terrain geometry is packed into a minimalist **16-byte format** (`NvidiumCompactChunkVertex` = `uvec4`) stored in raw unified device memory (`GL_NV_shader_buffer_load`).
* Culling is performed entirely on the GPU (Hierarchical Z culling, `GL_NV_representative_fragment_test`, and temporal coherence).
* Vertex interpolation is bypassed in hardware; instead, the fragment shader decodes quad data directly using barycentric coordinates (`GL_NV_fragment_shader_barycentric`).

Because of these architectural incompatibilities, Iris automatically disables Nvidium whenever a shaderpack is active. Attempting to force legacy OptiFine shaderpacks onto Nvidium would destroy the very mesh-shader optimizations that enable Nvidium's 10x rendering throughput.

**The Solution:** The **Nvidium Shader Pipeline (NSP)** provides a native, modern deferred and forward-plus rendering harness built *from the ground up* around NVIDIA's modern GPU capabilities (Turing, Ampere, Ada Lovelace, and Blackwell architectures).

---

## 2. Rendering Pipeline Architecture

The pipeline executes in six distinct, highly optimized stages every frame:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        NSP Pipeline Lifecycle                          │
└────────────────────────────────────────────────────────────────────────┘

  [Stage 1: Shadow Pass]
    Sun/Moon Cascaded Shadow Map (CSM) via Depth-Only Mesh Shaders
    └─► Generates 3 Cascades (Near 16m, Mid 64m, Far 256m) at 2048x2048
         │
  [Stage 2: G-Buffer Pass]
    Nvidium Mesh & Task Shaders rasterize into Multiple Render Targets (MRT)
    ├─► RT0: Albedo (RGB) + Roughness (A)          [GL_RGBA16F]
    ├─► RT1: World Normal (RGB) + Metalness (A)    [GL_RGBA16F]
    ├─► RT2: Light UVs (RG) + Emissive (B)         [GL_RGBA16F]
    └─► Depth: High-precision hardware scene depth [GL_DEPTH_COMPONENT32F]
         │
  [Stage 3: Deferred Lighting Pass]
    Fullscreen Quad reads G-Buffer & Shadow Cascades
    ├─► Cook-Torrance Microfacet GGX Direct Sunlight
    ├─► Percentage-Closer Filtered (PCF) Cascaded Shadows
    ├─► Ambient Skylight & Torchlight Evaluation
    └─► Writes lit scene into HDR Color Target     [GL_RGBA16F]
         │
  [Stage 4: Volumetric Fog & Atmospheric Shafts]
    Raymarches the Sun Cascades through Camera Frustum
    └─► Henyey-Greenstein Mie scattering blended additively onto HDR Color
         │
  [Stage 5: Water & Translucent Pass]
    Translucent geometry with Screen-Space Reflections (SSR)
    ├─► Water surface waves (Gerstner displacement in mesh shader)
    ├─► Screen-space raymarched reflections of lit HDR scene
    └─► Beer-Lambert physical depth-based water absorption
         │
  [Stage 6: Post-Processing & Tone Mapping]
    Final composite presented to Minecraft's Display Target
    ├─► HDR Bloom downsample/upsample blur pyramid
    ├─► ACES / AgX Filmic Tone Mapping curve
    └─► Contrast, Vibrance, and sRGB Gamma conversion
```

---

## 3. Data Formats & GPU Memory Layouts

### 3.1 The Scene Uniform Block (`std140`, binding = 0)
Shared across all vertex, mesh, fragment, and compute shaders via binding point `0`:

```glsl
layout(std140, binding = 0) uniform SceneUniforms {
    mat4  u_ViewMatrix;          // Camera view transform
    mat4  u_ProjMatrix;          // Camera projection transform
    mat4  u_ViewProjMatrix;      // Combined View * Projection
    mat4  u_InvViewProjMatrix;   // Inverse View-Projection (for depth unprojection)
    mat4  u_CascadeViewProj[3];  // Orthographic View-Projection for 3 shadow cascades
    vec4  u_CascadeSplits;       // x=Near (16m), y=Mid (64m), z=Far (256m), w=Unused
    vec4  u_SunDirection;        // Normalized vector pointing towards the sun
    vec4  u_SunColor;            // Sun intensity & spectral color
    vec4  u_AmbientColor;        // Sky ambient color
    vec4  u_CameraPos;           // World-space camera coordinates
    vec2  u_ScreenSize;          // Display width, height in pixels
    vec2  _pad0;
    float u_Time;                // Elapsed game time in seconds
    int   u_FrameIndex;          // Monotonically increasing frame counter
    float u_FogStart;            // Vanilla fog start distance
    float u_FogEnd;              // Vanilla fog end distance
    vec4  u_FogColor;            // Vanilla fog RGBA color
};
```

### 3.2 G-Buffer Framebuffer Specifications

| Target | Internal Format | Format | Type | Channels & Packing |
| :--- | :--- | :--- | :--- | :--- |
| **Color Attachment 0** | `GL_RGBA16F` | `GL_RGBA` | `GL_FLOAT` | `R, G, B`: Base Albedo<br>`A`: Surface Roughness $[0.04, 1.0]$ |
| **Color Attachment 1** | `GL_RGBA16F` | `GL_RGBA` | `GL_FLOAT` | `R, G, B`: Encoded World Normal ($N \cdot 0.5 + 0.5$)<br>`A`: Metalness $[0.0, 1.0]$ |
| **Color Attachment 2** | `GL_RGBA16F` | `GL_RGBA` | `GL_FLOAT` | `R`: Block Light $[0.0, 1.0]$<br>`G`: Sky Light $[0.0, 1.0]$<br>`B`: Emissive Intensity $[0.0, 1.0]$<br>`A`: Material Flag |
| **Depth Attachment** | `GL_DEPTH_COMPONENT32F` | `GL_DEPTH_COMPONENT` | `GL_FLOAT` | 32-bit floating point depth (eliminates Z-fighting at extreme render distances) |

---

## 4. How to Implement Custom Shaders

### 4.1 Surface Normal & Tangent Reconstruction (No Extra Vertex Memory)
Nvidium's `NvidiumCompactChunkVertex` packs each vertex into 16 bytes:
* Position: 16-bit uints for X, Y, Z
* Light: 8-bit block light, 8-bit sky light
* Color: 24-bit RGB tint
* Texture: 16-bit U, 16-bit V

To avoid bloating vertex bandwidth, calculate surface normals analytically:

```glsl
// Calculate Geometric Normal from screen-space world position derivatives
vec3 dX = dFdx(v_WorldPos);
vec3 dY = dFdy(v_WorldPos);
vec3 geomNormal = normalize(cross(dX, dY));

// Construct Tangent/Bitangent frame from texture UV gradients
vec2 dUvX = dFdx(v_Uv);
vec2 dUvY = dFdy(v_Uv);
vec3 tangent = normalize(dX * dUvY.t - dY * dUvX.t);
vec3 bitangent = cross(geomNormal, tangent);
mat3 TBN = mat3(tangent, bitangent, geomNormal);

// Transform LabPBR normal map to world space
vec3 normal = geomNormal;
vec4 normalSample = texture(tex_normals, v_Uv);
if (normalSample.a > 0.0) {
    vec3 tangentNormal = normalSample.xyz * 2.0 - 1.0;
    normal = normalize(TBN * tangentNormal);
}
```

### 4.2 LabPBR Specular & Material Decoding
LabPBR standard resource packs (e.g. Continuance, Stratum, Vanilla PBR) pack material data into the specular atlas (`_s.png`):
* **Red Channel**: Smoothness $\to$ $\text{Roughness} = 1.0 - \text{Smoothness}$
* **Green Channel**: Metallic factor ($0.0 = \text{dielectric}$, $1.0 = \text{metal}$)
* **Blue Channel**: Emissive intensity (torches, redstone, glowstone, lava)
* **Alpha Channel**: Porosity / Subsurface scattering

```glsl
vec4 specSample = texture(tex_specular, v_Uv);
float roughness = clamp(1.0 - specSample.r, 0.04, 1.0);
float metallic  = specSample.g;
float emissive  = specSample.a > 0.0 ? specSample.b : 0.0;
```

### 4.3 Evaluating Cook-Torrance Microfacet GGX Lighting
In `deferred_lighting.frag`:

```glsl
vec3 directSun = evaluatePbrBRDF(
    normal,         // World normal
    viewDir,        // Direction from surface to camera
    sunDir,         // Direction towards sun
    albedo,         // Base color
    roughness,      // GGX roughness
    metallic,       // Metallic factor
    u_SunColor.rgb * 2.5 // Light intensity
);
```

### 4.4 Sampling Cascaded Shadow Maps with PCF
Shadow evaluation compares world-space depth against the orthographic cascade projections:

```glsl
float sampleShadow(vec3 worldPos, float viewDist) {
    // Select appropriate cascade
    int cascade = (viewDist < u_CascadeSplits.x) ? 0 : 
                  (viewDist < u_CascadeSplits.y) ? 1 : 2;

    vec4 shadowClip = u_CascadeViewProj[cascade] * vec4(worldPos, 1.0);
    vec3 shadowCoord = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;

    // 4-tap PCF filter
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

### 4.5 Tone Mapping with ACES Filmic
Avoid harsh blown-out whites and preserve color saturation in bright areas:

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

## 5. Adding a Custom Pass in Java

To implement a new pass (e.g. Depth of Field, Screen-Space Ambient Occlusion, or Motion Blur):

1. **Implement `IShaderPass`**:
```java
package net.nvidium.shaders.pipeline.passes;

public class MyCustomPass implements IShaderPass {
    private boolean enabled = true;
    private ShaderProgram shader;

    @Override
    public String getId() { return "my_custom_pass"; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    @Override
    public void init(PipelineManager pipeline) {
        reload();
    }

    @Override
    public void execute(PipelineManager pipeline, RenderContext context) {
        if (!enabled || shader == null) return;
        
        shader.bind();
        // Bind textures, set uniforms...
        FullscreenQuad.render();
        ShaderProgram.unbind();
    }

    @Override
    public void reload() {
        if (shader != null) shader.destroy();
        shader = new ShaderProgram("MyCustomPass")
            .attachStage(GL20C.GL_VERTEX_SHADER, ResourceLocation.fromNamespaceAndPath("nvidium_shaders", "shaders/common/fullscreen.vert"))
            .attachStage(GL20C.GL_FRAGMENT_SHADER, ResourceLocation.fromNamespaceAndPath("nvidium_shaders", "shaders/post/my_shader.frag"))
            .link();
    }

    @Override
    public void destroy() {
        if (shader != null) shader.destroy();
    }
    
    @Override public void resize(int w, int h) {}
}
```

2. **Register in `PipelineManager.java`**:
```java
registerPass(new MyCustomPass());
```

---

## 6. Integrating with Nvidium Core

When combining this harness with `neo-nvidium-dev`:

1. In `RenderPipeline.java` of `neo-nvidium-dev`:
   * Inside `renderFrame(...)`, replace the default framebuffer clear with `PipelineManager.getGbuffer().bindWrite()`.
   * Bind the normal atlas (`_n.png`) and specular atlas (`_s.png`) in `PrimaryTerrainRasterizer.java` to texture units `2` and `3`.
2. After terrain rasterization finishes:
   * Call `PipelineManager.render(renderContext)`.
   * The pipeline will read the G-Buffer, evaluate deferred lighting, compute atmospheric volumetrics, render water with SSR, apply ACES tone mapping, and present the final HDR image directly to the display.

---

## 7. Performance & System Requirements

* **GPU Architecture**: NVIDIA Turing or newer (GTX 1650/1660, RTX 20/30/40/50 series).
* **OpenGL Version**: OpenGL 4.6 Core Profile with extensions:
  * `GL_NV_mesh_shader`
  * `GL_NV_fragment_shader_barycentric`
  * `GL_NV_shader_buffer_load`
  * `GL_NV_bindless_texture` (optional, for maximum texture streaming throughput)
* **Estimated Frame Time**:
  * Shadow Pass (3 cascades): **~0.3–0.6 ms** (due to GPU mesh-shader culling)
  * G-Buffer Geometry Pass: **~1.2–2.0 ms**
  * Deferred Lighting & GTAO: **~0.8–1.4 ms**
  * Volumetric Fog & SSR: **~0.5–1.0 ms**
  * Post-Processing (Bloom + ACES): **~0.3–0.5 ms**
  * **Total Frame Time**: **~3.5–5.5 ms (180–280+ FPS at 1440p on RTX 3070/4070)**.
