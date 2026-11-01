#version 460 core

layout(binding = 0) uniform sampler2D u_BlockAtlas;
layout(binding = 1) uniform sampler2D u_BlockAtlasNormal;   // LabPBR _n
layout(binding = 2) uniform sampler2D u_BlockAtlasSpecular; // LabPBR _s

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_LightCoord; // x: BlockLight, y: SkyLight
layout(location = 3) in vec3 v_WorldNormal;
layout(location = 4) in vec4 v_Tangent;

layout(location = 0) out vec4 outAlbedoRoughness; // Attachment 0: Albedo (RGB) + Roughness (A)
layout(location = 1) out vec4 outNormalMetallic;  // Attachment 1: Normal (XYZ) + Metalness (A)
layout(location = 2) out vec4 outLightEmission;   // Attachment 2: BlockLight (R), SkyLight (G), Emission (B)

void main() {
    vec4 baseSample = texture(u_BlockAtlas, v_TexCoord);
    if (baseSample.a < 0.1) {
        discard;
    }

    vec3 albedo = baseSample.rgb * v_Color.rgb;

    // Decode LabPBR Normal Map (_n: XY = tangent normal, Z = AO, W = height)
    vec4 normalSample = texture(u_BlockAtlasNormal, v_TexCoord);
    vec3 N = normalize(v_WorldNormal);
    if (length(normalSample.xy) > 0.01) {
        vec3 tangentNormal;
        tangentNormal.xy = normalSample.xy * 2.0 - 1.0;
        tangentNormal.z = sqrt(clamp(1.0 - dot(tangentNormal.xy, tangentNormal.xy), 0.0, 1.0));
        vec3 T = normalize(v_Tangent.xyz);
        vec3 B = cross(N, T) * v_Tangent.w;
        mat3 TBN = mat3(T, B, N);
        N = normalize(TBN * tangentNormal);
    }

    // Decode LabPBR Specular Map (_s: R = smoothness, G = F0/metalness, A = emission)
    vec4 specSample = texture(u_BlockAtlasSpecular, v_TexCoord);
    float perceptualSmoothness = specSample.r;
    float roughness = clamp(pow(1.0 - perceptualSmoothness, 2.0), 0.04, 1.0);
    float metallic = (specSample.g * 255.0 >= 229.5) ? 1.0 : clamp(specSample.g, 0.0, 1.0);
    float emission = (specSample.a < 1.0) ? specSample.a : 0.0;

    outAlbedoRoughness = vec4(albedo, roughness);
    outNormalMetallic  = vec4(N * 0.5 + 0.5, metallic);
    outLightEmission   = vec4(clamp(v_LightCoord.x, 0.0, 1.0), clamp(v_LightCoord.y, 0.0, 1.0), emission, 1.0);
}
