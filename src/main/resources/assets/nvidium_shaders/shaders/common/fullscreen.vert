#version 460 core

layout(location = 0) out vec2 v_Uv;

void main() {
    // Generate fullscreen triangle from gl_VertexID without any VBO
    v_Uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(v_Uv * 2.0f - 1.0f, 0.0f, 1.0f);
}
