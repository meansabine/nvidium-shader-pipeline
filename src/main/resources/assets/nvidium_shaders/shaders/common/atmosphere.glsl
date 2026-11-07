#ifndef NVIDIUM_ATMOSPHERE_GLSL
#define NVIDIUM_ATMOSPHERE_GLSL

float phaseHenyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * pow(max(denom, 1.0e-4), 1.5));
}

/**
 * Evaluates procedural 3D / 2D clouds along view ray V.
 */
vec4 evaluateSkyClouds(vec3 V, vec3 sunDir, vec3 sunCol, vec3 moonDir, vec3 moonCol, vec3 skyAmbient, float cloudMode, float coverageOpt) {
    if (cloudMode < 0.5 || V.y <= 0.015) {
        return vec4(0.0);
    }

    float rain = u_Weather.x;
    float coverage = clamp(mix(coverageOpt, 0.88, rain * 0.75), 0.0, 0.96);
    if (coverage <= 0.01) {
        return vec4(0.0);
    }

    float windTime = u_Time.x * 2.2;
    vec2 windOffset = vec2(windTime, windTime * 0.42);

    // Project view ray to cloud altitude plane (relative to camera height)
    float cloudHeight = max(80.0, 210.0 - u_CameraPos.y);
    vec2 planeXZ = u_CameraPos.xz + (V.xz / max(V.y, 0.04)) * cloudHeight;
    float horizonFade = smoothstep(0.015, 0.18, V.y);

    // High-altitude wispy cirrus layer
    vec2 cirrusUV = (planeXZ + windOffset * 1.4) * 0.0014;
    float cirrusNoise = fbm2D(cirrusUV) * fbm2D(cirrusUV * 2.3 + vec2(4.2, -1.9));
    float cirrusDensity = smoothstep(1.0 - coverage * 0.85, 1.0, cirrusNoise * 1.65) * 0.42;

    if (cloudMode < 1.5) {
        vec3 cloudCol = skyAmbient * 1.15 + sunCol * 0.35 + moonCol * 0.25;
        return vec4(cloudCol, cirrusDensity * horizonFade);
    }

    // Volumetric 5-step FBM cumulus slab
    vec3 lightDir = (u_SunDirection.y >= 0.0) ? sunDir : moonDir;
    vec3 activeLightCol = (u_SunDirection.y >= 0.0) ? (sunCol * u_SunColor.w) : (moonCol * u_MoonColor.w * 1.6);
    float cosAngle = dot(V, lightDir);
    float silverLining = phaseHenyeyGreenstein(cosAngle, 0.62) * 2.8 + phaseHenyeyGreenstein(cosAngle, -0.25) * 0.6;

    float accumAlpha = 0.0;
    vec3 accumColor = vec3(0.0);

    for (int i = 0; i < 5; i++) {
        float fi = (float(i) + 0.5) / 5.0;
        float h = mix(cloudHeight, cloudHeight + 55.0, fi);
        vec2 sampleXZ = u_CameraPos.xz + (V.xz / max(V.y, 0.045)) * h + windOffset;
        vec2 uv = sampleXZ * 0.0022;

        float base = fbm2D(uv);
        float detail = fbm2D(uv * 2.75 - windOffset * 0.003);
        float shape = base * 0.72 + detail * 0.28;

        // Vertical rounded profile (flat bottom, fluffy top)
        float verticalProfile = smoothstep(0.0, 0.22, fi) * smoothstep(1.0, 0.45, fi);
        float density = smoothstep(1.0 - coverage * 0.78, 0.96, shape * verticalProfile * 1.35);

        if (density > 0.005) {
            // Self-shadowing tap toward active light
            vec2 lightSampleUV = uv + lightDir.xz * 0.065;
            float lightOccluder = fbm2D(lightSampleUV) * verticalProfile;
            float lightTrans = exp(-max(0.0, lightOccluder - (1.0 - coverage * 0.75)) * 4.2);

            float powder = 1.0 - exp(-density * 3.2);
            vec3 directLit = activeLightCol * lightTrans * (0.42 + silverLining * powder * 0.55);
            vec3 ambientLit = mix(skyAmbient * 0.52, skyAmbient * 1.15, fi);
            vec3 stepCol = (ambientLit + directLit) * (1.0 - rain * 0.42);

            float stepAlpha = density * 0.34 * (1.0 - accumAlpha);
            accumColor += stepCol * stepAlpha;
            accumAlpha += stepAlpha;
            if (accumAlpha > 0.96) break;
        }
    }

    // Blend high cirrus behind cumulus
    float totalAlpha = clamp(accumAlpha + cirrusDensity * (1.0 - accumAlpha), 0.0, 0.98) * horizonFade;
    vec3 finalCloudColor = (accumColor + (skyAmbient * 1.2 + activeLightCol * 0.25) * cirrusDensity * (1.0 - accumAlpha))
            / max(accumAlpha + cirrusDensity * (1.0 - accumAlpha), 1.0e-4);

    return vec4(finalCloudColor, totalAlpha);
}

/**
 * Evaluates smooth Rayleigh + gentle Mie atmospheric scattering without solar/lunar disks,
 * tight coronas, stars, or clouds. Safe for distance aerial perspective haze on opaque geometry
 * so the Sun and Moon never bleed through solid objects.
 */
vec3 evaluateAtmosphericHazeSky(vec3 V, float haziness) {
    int dimId = int(round(u_FogParams.w));
    if (dimId == -1) {
        float h = clamp(V.y * 0.5 + 0.5, 0.0, 1.0);
        return mix(vec3(0.38, 0.07, 0.02), vec3(0.14, 0.02, 0.01), h);
    }
    if (dimId == 1) {
        return mix(vec3(0.08, 0.04, 0.14), vec3(0.02, 0.01, 0.04), clamp(abs(V.y), 0.0, 1.0));
    }

    vec3 sunDir = normalize(u_SunDirection.xyz);
    vec3 moonDir = normalize(u_MoonDirection.xyz);
    float sunsetFactor = u_HorizonColor.w;
    float rain = u_Weather.x;

    float zenithT = pow(clamp(max(V.y, 0.0), 0.0, 1.0), 0.52);
    vec3 skyGrad = mix(u_HorizonColor.rgb, u_SkyColor.rgb, zenithT);

    if (V.y < 0.0) {
        float groundT = clamp(-V.y * 3.5, 0.0, 1.0);
        skyGrad = mix(u_HorizonColor.rgb, u_SkyColor.rgb * 0.22, groundT);
    }

    // Broad, soft forward-scattering glow only (clamped so it never forms a disk or tight halo over terrain)
    float cosSun = clamp(dot(V, sunDir), 0.0, 1.0);
    float cosMoon = clamp(dot(V, moonDir), 0.0, 1.0);
    float mieHaze = max(0.2, haziness);

    float broadSunGlow = pow(cosSun, 3.0) * 0.18 * mieHaze * (1.0 + sunsetFactor * 0.8);
    skyGrad += u_SunColor.rgb * u_SunColor.w * broadSunGlow * 0.20 * (1.0 - rain * 0.80);

    float broadMoonGlow = pow(cosMoon, 3.0) * 0.14 * mieHaze;
    skyGrad += u_MoonColor.rgb * u_MoonColor.w * broadMoonGlow * 0.22 * (1.0 - rain * 0.80);

    return max(skyGrad, vec3(0.0));
}

/**
 * Anti-aliased 3D Voronoi starfield on the celestial sphere.
 */
vec3 evaluateCelestialStars(vec3 rayDir, float starDensity, float brightness) {
    vec3 p = rayDir * starDensity;
    vec3 cell = floor(p);
    vec3 frac = fract(p) - 0.5;

    float h = hash13(cell);
    float threshold = 0.965;
    if (h <= threshold) {
        return vec3(0.0);
    }

    vec3 starOffset = (vec3(
            hash13(cell + vec3(13.7, 5.3, 9.1)),
            hash13(cell + vec3(29.3, 41.1, 17.7)),
            hash13(cell + vec3(53.9, 11.3, 37.5))
    ) - 0.5) * 0.55;

    float dist = length(frac - starOffset);
    float spot = smoothstep(0.22, 0.03, dist);
    if (spot <= 0.001) {
        return vec3(0.0);
    }

    float mag = (h - threshold) / (1.0 - threshold);
    float twinkle = 0.72 + 0.28 * sin(u_Time.x * (2.5 + mag * 3.0) + h * 157.0);
    vec3 starTint = mix(vec3(0.70, 0.84, 1.00), vec3(1.00, 0.86, 0.68), fract(h * 91.7));
    return starTint * spot * (0.4 + mag * 1.8) * twinkle * brightness;
}

/**
 * Evaluates full physical sky, solar/lunar disks, stars, and clouds along view direction V.
 * Used for sky background pixels (depth >= 0.99999) and sharp specular mirror/water reflections.
 */
vec3 evaluatePhysicalSky(vec3 V, float cloudMode, float cloudCoverage, float haziness) {
    int dimId = int(round(u_FogParams.w));
    if (dimId == -1) {
        // Nether volumetric ember & volcanic ash dome
        float h = clamp(V.y * 0.5 + 0.5, 0.0, 1.0);
        vec3 crimsonBase = mix(vec3(0.42, 0.08, 0.025), vec3(0.14, 0.025, 0.012), h);
        vec2 plumeUV = V.xz / max(abs(V.y) + 0.35, 0.35);
        float smokeA = fbm2D(plumeUV * 2.2 + vec2(u_Time.x * 0.08, -u_Time.x * 0.05));
        float smokeB = fbm2D(plumeUV * 4.5 - vec2(u_Time.x * 0.12, u_Time.x * 0.09));
        float emberGlow = smoothstep(0.42, 0.85, smokeA * 0.65 + smokeB * 0.35);
        return crimsonBase + vec3(0.32, 0.09, 0.02) * emberGlow * (1.0 - h * 0.45);
    }
    if (dimId == 1) {
        // The End cosmic void nebula & 3D Voronoi starfield
        float absY = clamp(abs(V.y), 0.0, 1.0);
        vec3 voidBase = mix(vec3(0.07, 0.035, 0.13), vec3(0.015, 0.008, 0.035), absY);
        vec2 nebUV = V.xz / max(absY + 0.28, 0.28);
        float neb1 = fbm2D(nebUV * 1.6 + vec2(u_Time.x * 0.02, -u_Time.x * 0.015));
        float neb2 = fbm2D(nebUV * 3.1 - vec2(u_Time.x * 0.025, u_Time.x * 0.02) + neb1 * 0.65);
        vec3 purpleNebula = vec3(0.26, 0.08, 0.42) * smoothstep(0.42, 0.82, neb1);
        vec3 tealFilament = vec3(0.06, 0.24, 0.28) * smoothstep(0.55, 0.88, neb2) * 0.65;
        vec3 endStars = evaluateCelestialStars(normalize(V), 95.0, 1.85);
        return voidBase + (purpleNebula + tealFilament) * (1.0 - absY * 0.35) + endStars;
    }

    vec3 sunDir = normalize(u_SunDirection.xyz);
    vec3 moonDir = normalize(u_MoonDirection.xyz);
    float dayFactor = u_SkyColor.w;
    float sunsetFactor = u_HorizonColor.w;
    float nightFactor = 1.0 - dayFactor;
    float rain = u_Weather.x;

    // Zenith-to-horizon Rayleigh gradient
    float zenithT = pow(clamp(max(V.y, 0.0), 0.0, 1.0), 0.52);
    vec3 skyGrad = mix(u_HorizonColor.rgb, u_SkyColor.rgb, zenithT);

    // Lower hemisphere ground bounce fade
    if (V.y < 0.0) {
        float groundT = clamp(-V.y * 3.5, 0.0, 1.0);
        skyGrad = mix(u_HorizonColor.rgb, u_SkyColor.rgb * 0.22, groundT);
    }

    // Mie forward scattering around Sun & Moon
    float cosSun = dot(V, sunDir);
    float cosMoon = dot(V, moonDir);
    float mieHaze = max(0.2, haziness);

    float sunMie = phaseHenyeyGreenstein(cosSun, 0.78) * 0.45 * mieHaze
                 + pow(clamp(cosSun, 0.0, 1.0), 8.0) * 0.35 * (1.0 + sunsetFactor * 1.4);
    skyGrad += u_SunColor.rgb * u_SunColor.w * sunMie * 0.28 * (1.0 - rain * 0.80);

    float moonMie = phaseHenyeyGreenstein(cosMoon, 0.72) * 0.35 * mieHaze;
    skyGrad += u_MoonColor.rgb * u_MoonColor.w * moonMie * 0.35 * (1.0 - rain * 0.80);

    // Solar disk, limb darkening & corona
    if (V.y > -0.05 && rain < 0.92) {
        float sunDisk = smoothstep(0.9991, 0.99965, cosSun);
        float sunCorona = pow(clamp(cosSun, 0.0, 1.0), 96.0) * 1.4;
        skyGrad += u_SunColor.rgb * (sunDisk * 18.0 + sunCorona) * (1.0 - rain * 0.9);

        // Lunar disk with procedural craters and phase shading
        float moonDisk = smoothstep(0.9992, 0.99965, cosMoon);
        if (moonDisk > 0.001) {
            float craters = fbm2D(V.xy * 120.0 + V.z * 80.0);
            float phaseLit = clamp(u_MoonDirection.w, 0.25, 1.0);
            vec3 moonSurface = u_MoonColor.rgb * mix(0.65, 1.15, craters) * 6.5 * phaseLit;
            skyGrad += moonSurface * moonDisk * (1.0 - rain * 0.9);
        }
    }

    // Overworld Night Starfield (rotates smoothly with celestial sphere) & Milky Way band
    if (nightFactor > 0.05 && V.y > 0.02 && rain < 0.85) {
        float starVis = nightFactor * (1.0 - rain) * smoothstep(0.02, 0.22, V.y);
        float angle = u_Time.y * TAU;
        float ca = cos(angle);
        float sa = sin(angle);
        vec3 rotV = vec3(V.x * ca - V.y * sa, V.x * sa + V.y * ca, V.z);
        skyGrad += evaluateCelestialStars(rotV, 85.0, starVis * 2.2);

        // Milky way glow band
        float galaxyBand = exp(-pow((rotV.x * 0.65 + rotV.z * 0.75) * 2.6, 2.0));
        float galaxyDust = fbm2D(rotV.xz * 4.2);
        skyGrad += vec3(0.035, 0.055, 0.11) * galaxyBand * galaxyDust * starVis;
    }

    // Composite volumetric / planar clouds over sky
    vec4 clouds = evaluateSkyClouds(V, sunDir, u_SunColor.rgb, moonDir, u_MoonColor.rgb, skyGrad, cloudMode, cloudCoverage);
    skyGrad = mix(skyGrad, clouds.rgb, clouds.a);

    // Lightning flash boost during thunderstorms
    if (u_Weather.w > 0.01) {
        skyGrad += vec3(0.45, 0.55, 0.85) * u_Weather.w;
    }

    return max(skyGrad, vec3(0.0));
}

#endif
