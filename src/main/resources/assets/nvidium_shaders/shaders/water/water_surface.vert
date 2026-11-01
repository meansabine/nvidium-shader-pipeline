#version 460 core

// ============================================================================
// Nvidium Shader Pipeline (NSP) - Phase 5: Water Surface Vertex Shader
// Evaluates Gerstner wave displacement and forwards world-space coordinates.
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

layout(location = 0) in vec3 a_Position;
layout(location = 1) in vec2 a_TexCoord;
layout(location = 2) in vec2 a_LightCoord;

layout(location = 0) out vec3 v_WorldPos;
layout(location = 1) out vec2 v_TexCoord;
layout(location = 2) out vec2 v_LightCoord;

vec3 evaluateGerstnerOffset(vec2 xz, float time) {
    const vec4 wave0 = vec4(0.85, 0.52, 0.22, 3.8);
    const vec4 wave1 = vec4(-0.42, 0.91, 0.16, 2.1);
    const vec4 wave2 = vec4(0.61, -0.79, 0.12, 1.2);

    vec3 disp = vec3(0.0);
    vec4 waves[3] = vec4[3](wave0, wave1, wave2);

    for (int i = 0; i < 3; ++i) {
        vec2  dir = normalize(waves[i].xy);
        float steepness = waves[i].z;
        float wavelength = waves[i].w;
        float k = 6.2831853 / wavelength;
        float c = sqrt(9.81 / k);
        float phase = k * (dot(dir, xz) - c * time);
        float amp = steepness / (k * 3.0);

        disp.x += dir.x * (amp * cos(phase));
        disp.y += amp * sin(phase);
        disp.z += dir.y * (amp * cos(phase));
    }
    return disp;
}

void main() {
    vec3 worldPos = a_Position;
    worldPos += evaluateGerstnerOffset(worldPos.xz, u_Time);

    v_WorldPos   = worldPos;
    v_TexCoord   = a_TexCoord;
    v_LightCoord = a_LightCoord;

    gl_Position = u_ViewProjMatrix * vec4(worldPos, 1.0);
}
