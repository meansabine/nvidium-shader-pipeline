#ifndef NVIDIUM_PBR_BRDF_GLSL
#define NVIDIUM_PBR_BRDF_GLSL

float D_GGX(float NoH, float alphaRoughness) {
    float a2 = alphaRoughness * alphaRoughness;
    float d = (NoH * a2 - NoH) * NoH + 1.0;
    return a2 / max(PI * d * d, 1.0e-6);
}

float V_SmithGGXCorrelated(float NoV, float NoL, float alphaRoughness) {
    float a2 = alphaRoughness * alphaRoughness;
    float ggxV = NoL * sqrt(NoV * NoV * (1.0 - a2) + a2);
    float ggxL = NoV * sqrt(NoL * NoL * (1.0 - a2) + a2);
    return 0.5 / max(ggxV + ggxL, 1.0e-5);
}

vec3 F_Schlick(float u, vec3 f0) {
    float f = pow(clamp(1.0 - u, 0.0, 1.0), 5.0);
    return f0 + (1.0 - f0) * f;
}

vec3 F_SchlickRoughness(float cosTheta, vec3 f0, float roughness) {
    return f0 + (max(vec3(1.0 - roughness), f0) - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

float Fd_Burley(float NoV, float NoL, float LoH, float roughness) {
    float f90 = 0.5 + 2.0 * roughness * LoH * LoH;
    float lightScatter = 1.0 + (f90 - 1.0) * pow(1.0 - NoL, 5.0);
    float viewScatter  = 1.0 + (f90 - 1.0) * pow(1.0 - NoV, 5.0);
    return (lightScatter * viewScatter) * (1.0 / PI);
}

/**
 * Evaluates full direct PBR + Subsurface Scattering lighting for a directional or point light.
 */
vec3 evaluateDirectPBR(
        vec3 N,
        vec3 V,
        vec3 L,
        vec3 albedo,
        float roughness,
        float metallic,
        float subsurface,
        vec3 lightRadiance) {
    vec3 H = normalize(V + L);
    float NoV = abs(dot(N, V)) + 1.0e-5;
    float NoL = clamp(dot(N, L), 0.0, 1.0);
    float NoH = clamp(dot(N, H), 0.0, 1.0);
    float LoH = clamp(dot(L, H), 0.0, 1.0);

    float alphaRoughness = max(0.045, roughness * roughness);
    vec3 f0 = mix(vec3(0.04), albedo, metallic);

    float D = D_GGX(NoH, alphaRoughness);
    float Vis = V_SmithGGXCorrelated(NoV, NoL, alphaRoughness);
    vec3 F = F_Schlick(LoH, f0);

    vec3 specular = (D * Vis) * F;

    vec3 kD = (vec3(1.0) - F) * (1.0 - metallic);
    vec3 diffuse = kD * albedo * Fd_Burley(NoV, NoL, LoH, roughness);

    vec3 direct = (diffuse + specular) * NoL * lightRadiance;

    // Subsurface Scattering (wrapped diffuse + forward view-transmitted scatter for foliage/leaves/ice/snow)
    if (subsurface > 0.01) {
        float wrapNoL = clamp((dot(N, L) + 0.45) / 1.45, 0.0, 1.0);
        float VoL = clamp(dot(V, -L), 0.0, 1.0);
        float forwardTrans = pow(VoL, 3.5) * 0.65 + wrapNoL * 0.45;
        vec3 sssTint = pow(max(albedo, vec3(0.02)), vec3(0.75));
        direct += sssTint * forwardTrans * subsurface * (1.0 - metallic) * lightRadiance * 0.48;
    }

    return direct;
}

/**
 * Jiménez 2016 Multi-Bounce Ambient Occlusion approximation (adds colored interreflection in crevices).
 */
vec3 gtaoMultiBounce(float visibility, vec3 albedo) {
    vec3 a =  2.0404 * albedo - 0.3324;
    vec3 b = -4.7951 * albedo + 0.6417;
    vec3 c =  2.7552 * albedo + 0.6903;
    return max(vec3(visibility), ((visibility * a + b) * visibility + c) * visibility);
}

#endif
