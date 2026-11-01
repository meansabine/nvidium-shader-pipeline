// Standard Scene Uniform Buffer (std140, binding = 3)
// Must exactly match net.nvidium.shaders.pipeline.uniforms.SceneUniforms

layout(std140, binding = 3) uniform SceneUniforms {
    mat4 u_ViewMatrix;
    mat4 u_ProjMatrix;
    mat4 u_ViewProjMatrix;
    mat4 u_InvViewProjMatrix;
    mat4 u_CascadeViewProj[3];
    vec4 u_CascadeSplits;
    vec4 u_SunDirection;
    vec4 u_SunColor;
    vec4 u_AmbientColor;
    vec4 u_CameraPos;
    vec2 u_ScreenSize;
    vec2 _pad0;
    float u_Time;
    int u_FrameIndex;
    float u_FogStart;
    float u_FogEnd;
    vec4 u_FogColor;
};
