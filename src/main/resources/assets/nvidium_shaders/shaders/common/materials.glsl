#ifndef NVIDIUM_MATERIALS_GLSL
#define NVIDIUM_MATERIALS_GLSL

const uint MAT_SOLID          = 0u;
const uint MAT_WATER          = 1u;
const uint MAT_LAVA           = 2u;
const uint MAT_FOLIAGE_WAVING = 3u;
const uint MAT_LEAVES         = 4u;
const uint MAT_GLASS          = 5u;
const uint MAT_ICE            = 6u;
const uint MAT_EMISSIVE       = 7u;
const uint MAT_METAL          = 8u;
const uint MAT_GEM            = 9u;
const uint MAT_POLISHED       = 10u;
const uint MAT_WET            = 11u;
const uint MAT_SAND_SNOW      = 12u;
const uint MAT_PORTAL         = 13u;
const uint MAT_SLIME_HONEY    = 14u;
const uint MAT_ORE            = 15u;

struct MaterialProperties {
    float roughness;
    float metallic;
    float emissive;
    float subsurface;
    float porosity;
    uint  materialId;
};

/**
 * Packs continuous metallic [0..1] and integer materialId [0..31] into a single float
 * stored in G-Buffer Attachment 1's alpha channel (RGBA16F has 10-bit mantissa, easily holding 0..31 + fraction).
 */
float packMetallicAndMaterial(float metallic, uint materialId) {
    float m = clamp(metallic, 0.0, 0.98);
    return float(materialId & 31u) + m;
}

void unpackMetallicAndMaterial(float packedVal, out float metallic, out uint materialId) {
    float floored = floor(max(0.0, packedVal) + 1.0e-4);
    materialId = uint(clamp(floored, 0.0, 31.0));
    metallic = clamp((packedVal - floored) / 0.98, 0.0, 1.0);
}

/**
 * Evaluates full PBR material properties from:
 *   1. Exact 5-bit block material ID encoded in NvidiumCompactChunkVertex
 *   2. LabPBR 1.3 specular texture (_s) if supplied by the active resource pack
 *   3. Micro-texture color/luminance variation so individual pixels on ores, bricks, and wood react naturally
 */
MaterialProperties evaluateBlockMaterial(
        uint materialId,
        vec3 linearAlbedo,
        vec4 labPbrSpec,
        vec2 lightUV) {
    MaterialProperties mat;
    mat.materialId = materialId;
    mat.roughness = 0.82;
    mat.metallic = 0.0;
    mat.emissive = 0.0;
    mat.subsurface = 0.0;
    mat.porosity = 0.35;

    float luma = dot(linearAlbedo, vec3(0.2126, 0.7152, 0.0722));
    float maxC = max(linearAlbedo.r, max(linearAlbedo.g, linearAlbedo.b));
    float minC = min(linearAlbedo.r, min(linearAlbedo.g, linearAlbedo.b));
    float chroma = (maxC - minC) / max(maxC, 1.0e-4);

    // Subtle micro-roughness variation from albedo detail so surfaces aren't flat plastic
    float detailVar = clamp((luma - 0.25) * 0.18, -0.08, 0.08);

    if (materialId == MAT_WATER) {
        mat.roughness = 0.03;
        mat.metallic = 0.0;
        mat.subsurface = 0.35;
        mat.porosity = 0.0;
    } else if (materialId == MAT_LAVA) {
        mat.roughness = 0.35;
        mat.metallic = 0.0;
        mat.emissive = clamp(0.85 + luma * 1.4, 0.6, 2.5);
        mat.porosity = 0.0;
    } else if (materialId == MAT_FOLIAGE_WAVING) {
        mat.roughness = clamp(0.68 - detailVar, 0.45, 0.85);
        mat.metallic = 0.0;
        mat.subsurface = 0.78;
        mat.porosity = 0.25;
    } else if (materialId == MAT_LEAVES) {
        mat.roughness = clamp(0.58 - detailVar, 0.38, 0.78);
        mat.metallic = 0.0;
        mat.subsurface = 0.65;
        mat.porosity = 0.20;
    } else if (materialId == MAT_GLASS) {
        mat.roughness = 0.05;
        mat.metallic = 0.0;
        mat.subsurface = 0.10;
        mat.porosity = 0.0;
    } else if (materialId == MAT_ICE) {
        mat.roughness = clamp(0.12 - detailVar * 0.5, 0.05, 0.24);
        mat.metallic = 0.0;
        mat.subsurface = 0.55;
        mat.porosity = 0.05;
    } else if (materialId == MAT_EMISSIVE) {
        mat.roughness = 0.35;
        mat.metallic = 0.0;
        // Bright pixels of torches/lanterns/glowstone/sculk glow strongly while dark frames stay solid
        float glowMask = smoothstep(0.08, 0.55, luma + chroma * 0.35);
        mat.emissive = mix(0.22, 1.65, glowMask);
        mat.porosity = 0.10;
    } else if (materialId == MAT_METAL) {
        mat.roughness = clamp(0.24 - detailVar * 0.8, 0.10, 0.42);
        mat.metallic = 0.88;
        mat.porosity = 0.02;
    } else if (materialId == MAT_GEM) {
        mat.roughness = clamp(0.11 - detailVar * 0.5, 0.04, 0.22);
        mat.metallic = 0.18;
        mat.subsurface = 0.32;
        mat.porosity = 0.0;
        if (chroma > 0.45 && luma > 0.25) {
            mat.emissive = 0.12;
        }
    } else if (materialId == MAT_POLISHED) {
        mat.roughness = clamp(0.22 - detailVar * 0.7, 0.09, 0.38);
        mat.metallic = 0.04;
        mat.porosity = 0.12;
    } else if (materialId == MAT_WET) {
        mat.roughness = clamp(0.28 - detailVar * 0.6, 0.12, 0.45);
        mat.metallic = 0.0;
        mat.porosity = 0.75;
    } else if (materialId == MAT_SAND_SNOW) {
        mat.roughness = clamp(0.76 - detailVar * 0.5, 0.55, 0.90);
        mat.metallic = 0.0;
        mat.subsurface = 0.42;
        mat.porosity = 0.65;
    } else if (materialId == MAT_PORTAL) {
        mat.roughness = 0.08;
        mat.metallic = 0.25;
        mat.emissive = 1.60;
        mat.porosity = 0.0;
    } else if (materialId == MAT_SLIME_HONEY) {
        mat.roughness = 0.14;
        mat.metallic = 0.0;
        mat.subsurface = 0.72;
        mat.porosity = 0.0;
    } else if (materialId == MAT_ORE) {
        // Distinguish mineral veins inside the ore block from surrounding stone matrix!
        float veinMask = smoothstep(0.22, 0.48, chroma + max(0.0, luma - 0.32));
        mat.roughness = mix(0.80, 0.18, veinMask);
        mat.metallic = mix(0.0, 0.75, veinMask);
        mat.emissive = veinMask * 0.28;
        mat.porosity = mix(0.40, 0.02, veinMask);
    } else {
        // MAT_SOLID default terrain: subtle roughness contrast from texture luminance
        mat.roughness = clamp(0.80 - detailVar, 0.48, 0.94);
        mat.metallic = 0.0;
        mat.porosity = 0.45;
    }

    // Override with LabPBR 1.3 _s texture if present (non-zero)
    if (dot(labPbrSpec, labPbrSpec) > 0.0005) {
        float perceptualSmoothness = labPbrSpec.r;
        mat.roughness = clamp(pow(1.0 - perceptualSmoothness, 2.0), 0.02, 1.0);
        if (labPbrSpec.g * 255.0 >= 229.5) {
            mat.metallic = 1.0;
        } else {
            mat.metallic = clamp(labPbrSpec.g, 0.0, 0.95);
        }
        if (labPbrSpec.b * 255.0 <= 64.0) {
            mat.porosity = (labPbrSpec.b * 255.0) / 64.0;
        } else {
            mat.subsurface = ((labPbrSpec.b * 255.0) - 65.0) / 190.0;
        }
        if (labPbrSpec.a < (254.5 / 255.0) && labPbrSpec.a > 0.004) {
            mat.emissive = max(mat.emissive, labPbrSpec.a * 2.0);
        }
    }

    return mat;
}

#endif
