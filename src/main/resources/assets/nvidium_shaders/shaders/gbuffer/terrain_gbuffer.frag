#version 460 core

// ============================================================================
// Nvidium Shader Pipeline (NSP) - Phase 1: Opaque Terrain G-Buffer Shader
// Writes PBR material properties and lightmap channels to MRT attachments 0-2.
// ============================================================================

layout(std140, binding = 3) uniform SceneUniforms {
    mat4  u_ViewMatrix;
    mat4  u_ProjMatrix;
    mat4  u_ViewProjMatrix;
    mat4  u_InvViewProjMatrix;
    mat4  u_CascadeViewProj[3];
    vec4  u_CascadeSplits;
    vec4  u_SunDirection;
    vec4  u_SunColor;
    vec4  u_AmbientColor;
    vec4  u_CameraPos;
    vec2  u_ScreenSize;
    vec2  _pad0;
    float u_Time;
    int   u_FrameIndex;
    float u_FogStart;
    float u_FogEnd;
    vec4  u_FogColor;
};

layout(binding = 0) uniform sampler2D u_BlockAtlas;

layout(location = 0) in vec3 v_WorldPos;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec4 v_VertexColor;
layout(location = 3) in vec2 v_LightCoord;
layout(location = 4) in vec3 v_Normal;

layout(location = 0) out vec4 outAlbedoRoughness;
layout(location = 1) out vec4 outNormalMetallic;
layout(location = 2) out vec4 outLightEmission;

float deriveRoughness(vec3 albedo, vec2 uv) {
    float luma = dot(albedo, vec3(0.2126, 0.7152, 0.0722));
    float chroma = max(albedo.r, max(albedo.g, albedo.b)) - min(albedo.r, min(albedo.g, albedo.b));
    float baseRoughness = mix(0.88, 0.52, clamp(luma * 0.7 + chroma * 0.3, 0.0, 1.0));
    return clamp(baseRoughness, 0.04, 1.0);
}

float deriveMetallic(vec3 albedo) {
    float maxC = max(albedo.r, max(albedo.g, albedo.b));
    float minC = min(albedo.r, min(albedo.g, albedo.b));
    float saturation = (maxC > 0.001) ? ((maxC - minC) / maxC) : 0.0;
    float brightness = dot(albedo, vec3(0.299, 0.587, 0.114));
    return smoothstep(0.78, 0.96, brightness) * (1.0 - smoothstep(0.15, 0.45, saturation)) * 0.35;
}

float deriveEmission(vec3 albedo, float blockLight) {
    float maxC = max(albedo.r, max(albedo.g, albedo.b));
    float warmGlow = smoothstep(0.82, 0.99, maxC) * smoothstep(0.88, 0.99, blockLight);
    return clamp(warmGlow, 0.0, 1.0);
}

void main() {
    vec4 texSample = texture(u_BlockAtlas, v_TexCoord);
    if (texSample.a < 0.1) {
        discard;
    }

    // Convert sRGB block texture + vertex AO/tint to linear space
    vec3 srgbAlbedo = texSample.rgb * v_VertexColor.rgb;
    vec3 linearAlbedo = pow(max(srgbAlbedo, vec3(0.0)), vec3(2.2));

    // Geometric normal fallback if vertex normal is not supplied
    vec3 N = v_Normal;
    if (dot(N, N) < 0.001) {
        vec3 dpdx = dFdx(v_WorldPos);
        vec3 dpdy = dFdy(v_WorldPos);
        N = normalize(cross(dpdx, dpdy));
    } else {
        N = normalize(N);
    }

    float roughness = deriveRoughness(linearAlbedo, v_TexCoord);
    float metallic  = deriveMetallic(linearAlbedo);

    vec2 lightLevels = clamp(v_LightCoord, vec2(0.0), vec2(1.0));
    float emission   = deriveEmission(srgbAlbedo, lightLevels.x);

    // Attachment 0: RGB = Linear Albedo, A = Roughness [0.04, 1.0]
    outAlbedoRoughness = vec4(linearAlbedo, roughness);

    // Attachment 1: RGB = Encoded Normal [0, 1], A = Metalness [0.0, 1.0]
    outNormalMetallic  = vec4(N * 0.5 + 0.5, metallic);

    // Attachment 2: R = Block Light, G = Sky Light, B = Emission, A = Terrain Flag (1.0)
    outLightEmission   = vec4(lightLevels.x, lightLevels.y, emission, 1.0);
}
