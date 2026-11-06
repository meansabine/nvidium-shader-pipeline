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

layout(location = 0) out vec4 outAlbedoRoughness; // RGB = Linear Albedo, A = Roughness
layout(location = 1) out vec4 outNormalMetallic;  // RGB = World Normal, A = Packed(Metallic, MaterialId)
layout(location = 2) out vec4 outLightEmission;   // R = BlockLight, G = SkyLight, B = Emissive, A = Vertex/Tex AO

layout(location = 5) in NvidiumVertexData {
    vec2 v_uv;
    vec3 v_color;
    vec2 v_lightUV;
} nv_in;

layout(binding = 0) uniform sampler2D tex_diffuse;
layout(binding = 1) uniform sampler2D tex_light;
layout(binding = 2) uniform sampler2D tex_normal;   // LabPBR _n
layout(binding = 3) uniform sampler2D tex_specular; // LabPBR _s

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

    vec2 uv0 = decodeVertexUV(V0);
    vec2 uv1 = decodeVertexUV(V1);
    vec2 uv2 = decodeVertexUV(V2);
    // Hardware-clipped, perspective-correct UV from mesh shader (immune to frustum-clipping distortion)
    vec2 uv = nv_in.v_uv;

    float lodBias = (((gl_PrimitiveID >> 2) & 1) != 0) ? -8.0 : -0.35;
    vec4 texSample = texture(tex_diffuse, uv, lodBias);

    float alphaCutoff = getVertexAlphaCutoff(uint(gl_PrimitiveID) & 3u);
    if (texSample.a < alphaCutoff) {
        discard;
    }

    // Decode positions & exact geometric face normal
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

    // Separate hardware-clipped vertex color into normalized biome tint and smooth vertex AO
    vec3 rawVCol = nv_in.v_color;

    float maxChannel = max(rawVCol.r, max(rawVCol.g, rawVCol.b));
    vec3 normalizedTint = (maxChannel > 1.0e-4) ? (rawVCol / maxChannel) : vec3(1.0);
    // Undo vanilla's baked cardinal face shade (0.5 bottom, 0.6 N/S, 0.8 E/W, 1.0 top) so deferred PBR shades faces physically
    float vanillaFaceFactor = (geomNormal.y < -0.5) ? 0.5 : ((abs(geomNormal.y) > 0.5) ? 1.0 : ((abs(geomNormal.z) > 0.5) ? 0.6 : 0.8));
    float vertexAO = clamp(maxChannel / max(vanillaFaceFactor, 0.5), 0.15, 1.0);

    vec3 srgbAlbedo = texSample.rgb * normalizedTint;
    vec3 linearAlbedo = pow(max(srgbAlbedo, vec3(0.0)), vec3(2.2));

    // Decode hardware-clipped lightmap UV & block material ID
    vec2 lightUV = nv_in.v_lightUV;
    float blockLight = clamp((lightUV.x - 0.03125) / 0.9375, 0.0, 1.0);
    float skyLight   = clamp((lightUV.y - 0.03125) / 0.9375, 0.0, 1.0);

    uint materialId = decodeVertexMaterial(V0);
    vec4 labSpec = texture(tex_specular, uv, lodBias);
    MaterialProperties mat = evaluateBlockMaterial(materialId, linearAlbedo, labSpec, lightUV);

    // Construct tangent frame (T, B, N) for LabPBR normal mapping when a LabPBR pack is active
    vec3 worldNormal = geomNormal;
    float bumpStrength = max(0.0, OPT_NORMAL_BUMP_STRENGTH);
    if (bumpStrength > 0.01) {
        vec4 labNorm = texture(tex_normal, uv, lodBias);
        vec2 labXY = labNorm.rg * 2.0 - 1.0;

        if (dot(labXY, labXY) > 0.002) {
            vec2 duv1 = uv1 - uv0;
            vec2 duv2 = uv2 - uv0;
            float det = duv1.x * duv2.y - duv2.x * duv1.y;
            vec3 T;
            vec3 B;
            if (abs(det) > 1.0e-7) {
                float invDet = 1.0 / det;
                T = normalize((edge1 * duv2.y - edge2 * duv1.y) * invDet);
                B = normalize((edge2 * duv1.x - edge1 * duv2.x) * invDet);
            } else {
                vec3 upRef = abs(geomNormal.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                T = normalize(cross(upRef, geomNormal));
                B = cross(geomNormal, T);
            }
            labXY *= bumpStrength;
            float nz = sqrt(max(1.0 - dot(labXY, labXY), 0.01));
            worldNormal = normalize(T * labXY.x + B * labXY.y + geomNormal * nz);
            float texAO = clamp(labNorm.b, 0.25, 1.0);
            vertexAO *= texAO;
        }
    }

    // Outdoor rain wetness & porous darkening
    float wetness = u_Weather.y;
    if (wetness > 0.01 && OPT_RAIN_PUDDLES > 0.5 && skyLight > 0.65 && materialId != MAT_LAVA && materialId != MAT_EMISSIVE) {
        float outdoorMask = smoothstep(0.65, 0.92, skyLight);
        float upFacing = clamp(geomNormal.y, 0.0, 1.0);
        float surfaceWet = wetness * outdoorMask * (0.35 + 0.65 * upFacing);

        // Wet porous darkening (wet stone, dirt, wood, sand look darker and richer)
        linearAlbedo *= mix(1.0, 1.0 - mat.porosity * 0.38, surfaceWet);
        mat.roughness = mix(mat.roughness, 0.07, surfaceWet * 0.82);
        worldNormal = normalize(mix(worldNormal, geomNormal, surfaceWet * 0.55));
    }

    outAlbedoRoughness = vec4(linearAlbedo, clamp(mat.roughness, 0.02, 1.0));
    outNormalMetallic  = vec4(worldNormal, packMetallicAndMaterial(mat.metallic, mat.materialId));
    outLightEmission   = vec4(blockLight, skyLight, mat.emissive, clamp(vertexAO, 0.0, 1.0));
}
