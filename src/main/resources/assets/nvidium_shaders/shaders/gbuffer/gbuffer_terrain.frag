#version 460 core

#extension GL_NV_gpu_shader5 : require
#extension GL_NV_bindless_texture : enable
#extension GL_NV_fragment_shader_barycentric : require

// MRT Framebuffer Outputs
layout(location = 0) out vec4 outAlbedoRoughness;
layout(location = 1) out vec4 outNormalMetallic;
layout(location = 2) out vec4 outLightEmission;

// Texture Atlas Samplers
layout(binding = 0) uniform sampler2D tex_diffuse;
layout(binding = 1) uniform sampler2D tex_light;
layout(binding = 2) uniform sampler2D tex_normals;   // LabPBR Normal Map Atlas
layout(binding = 3) uniform sampler2D tex_specular;  // LabPBR Specular (Smoothness, Metallic, Emission)

in vec3 v_WorldPos;
in vec2 v_Uv;
in vec2 v_LightUv;
in vec4 v_ColorTint;

void main() {
    vec4 albedo = texture(tex_diffuse, v_Uv);
    if (albedo.a < 0.1) discard; // Alpha cutoff
    albedo.rgb *= v_ColorTint.rgb;

    // 1. Calculate Geometric Surface Normal from screen-space derivatives
    vec3 dX = dFdx(v_WorldPos);
    vec3 dY = dFdy(v_WorldPos);
    vec3 geomNormal = normalize(cross(dX, dY));

    // 2. Decode LabPBR Normal Mapping if texture is available
    vec4 normalSample = texture(tex_normals, v_Uv);
    vec3 normal = geomNormal;
    if (normalSample.a > 0.0) {
        // Construct tangent space from UV derivatives
        vec2 dUvX = dFdx(v_Uv);
        vec2 dUvY = dFdy(v_Uv);
        vec3 tangent = normalize(dX * dUvY.t - dY * dUvX.t);
        vec3 bitangent = cross(geomNormal, tangent);
        mat3 TBN = mat3(tangent, bitangent, geomNormal);

        vec3 tangentNormal = normalSample.xyz * 2.0 - 1.0;
        normal = normalize(TBN * tangentNormal);
    }

    // 3. Decode LabPBR Specular / Material Properties
    vec4 specSample = texture(tex_specular, v_Uv);
    float roughness = clamp(1.0 - specSample.r, 0.04, 1.0); // Red: Smoothness -> Roughness
    float metallic = specSample.g;                         // Green: Metalness
    float emission = specSample.a > 0.0 ? specSample.b : 0.0; // Blue: Emissive

    // Write to G-Buffer Multiple Render Targets
    outAlbedoRoughness = vec4(albedo.rgb, roughness);
    outNormalMetallic = vec4(normal * 0.5 + 0.5, metallic); // Pack [-1,1] normal to [0,1]
    outLightEmission = vec4(v_LightUv, emission, 1.0);
}
