package net.nvidium.shaders.pack;

import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.config.StatisticsLoggingLevel;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.sodiumCompat.NvidiumCompactChunkVertex;
import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.config.PipelineConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads and preprocesses GLSL shader source files from the active {@link ShaderPack}
 * (built-in classpath resources, filesystem directory, or .zip archive), resolving
 * {@code #include} and {@code #import} directives with automatic fallback to built-in
 * shader libraries.
 */
public final class ShaderSourceLoader {
    private static final String BUILTIN_SHADERS_ROOT = "/assets/nvidium_shaders/shaders/";
    private static final String NVIDIUM_SHADERS_ROOT = "/assets/nvidium/shaders/";

    private ShaderSourceLoader() {}

    /**
     * Loads a stage shader for the specified pack, preprocessing includes and option defines.
     * If the pack does not provide the stage file, falls back to the built-in pack's stage file.
     */
    public static String loadStageSource(ShaderPack pack, String stageKey, boolean injectNvidiumDefines) {
        String defaultRel = ShaderPackManager.DEFAULT_STAGE_PATHS.getOrDefault(stageKey, stageKey);
        String relPath = pack != null ? pack.getStagePath(stageKey, defaultRel) : defaultRel;
        relPath = normalizeRelPath(relPath);

        String raw = readRawFile(pack, relPath);
        ShaderPack effectivePack = pack;
        if (raw == null && pack != null && !pack.isBuiltin()) {
            NvidiumShaderPipeline.LOGGER.info("Stage '{}' ({}) not found in pack '{}', using built-in fallback",
                    stageKey, relPath, pack.getName());
            effectivePack = ShaderPackManager.get().getBuiltinPack();
            relPath = normalizeRelPath(effectivePack.getStagePath(stageKey, defaultRel));
            raw = readRawFile(effectivePack, relPath);
        }

        if (raw == null) {
            throw new IllegalStateException("Missing shader source for stage '" + stageKey + "' (" + relPath + ")");
        }

        Set<String> visited = new HashSet<>();
        visited.add(relPath);
        String expanded = resolveIncludes(effectivePack, relPath, raw, visited);
        return injectDefinesAfterVersion(expanded, pack != null ? pack : effectivePack, injectNvidiumDefines);
    }

    /**
     * Loads a stage shader strictly from the built-in pack (used when a custom pack stage fails to compile).
     */
    public static String loadBuiltinStageSource(String stageKey, boolean injectNvidiumDefines) {
        ShaderPack builtin = ShaderPackManager.get().getBuiltinPack();
        return loadStageSource(builtin, stageKey, injectNvidiumDefines);
    }

    /**
     * Loads a Nvidium core shader (e.g., {@code terrain/task.glsl} or {@code terrain/mesh.glsl})
     * from {@code /assets/nvidium/shaders/} with Nvidium's preprocessor defines and `#import` resolution.
     */
    public static String loadNvidiumCoreShader(String nvidiumRelativePath) {
        String norm = normalizeRelPath(nvidiumRelativePath);
        String raw = readClasspathResource(NVIDIUM_SHADERS_ROOT + norm);
        if (raw == null) {
            throw new IllegalStateException("Failed to load Nvidium core shader: " + norm);
        }
        Set<String> visited = new HashSet<>();
        visited.add("nvidium:" + norm);
        String expanded = resolveNvidiumImports(norm, raw, visited);
        return injectDefinesAfterVersion(expanded, ShaderPackManager.get().getActivePack(), true);
    }

    private static String resolveIncludes(ShaderPack pack, String currentRelPath, String source, Set<String> visited) {
        String parentDir = parentDirOf(currentRelPath);
        StringBuilder out = new StringBuilder(source.length() + 512);
        String[] lines = source.replace("\r\n", "\n").split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#include ") || trimmed.startsWith("#import ")) {
                String target = extractDirectiveTarget(trimmed);
                if (target != null && !target.isEmpty()) {
                    if (target.startsWith("nvidium:")) {
                        String nvRel = normalizeRelPath(target.substring("nvidium:".length()));
                        String key = "nvidium:" + nvRel;
                        if (!visited.contains(key)) {
                            visited.add(key);
                            String nvRaw = readClasspathResource(NVIDIUM_SHADERS_ROOT + nvRel);
                            if (nvRaw != null) {
                                out.append("// --- begin #import <").append(target).append("> ---\n");
                                out.append(resolveNvidiumImports(nvRel, nvRaw, visited)).append("\n");
                                out.append("// --- end #import <").append(target).append("> ---\n");
                            }
                        }
                        continue;
                    }

                    if (target.startsWith("nvidium_shaders:")) {
                        target = target.substring("nvidium_shaders:".length());
                    }

                    String resolvedRel = target.startsWith("/")
                            ? normalizeRelPath(target.substring(1))
                            : normalizeRelPath(parentDir.isEmpty() ? target : parentDir + "/" + target);

                    if (!visited.contains(resolvedRel)) {
                        visited.add(resolvedRel);
                        String incRaw = readRawFile(pack, resolvedRel);
                        if (incRaw == null && pack != null && !pack.isBuiltin()) {
                            // Fall back to built-in common libraries so custom packs can #include "../common/..."
                            incRaw = readRawFile(ShaderPackManager.get().getBuiltinPack(), resolvedRel);
                        }
                        if (incRaw != null) {
                            out.append("// --- begin #include \"").append(resolvedRel).append("\" ---\n");
                            out.append(resolveIncludes(pack, resolvedRel, incRaw, visited)).append("\n");
                            out.append("// --- end #include \"").append(resolvedRel).append("\" ---\n");
                        } else {
                            NvidiumShaderPipeline.LOGGER.warn("Unresolved #include '{}' in '{}'", resolvedRel, currentRelPath);
                        }
                    }
                    continue;
                }
            }
            out.append(line).append("\n");
        }
        return out.toString();
    }

    private static String resolveNvidiumImports(String currentRelPath, String source, Set<String> visited) {
        String parentDir = parentDirOf(currentRelPath);
        StringBuilder out = new StringBuilder(source.length() + 512);
        String[] lines = source.replace("\r\n", "\n").split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#import ") || trimmed.startsWith("#include ")) {
                String target = extractDirectiveTarget(trimmed);
                if (target != null && !target.isEmpty()) {
                    String nvRel;
                    if (target.startsWith("nvidium:")) {
                        nvRel = normalizeRelPath(target.substring("nvidium:".length()));
                    } else {
                        nvRel = normalizeRelPath(parentDir.isEmpty() ? target : parentDir + "/" + target);
                    }
                    String key = "nvidium:" + nvRel;
                    if (!visited.contains(key)) {
                        visited.add(key);
                        String nvRaw = readClasspathResource(NVIDIUM_SHADERS_ROOT + nvRel);
                        if (nvRaw != null) {
                            out.append(resolveNvidiumImports(nvRel, nvRaw, visited)).append("\n");
                        }
                    }
                    continue;
                }
            }
            out.append(line).append("\n");
        }
        return out.toString();
    }

    private static String injectDefinesAfterVersion(String source, ShaderPack pack, boolean injectNvidiumDefines) {
        StringBuilder defines = new StringBuilder();
        PipelineConfig cfg = PipelineConfig.get();

        if (injectNvidiumDefines) {
            try {
                if (Nvidium.config != null) {
                    if (Nvidium.config.render_fog) {
                        defines.append("#define RENDER_FOG\n");
                    }
                    for (int i = 1; i <= Nvidium.config.statistics_level.ordinal(); i++) {
                        defines.append("#define STATISTICS_").append(StatisticsLoggingLevel.values()[i].name()).append("\n");
                    }
                    for (int i = 1; i <= Nvidium.config.translucency_sorting_level.ordinal(); i++) {
                        defines.append("#define TRANSLUCENCY_SORTING_").append(TranslucencySortingLevel.values()[i].name()).append("\n");
                    }
                } else {
                    defines.append("#define RENDER_FOG\n");
                }
            } catch (Throwable t) {
                defines.append("#define RENDER_FOG\n");
            }
            defines.append("#define TEXTURE_MAX_SCALE ").append(NvidiumCompactChunkVertex.TEXTURE_MAX_VALUE).append("\n");
        }

        if (cfg.wavingTerrain) {
            defines.append("#define NS_WAVING_TERRAIN 1\n");
        }

        if (pack != null) {
            for (ShaderPack.PackOption opt : pack.getOptions()) {
                if (opt.defineMacro && opt.id != null) {
                    float val = cfg.getPackOptionValue(pack.getId(), opt.id, opt.defaultValue);
                    String safeId = opt.id.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
                    if ("bool".equals(opt.type) || "int".equals(opt.type) || "enum".equals(opt.type)) {
                        defines.append("#define NS_OPT_").append(safeId).append(" ").append(Math.round(val)).append("\n");
                    } else {
                        defines.append(String.format(Locale.ROOT, "#define NS_OPT_%s %.4f\n", safeId, val));
                    }
                }
            }
        }

        String normalized = source.replace("\r\n", "\n");
        int versionIdx = normalized.indexOf("#version");
        if (versionIdx >= 0) {
            int lineEnd = normalized.indexOf('\n', versionIdx);
            if (lineEnd >= 0) {
                return normalized.substring(0, lineEnd + 1)
                        + defines
                        + normalized.substring(lineEnd + 1);
            }
        }
        return "#version 460 core\n" + defines + normalized;
    }

    private static String readRawFile(ShaderPack pack, String relPath) {
        String norm = normalizeRelPath(relPath);
        if (pack == null || pack.isBuiltin()) {
            return readClasspathResource(BUILTIN_SHADERS_ROOT + norm);
        }

        if (pack.getSourceType() == ShaderPack.SourceType.DIRECTORY) {
            Path root = pack.getRootPath();
            if (root != null) {
                Path candidate = root.resolve(norm).normalize();
                if (candidate.startsWith(root.normalize()) && Files.isRegularFile(candidate)) {
                    try {
                        return Files.readString(candidate, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        NvidiumShaderPipeline.LOGGER.warn("Failed to read shader file {}", candidate, e);
                    }
                }
            }
            return null;
        }

        if (pack.getSourceType() == ShaderPack.SourceType.ZIP) {
            Path zipPath = pack.getRootPath();
            if (zipPath != null && Files.isRegularFile(zipPath)) {
                try (ZipFile zip = new ZipFile(zipPath.toFile())) {
                    String entryName = pack.getZipSubPrefix() + norm;
                    ZipEntry entry = zip.getEntry(entryName);
                    if (entry == null && !pack.getZipSubPrefix().isEmpty()) {
                        entry = zip.getEntry(norm);
                    }
                    if (entry == null) {
                        entry = zip.getEntry("shaders/" + norm);
                    }
                    if (entry != null && !entry.isDirectory()) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                } catch (IOException e) {
                    NvidiumShaderPipeline.LOGGER.warn("Failed to read zip shader entry {} from {}", norm, zipPath, e);
                }
            }
            return null;
        }

        return null;
    }

    private static String readClasspathResource(String resourcePath) {
        if (resourcePath != null && resourcePath.startsWith(NVIDIUM_SHADERS_ROOT)) {
            String rel = resourcePath.substring(NVIDIUM_SHADERS_ROOT.length());
            try {
                String viaApi = me.cortex.nvidium.api0.NvidiumAPI.getCoreShaderSource(rel);
                if (viaApi != null) {
                    return viaApi;
                }
            } catch (Throwable ignored) {
            }
            try (InputStream in = Nvidium.class.getResourceAsStream(resourcePath)) {
                if (in != null) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
            }
        }
        try (InputStream in = ShaderSourceLoader.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
        try (InputStream in = Nvidium.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String extractDirectiveTarget(String trimmedLine) {
        int q1 = trimmedLine.indexOf('"');
        if (q1 >= 0) {
            int q2 = trimmedLine.indexOf('"', q1 + 1);
            if (q2 > q1) {
                return trimmedLine.substring(q1 + 1, q2).trim();
            }
        }
        int a1 = trimmedLine.indexOf('<');
        if (a1 >= 0) {
            int a2 = trimmedLine.indexOf('>', a1 + 1);
            if (a2 > a1) {
                return trimmedLine.substring(a1 + 1, a2).trim();
            }
        }
        return null;
    }

    private static String parentDirOf(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash >= 0 ? relPath.substring(0, slash) : "";
    }

    public static String normalizeRelPath(String path) {
        String p = path.replace('\\', '/');
        String[] parts = p.split("/");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else {
                stack.addLast(part);
            }
        }
        return String.join("/", stack);
    }
}
