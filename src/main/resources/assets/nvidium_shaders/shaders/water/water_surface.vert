#version 460 core

layout(location = 0) out vec3 v_WorldPos;
layout(location = 1) out vec2 v_TexCoord;
layout(location = 2) out vec2 v_LightCoord;

// Global Scene Uniform Buffer (std140, binding = 3)
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

void main() {
    // Generate fullscreen triangle
    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    v_TexCoord = uv;
    v_LightCoord = vec2(1.0, 1.0);

    vec4 clip = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    vec4 world = u_InvViewProjMatrix * clip;
    v_WorldPos = world.xyz / world.w;

    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
