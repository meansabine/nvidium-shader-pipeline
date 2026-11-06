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
