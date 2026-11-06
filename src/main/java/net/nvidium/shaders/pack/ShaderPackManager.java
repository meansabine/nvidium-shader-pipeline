package net.nvidium.shaders.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.config.PipelineConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovers, loads, and manages Nvidium shader packs from built-in resources,
 * {@code shaderpacks/}, and {@code config/nvidium_shaders/} (both folders and .zip archives).
 */
public class ShaderPackManager {
    public static final String BUILTIN_ID = "builtin";
    private static final String BUILTIN_BASE = "/assets/nvidium_shaders/shaders/";

    public static final Map<String, String> DEFAULT_STAGE_PATHS = Map.of(
            "terrain", "gbuffer/terrain_gbuffer.frag",
            "shadow", "shadow/shadow.frag",
            "deferred", "deferred/deferred_lighting.frag",
            "fog", "deferred/volumetric_fog.frag",
            "translucent", "water/water_surface.frag",
            "post", "post/tonemap_bloom.frag",
            "final", "post/final_composite.frag",
            "fullscreen_vert", "common/fullscreen.vert"
    );

    private static final ShaderPackManager INSTANCE = new ShaderPackManager();

    private final Map<String, ShaderPack> packs = new LinkedHashMap<>();
    private ShaderPack builtinPack;
    private String lastError = null;

    public static ShaderPackManager get() {
        return INSTANCE;
    }

    private ShaderPackManager() {
        discoverPacks();
    }

    public synchronized void discoverPacks() {
        packs.clear();
        builtinPack = loadBuiltinPack();
        packs.put(BUILTIN_ID, builtinPack);

        try {
            Files.createDirectories(PipelineConfig.getShaderPacksDir());
            Files.createDirectories(PipelineConfig.getConfigShaderDir());
        } catch (IOException ignored) {
        }

        scanDirectoryForPacks(PipelineConfig.getShaderPacksDir());
        scanDirectoryForPacks(PipelineConfig.getConfigShaderDir());
    }

    private ShaderPack loadBuiltinPack() {
        try (InputStream in = ShaderPackManager.class.getResourceAsStream(BUILTIN_BASE + "pack.json")) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    return parsePackJson(BUILTIN_ID, root, ShaderPack.SourceType.BUILTIN, null, "");
                }
            }
        } catch (Exception e) {
            NvidiumShaderPipeline.LOGGER.warn("Failed to parse built-in pack.json, using default stage mapping", e);
        }

        return new ShaderPack(
                BUILTIN_ID,
                "Nvidium Frontier Default",
                "Built-in physically-based deferred shader pack with LabPBR, CSM shadows, GTAO, volumetric light shafts, Gerstner water, and AgX/ACES tonemapping.",
                "Nvidium Team",
                "2.0.0",
                ShaderPack.SourceType.BUILTIN,
                null,
                "",
                DEFAULT_STAGE_PATHS,
                defaultBuiltinOptions()
        );
    }

    private void scanDirectoryForPacks(Path parentDir) {
        if (parentDir == null || !Files.isDirectory(parentDir)) {
            return;
        }

        // Also check if config/nvidium_shaders itself has a direct pack.json or stage folders
        if (parentDir.equals(PipelineConfig.getConfigShaderDir()) && isPackFolder(parentDir)) {
            ShaderPack directPack = loadDirectoryPack("config:nvidium_shaders", "Config Custom Pack", parentDir);
            if (directPack != null) {
                packs.put(directPack.getId(), directPack);
            }
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentDir)) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    Path packRoot = resolveDirectoryPackRoot(entry);
                    if (packRoot != null) {
                        String id = "dir:" + fileName;
                        if (!packs.containsKey(id)) {
                            ShaderPack pack = loadDirectoryPack(id, fileName, packRoot);
                            if (pack != null) {
                                packs.put(pack.getId(), pack);
                            }
                        }
                    }
                } else if (fileName.toLowerCase(Locale.ROOT).endsWith(".zip") && Files.isRegularFile(entry)) {
                    String id = "zip:" + fileName;
                    if (!packs.containsKey(id)) {
                        ShaderPack pack = loadZipPack(id, fileName.substring(0, fileName.length() - 4), entry);
                        if (pack != null) {
                            packs.put(pack.getId(), pack);
                        }
                    }
                }
            }
        } catch (IOException e) {
            NvidiumShaderPipeline.LOGGER.warn("Failed to scan shader pack directory {}", parentDir, e);
        }
    }

    private Path resolveDirectoryPackRoot(Path dir) {
        if (isPackFolder(dir)) {
            return dir;
        }
        Path subShaders = dir.resolve("shaders");
        if (Files.isDirectory(subShaders) && isPackFolder(subShaders)) {
            return subShaders;
        }
        return null;
    }

    private boolean isPackFolder(Path dir) {
        if (Files.isRegularFile(dir.resolve("pack.json"))) {
            return true;
        }
        for (String rel : DEFAULT_STAGE_PATHS.values()) {
            if (Files.isRegularFile(dir.resolve(rel))) {
                return true;
            }
        }
        return false;
    }

    private ShaderPack loadDirectoryPack(String id, String fallbackName, Path packRoot) {
        Path manifest = packRoot.resolve("pack.json");
        if (Files.isRegularFile(manifest)) {
            try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                return parsePackJson(id, root, ShaderPack.SourceType.DIRECTORY, packRoot, "");
            } catch (Exception e) {
                NvidiumShaderPipeline.LOGGER.warn("Failed to read pack.json in {}", packRoot, e);
            }
        }
        return new ShaderPack(
                id,
                fallbackName,
                "Custom folder shader pack (" + packRoot.getFileName() + ")",
                "Custom",
                "1.0",
                ShaderPack.SourceType.DIRECTORY,
                packRoot,
                "",
                DEFAULT_STAGE_PATHS,
                builtinPack != null ? builtinPack.getOptions() : defaultBuiltinOptions()
        );
    }

    private ShaderPack loadZipPack(String id, String fallbackName, Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry packJsonEntry = null;
            String detectedPrefix = "";

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (ze.isDirectory()) continue;
                String name = ze.getName().replace('\\', '/');
                if (name.equals("pack.json") || name.endsWith("/pack.json")) {
                    packJsonEntry = ze;
                    int slash = name.lastIndexOf('/');
                    detectedPrefix = slash >= 0 ? name.substring(0, slash + 1) : "";
                    break;
                }
            }

            if (packJsonEntry != null) {
                try (InputStream in = zip.getInputStream(packJsonEntry);
                     Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    return parsePackJson(id, root, ShaderPack.SourceType.ZIP, zipPath, detectedPrefix);
                }
            }

            // Otherwise check if any stage shader exists inside the zip
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                if (ze.isDirectory()) continue;
                String name = ze.getName().replace('\\', '/');
                for (String stageRel : DEFAULT_STAGE_PATHS.values()) {
                    if (name.equals(stageRel) || name.endsWith("/" + stageRel)) {
                        int idx = name.indexOf(stageRel);
                        detectedPrefix = idx > 0 ? name.substring(0, idx) : "";
                        return new ShaderPack(
                                id,
                                fallbackName,
                                "Custom ZIP shader pack (" + zipPath.getFileName() + ")",
                                "Custom",
                                "1.0",
                                ShaderPack.SourceType.ZIP,
                                zipPath,
                                detectedPrefix,
                                DEFAULT_STAGE_PATHS,
                                builtinPack != null ? builtinPack.getOptions() : defaultBuiltinOptions()
                        );
                    }
                }
            }
        } catch (Exception e) {
            NvidiumShaderPipeline.LOGGER.warn("Failed to inspect shader pack zip {}", zipPath, e);
        }
        return null;
    }

    private ShaderPack parsePackJson(
            String id,
            JsonObject root,
            ShaderPack.SourceType sourceType,
            Path rootPath,
            String zipPrefix) {
        String name = getString(root, "name", id);
        String description = getString(root, "description", "Custom Nvidium Shader Pack");
        String author = getString(root, "author", "Unknown");
        String version = getString(root, "version", "1.0.0");

        Map<String, String> stages = new LinkedHashMap<>(DEFAULT_STAGE_PATHS);
        if (root.has("stages") && root.get("stages").isJsonObject()) {
            JsonObject stagesObj = root.getAsJsonObject("stages");
            for (Map.Entry<String, JsonElement> e : stagesObj.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    stages.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }

        List<ShaderPack.PackOption> options = new ArrayList<>();
        if (root.has("options") && root.get("options").isJsonArray()) {
            JsonArray arr = root.getAsJsonArray("options");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                ShaderPack.PackOption opt = new ShaderPack.PackOption();
                opt.id = getString(o, "id", "OPTION_" + options.size());
                opt.name = getString(o, "name", opt.id);
                opt.description = getString(o, "description", "");
                opt.category = getString(o, "category", "General");
                opt.type = getString(o, "type", "float").toLowerCase(Locale.ROOT);
                opt.defaultValue = getFloat(o, "default", 0.0f);
                opt.min = getFloat(o, "min", 0.0f);
                opt.max = getFloat(o, "max", 1.0f);
                opt.step = getFloat(o, "step", 0.05f);
                opt.slot = o.has("slot") ? o.get("slot").getAsInt() : -1;
                opt.defineMacro = o.has("defineMacro") && o.get("defineMacro").getAsBoolean();
                if (o.has("choices") && o.get("choices").isJsonArray()) {
                    for (JsonElement ch : o.getAsJsonArray("choices")) {
                        opt.choices.add(ch.getAsString());
                    }
                }
                options.add(opt);
            }
        } else if (builtinPack != null) {
            options.addAll(builtinPack.getOptions());
        } else {
            options.addAll(defaultBuiltinOptions());
        }

        return new ShaderPack(id, name, description, author, version, sourceType, rootPath, zipPrefix, stages, options);
    }

    private static String getString(JsonObject obj, String key, String fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : fallback;
    }

    private static float getFloat(JsonObject obj, String key, float fallback) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsFloat() : fallback;
    }

    public static List<ShaderPack.PackOption> defaultBuiltinOptions() {
        List<ShaderPack.PackOption> list = new ArrayList<>();

        list.add(makeSlider("SUNLIGHT_INTENSITY", "Sunlight Intensity", "Direct sun/moon directional illuminance multiplier", "Lighting", 1.0f, 0.2f, 2.5f, 0.05f, 0));
        list.add(makeSlider("AMBIENT_INTENSITY", "Sky Ambient Intensity", "Hemispherical sky & bounce ambient brightness", "Lighting", 1.0f, 0.25f, 2.5f, 0.05f, 1));
        list.add(makeSlider("EMISSIVE_STRENGTH", "Emissive Block Glow", "HDR luminance boost for torches, lanterns, lava, sculk, and ores", "Lighting", 1.25f, 0.2f, 3.5f, 0.05f, 2));
        list.add(makeBool("HANDHELD_DYNAMIC_LIGHT", "Handheld Dynamic Light", "Casts colored point light from held torches, lanterns, lava buckets, etc.", "Lighting", true, 3));

        list.add(makeEnum("CLOUD_MODE", "Clouds Style", "Volumetric FBM clouds vs planar or disabled", "Atmosphere", 2, List.of("Off", "2D Cirrus", "Volumetric Multi-Layer"), 4));
        list.add(makeSlider("CLOUD_COVERAGE", "Cloud Coverage", "Amount of sky covered by dynamic clouds", "Atmosphere", 0.52f, 0.0f, 1.0f, 0.05f, 5));
        list.add(makeSlider("GODRAYS_STRENGTH", "Volumetric Godrays", "Intensity of raymarched crepuscular sun/moon light shafts", "Atmosphere", 1.0f, 0.0f, 2.5f, 0.05f, 6));
        list.add(makeSlider("ATMOS_HAZINESS", "Atmospheric Haziness", "Aerial perspective and Mie scattering turbidity", "Atmosphere", 1.0f, 0.0f, 2.5f, 0.05f, 7));

        list.add(makeSlider("WATER_WAVE_HEIGHT", "Water Wave Amplitude", "Gerstner surface wave height and normal perturbation", "Water & Materials", 1.0f, 0.0f, 2.5f, 0.05f, 8));
        list.add(makeSlider("WATER_CLARITY", "Water Clarity", "Controls underwater absorption depth and refraction visibility", "Water & Materials", 1.0f, 0.2f, 2.5f, 0.05f, 9));
        list.add(makeBool("WATER_CAUSTICS", "Underwater Caustics", "Animated refractive light patterns on underwater surfaces", "Water & Materials", true, 10));
        list.add(makeBool("RAIN_PUDDLES", "Rain Wetness & Puddles", "Dynamic reflective puddles and darkening on outdoor blocks during rain", "Water & Materials", true, 11));

        list.add(makeBool("SUBSURFACE_SCATTERING", "Subsurface Scattering", "Back-lit foliage, leaves, snow, and ice translucency", "Water & Materials", true, 12));
        list.add(makeSlider("NORMAL_BUMP_STRENGTH", "Surface Relief / Bump", "Strength of LabPBR and procedural micro-surface normals", "Water & Materials", 1.0f, 0.0f, 2.0f, 0.05f, 13));
        list.add(makeSlider("WIND_SPEED", "Foliage Wind Speed", "Speed and amplitude of waving grass, crops, vines, and leaves", "Water & Materials", 1.0f, 0.0f, 2.5f, 0.05f, 14));
        list.add(makeSlider("SHADOW_SOFTNESS", "Shadow Penumbra Softness", "Vogel-disk PCF filter radius for sun/moon shadows", "Lighting", 1.0f, 0.25f, 2.5f, 0.05f, 15));

        return list;
    }

    private static ShaderPack.PackOption makeSlider(String id, String name, String desc, String cat, float def, float min, float max, float step, int slot) {
        ShaderPack.PackOption o = new ShaderPack.PackOption();
        o.id = id;
        o.name = name;
        o.description = desc;
        o.category = cat;
        o.type = "float";
        o.defaultValue = def;
        o.min = min;
        o.max = max;
        o.step = step;
        o.slot = slot;
        return o;
    }

    private static ShaderPack.PackOption makeBool(String id, String name, String desc, String cat, boolean def, int slot) {
        ShaderPack.PackOption o = new ShaderPack.PackOption();
        o.id = id;
        o.name = name;
        o.description = desc;
        o.category = cat;
        o.type = "bool";
        o.defaultValue = def ? 1.0f : 0.0f;
        o.min = 0.0f;
        o.max = 1.0f;
        o.step = 1.0f;
        o.slot = slot;
        return o;
    }

    private static ShaderPack.PackOption makeEnum(String id, String name, String desc, String cat, int def, List<String> choices, int slot) {
        ShaderPack.PackOption o = new ShaderPack.PackOption();
        o.id = id;
        o.name = name;
        o.description = desc;
        o.category = cat;
        o.type = "enum";
        o.defaultValue = def;
        o.min = 0;
        o.max = Math.max(0, choices.size() - 1);
        o.step = 1.0f;
        o.choices = new ArrayList<>(choices);
        o.slot = slot;
        return o;
    }

    public synchronized List<ShaderPack> getAvailablePacks() {
        return new ArrayList<>(packs.values());
    }

    public synchronized ShaderPack getBuiltinPack() {
        if (builtinPack == null) {
            builtinPack = loadBuiltinPack();
        }
        return builtinPack;
    }

    public synchronized ShaderPack getActivePack() {
        String id = PipelineConfig.get().activePack;
        ShaderPack pack = packs.get(id);
        if (pack == null) {
            pack = getBuiltinPack();
        }
        return pack;
    }

    public synchronized void recordError(String context, String details) {
        this.lastError = context + ": " + details;
        NvidiumShaderPipeline.LOGGER.error("[Nvidium Shaders] {} - {}", context, details);
        try {
            Path logFile = PipelineConfig.getConfigShaderDir().resolve("last_error.log");
            Files.createDirectories(logFile.getParent());
            String content = "Timestamp: " + LocalDateTime.now() + "\n"
                    + "Active Pack: " + PipelineConfig.get().activePack + "\n"
                    + "Context: " + context + "\n\n"
                    + details + "\n";
            Files.writeString(logFile, content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public synchronized void clearError() {
        this.lastError = null;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    /**
     * Exports a complete, editable copy of the built-in shader pack to
     * {@code shaderpacks/nvidium-custom-template/} so pack authors can immediately customize it.
     */
    public synchronized Path exportTemplatePack() throws IOException {
        Path targetDir = PipelineConfig.getShaderPacksDir().resolve("nvidium-custom-template");
        Files.createDirectories(targetDir);

        String[] filesToCopy = new String[] {
                "pack.json",
                "common/scene_uniforms.glsl",
                "common/options.glsl",
                "common/materials.glsl",
                "common/pbr_brdf.glsl",
                "common/atmosphere.glsl",
                "common/shadows.glsl",
                "common/post_tonemap.glsl",
                "common/fullscreen.vert",
                "shadow/shadow.frag",
                "gbuffer/terrain_gbuffer.frag",
                "deferred/deferred_lighting.frag",
                "deferred/volumetric_fog.frag",
                "water/water_surface.frag",
                "post/tonemap_bloom.frag",
                "post/final_composite.frag"
        };

        for (String rel : filesToCopy) {
            try (InputStream in = ShaderPackManager.class.getResourceAsStream(BUILTIN_BASE + rel)) {
                if (in != null) {
                    Path out = targetDir.resolve(rel);
                    Files.createDirectories(out.getParent());
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (rel.equals("pack.json")) {
                        text = text.replace("\"Nvidium Frontier Default\"", "\"Nvidium Custom Pack (Template)\"");
                    }
                    Files.writeString(out, text, StandardCharsets.UTF_8);
                }
            }
        }

        discoverPacks();
        return targetDir;
    }
}
