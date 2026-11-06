package net.nvidium.shaders.pack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Describes a loaded Nvidium shader pack (built-in, folder, or .zip archive).
 */
public class ShaderPack {

    public enum SourceType {
        BUILTIN,
        DIRECTORY,
        ZIP
    }

    public static class PackOption {
        public String id;
        public String name;
        public String description = "";
        public String category = "General";
        /** "bool", "float", "int", or "enum" */
        public String type = "float";
        public float defaultValue = 0.0f;
        public float min = 0.0f;
        public float max = 1.0f;
        public float step = 0.05f;
        public List<String> choices = new ArrayList<>();
        /** Index 0..63 into u_PackOptions[16] (vec4 array in binding = 4) */
        public int slot = -1;
        /** Whether changing this option also injects a preprocessor macro #define NS_OPT_<ID> */
        public boolean defineMacro = false;

        public String displayName() {
            return (name != null && !name.isBlank()) ? name : id;
        }
    }

    private final String id;
    private final String name;
    private final String description;
    private final String author;
    private final String version;
    private final SourceType sourceType;
    private final Path rootPath;
    private final String zipSubPrefix;
    private final Map<String, String> stages;
    private final List<PackOption> options;

    public ShaderPack(
            String id,
            String name,
            String description,
            String author,
            String version,
            SourceType sourceType,
            Path rootPath,
            String zipSubPrefix,
            Map<String, String> stages,
            List<PackOption> options) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.author = author;
        this.version = version;
        this.sourceType = sourceType;
        this.rootPath = rootPath;
        this.zipSubPrefix = zipSubPrefix != null ? zipSubPrefix : "";
        this.stages = new LinkedHashMap<>(stages);
        this.options = new ArrayList<>(options != null ? options : List.of());

        // Assign sequential UBO slots (0..63) to options that did not specify one
        int nextSlot = 0;
        for (PackOption opt : this.options) {
            if (opt.slot < 0) {
                opt.slot = Math.min(63, nextSlot++);
            } else {
                nextSlot = Math.max(nextSlot, opt.slot + 1);
            }
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getAuthor() {
        return author;
    }

    public String getVersion() {
        return version;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public boolean isBuiltin() {
        return sourceType == SourceType.BUILTIN;
    }

    public Path getRootPath() {
        return rootPath;
    }

    public String getZipSubPrefix() {
        return zipSubPrefix;
    }

    public Map<String, String> getStages() {
        return Collections.unmodifiableMap(stages);
    }

    public String getStagePath(String stageKey, String fallbackRelativePath) {
        return stages.getOrDefault(stageKey, fallbackRelativePath);
    }

    public List<PackOption> getOptions() {
        return Collections.unmodifiableList(options);
    }
}
