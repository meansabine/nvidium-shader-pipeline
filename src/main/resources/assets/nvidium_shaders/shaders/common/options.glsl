#ifndef NVIDIUM_PACK_OPTIONS_GLSL
#define NVIDIUM_PACK_OPTIONS_GLSL

layout(std140, binding = 4) uniform PackOptions {
    vec4 u_PackOptions[16];
};

float getPackOption(int slot) {
    int vecIdx = clamp(slot >> 2, 0, 15);
    int compIdx = slot & 3;
    return u_PackOptions[vecIdx][compIdx];
}

#define OPT_SUNLIGHT_INTENSITY      u_PackOptions[0].x
#define OPT_AMBIENT_INTENSITY       u_PackOptions[0].y
#define OPT_EMISSIVE_STRENGTH       u_PackOptions[0].z
#define OPT_HANDHELD_DYNAMIC_LIGHT  u_PackOptions[0].w

#define OPT_CLOUD_MODE              u_PackOptions[1].x
#define OPT_CLOUD_COVERAGE          u_PackOptions[1].y
#define OPT_GODRAYS_STRENGTH        u_PackOptions[1].z
#define OPT_ATMOS_HAZINESS          u_PackOptions[1].w

#define OPT_WATER_WAVE_HEIGHT       u_PackOptions[2].x
#define OPT_WATER_CLARITY           u_PackOptions[2].y
#define OPT_WATER_CAUSTICS          u_PackOptions[2].z
#define OPT_RAIN_PUDDLES            u_PackOptions[2].w

#define OPT_SUBSURFACE_SCATTERING   u_PackOptions[3].x
#define OPT_NORMAL_BUMP_STRENGTH    u_PackOptions[3].y
#define OPT_WIND_SPEED              u_PackOptions[3].z
#define OPT_SHADOW_SOFTNESS         u_PackOptions[3].w

#endif
