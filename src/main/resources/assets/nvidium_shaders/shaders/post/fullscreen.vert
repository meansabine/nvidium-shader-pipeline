#version 460 core

// ============================================================================
// Nvidium Shader Pipeline (NSP) - Fullscreen Triangle Vertex Shader
// Generates a clip-space triangle covering [-1, 1] without VBO bindings.
// ============================================================================

layout(location = 0) out vec2 v_Uv;

void main() {
    v_Uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(v_Uv * 2.0 - 1.0, 0.0, 1.0);
}
