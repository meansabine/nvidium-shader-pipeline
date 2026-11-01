#version 460 core
#extension GL_NV_fragment_shader_barycentric : require

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


// Samplers available during G-Buffer pass
layout(binding = 0) uniform sampler2D tex_diffuse;   // Block Atlas (RGBA8)
layout(binding = 1) uniform sampler2D tex_light;     // Vanilla Lightmap (block/sky light levels)
layout(binding = 2) uniform sampler2D tex_normals;   // LabPBR Normal Atlas (XYZ normal + height)
layout(binding = 3) uniform sampler2D tex_specular;  // LabPBR Specular Atlas (R=Smoothness, G=Metal, B=Emit)

// Inputs from Nvidium Mesh Shader
layout(location = 0) in vec3 v_WorldPos;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_LightCoord;
layout(location = 3) in vec4 v_VertexColor;
layout(location = 4) flat in uint v_MaterialFlag;

// G-Buffer Multiple Render Targets (MRT)
layout(location = 0) out vec4 outAlbedoRoughness; // RGB: Base Albedo, A: Roughness [0.04, 1.0]
layout(location = 1) out vec4 outNormalMetallic;   // RGB: Encoded Normal (N * 0.5 + 0.5), A: Metalness [0.0, 1.0]
layout(location = 2) out vec4 outLightEmission;    // R: Block Light, G: Sky Light, B: Emission, A: Material Flag

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

// LabPBR 1.3 Normal Decoding (DirectX/OpenGL XY reconstruction)
vec3 decodeLabPBRNormal(vec4 normalSample) {
    vec2 xy = normalSample.rg * 2.0 - 1.0;
    float z = sqrt(max(1.0 - dot(xy, xy), 0.0));
    return normalize(vec3(xy, z));
}

void main() {
    vec4 diffuseSample = texture(tex_diffuse, v_TexCoord) * v_VertexColor;
    if (diffuseSample.a < 0.1) {
        discard;
    }

    // Convert sRGB albedo to linear space for PBR calculations
    vec3 albedo = pow(diffuseSample.rgb, vec3(2.2));

    // Analytical geometric normal and TBN matrix
    vec3 geomNormal = deriveGeometricNormal(v_WorldPos);
    mat3 TBN = constructTBN(v_WorldPos, v_TexCoord, geomNormal);

    // Sample & decode LabPBR 1.3 normal map
    vec4 normalSample = texture(tex_normals, v_TexCoord);
    vec3 tangentNormal = decodeLabPBRNormal(normalSample);
    vec3 worldNormal = normalize(TBN * tangentNormal);

    // Sample & decode LabPBR 1.3 specular map
    vec4 spec = texture(tex_specular, v_TexCoord);
    float roughness = clamp(1.0 - spec.r, 0.04, 1.0);
    // LabPBR 1.3: values >= 230/255 represent hardcoded metalness/F0 presets; < 230 is linear metalness
    float metallic = (spec.g * 255.0 >= 230.0) ? 1.0 : spec.g;
    // LabPBR 1.3: B channel 255 (1.0) is reserved for 0 emission if unmarked
    float emissive = (spec.b < (254.5 / 255.0)) ? spec.b : 0.0;

    // Pack outputs into G-Buffer MRTs
    outAlbedoRoughness = vec4(albedo, roughness);
    outNormalMetallic  = vec4(worldNormal * 0.5 + 0.5, metallic);
    outLightEmission   = vec4(
        clamp(v_LightCoord.x, 0.0, 1.0),
        clamp(v_LightCoord.y, 0.0, 1.0),
        emissive,
        float(v_MaterialFlag) / 255.0
    );
}
