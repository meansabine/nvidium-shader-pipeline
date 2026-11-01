package net.nvidium.shaders.pipeline.gl;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.nvidium.shaders.NvidiumShaderPipeline;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Modern OpenGL shader program supporting standard pipeline stages
 * as well as NVIDIA Mesh & Task shaders (GL_NV_mesh_shader).
 * Includes recursive pre-processing of #include and #import directives.
 */
public class ShaderProgram {
    public static final int GL_MESH_SHADER_NV = 0x864E;
    public static final int GL_TASK_SHADER_NV = 0x864F;

    private int programId;
    private final Map<Integer, Integer> attachedShaders = new HashMap<>();
    private final String name;

    public ShaderProgram(String name) {
        this.name = name;
        this.programId = GL20C.glCreateProgram();
    }

    public ShaderProgram attachStage(int stageType, ResourceLocation shaderLocation) {
        String source = loadShaderSource(shaderLocation);
        if (source == null) {
            throw new RuntimeException("Failed to read shader file: " + shaderLocation);
        }

        int shaderId = GL20C.glCreateShader(stageType);
        GL20C.glShaderSource(shaderId, source);
        GL20C.glCompileShader(shaderId);

        int compiled = GL20C.glGetShaderi(shaderId, GL20C.GL_COMPILE_STATUS);
        if (compiled == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shaderId);
            GL20C.glDeleteShader(shaderId);
            throw new RuntimeException("Shader compilation failed for [" + name + "] (" + shaderLocation + "):\n" + log);
        }

        GL20C.glAttachShader(this.programId, shaderId);
        attachedShaders.put(stageType, shaderId);
        return this;
    }

    public ShaderProgram link() {
        GL20C.glLinkProgram(programId);
        int linked = GL20C.glGetProgrami(programId, GL20C.GL_LINK_STATUS);
        if (linked == GL11C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(programId);
            throw new RuntimeException("Shader link failed for [" + name + "]:\n" + log);
        }

        // Clean up attached shader objects once linked
        for (int shaderId : attachedShaders.values()) {
            GL20C.glDetachShader(programId, shaderId);
            GL20C.glDeleteShader(shaderId);
        }
        attachedShaders.clear();
        return this;
    }

    public void bind() {
        GL20C.glUseProgram(programId);
    }

    public static void unbind() {
        GL20C.glUseProgram(0);
    }

    public int getUniformLocation(String uniformName) {
        return GL20C.glGetUniformLocation(programId, uniformName);
    }

    public void setUniform1i(String name, int value) {
        int loc = getUniformLocation(name);
        if (loc != -1) GL20C.glUniform1i(loc, value);
    }

    public void setUniform1f(String name, float value) {
        int loc = getUniformLocation(name);
        if (loc != -1) GL20C.glUniform1f(loc, value);
    }

    public void destroy() {
        if (programId != 0) {
            GL20C.glDeleteProgram(programId);
            programId = 0;
        }
    }

    public int getProgramId() {
        return programId;
    }

    public String getName() {
        return name;
    }

    public static String loadShaderSource(ResourceLocation location) {
        return loadShaderSource(location, new HashSet<>());
    }

    private static String loadShaderSource(ResourceLocation location, Set<ResourceLocation> visited) {
        if (!visited.add(location)) {
            return "// Cycle omitted: " + location + "\n";
        }

        try {
            Optional<Resource> res = resolveResource(location);
            if (res.isPresent()) {
                StringBuilder builder = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.get().open(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("#include") || trimmed.startsWith("#import")) {
                            int start = trimmed.indexOf('<');
                            int end = trimmed.indexOf('>');
                            if (start == -1 || end == -1) {
                                start = trimmed.indexOf('"');
                                end = trimmed.lastIndexOf('"');
                            }
                            if (start != -1 && end != -1 && end > start) {
                                String includeTarget = trimmed.substring(start + 1, end).trim();
                                ResourceLocation incLoc = parseIncludeLocation(location, includeTarget);
                                String includedContent = loadShaderSource(incLoc, visited);
                                if (includedContent != null) {
                                    builder.append("// --- Begin Included: ").append(includeTarget).append(" ---\n");
                                    builder.append(includedContent).append("\n");
                                    builder.append("// --- End Included: ").append(includeTarget).append(" ---\n");
                                    continue;
                                } else {
                                    NvidiumShaderPipeline.LOGGER.warn("Failed to resolve shader include: {}", includeTarget);
                                }
                            }
                        }
                        builder.append(line).append("\n");
                    }
                }
                return builder.toString();
            } else {
                NvidiumShaderPipeline.LOGGER.error("Shader resource not found: {}", location);
            }
        } catch (Exception e) {
            NvidiumShaderPipeline.LOGGER.error("Error reading shader: {}", location, e);
        }
        return null;
    }

    private static Optional<Resource> resolveResource(ResourceLocation loc) {
        var rm = Minecraft.getInstance().getResourceManager();
        var direct = rm.getResource(loc);
        if (direct.isPresent()) return direct;

        // Try prefixing "shaders/" if omitted
        if (!loc.getPath().startsWith("shaders/")) {
            var withShaders = rm.getResource(ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), "shaders/" + loc.getPath()));
            if (withShaders.isPresent()) return withShaders;
        }
        return Optional.empty();
    }

    private static ResourceLocation parseIncludeLocation(ResourceLocation parent, String includePath) {
        String namespace = parent.getNamespace();
        String path = includePath;
        if (includePath.contains(":")) {
            String[] parts = includePath.split(":", 2);
            namespace = parts[0];
            path = parts[1];
        } else {
            String parentPath = parent.getPath();
            int slash = parentPath.lastIndexOf('/');
            String dir = slash != -1 ? parentPath.substring(0, slash + 1) : "";
            path = dir + path;
        }
        path = normalizePath(path);
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static String normalizePath(String path) {
        String[] parts = path.split("/");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!list.isEmpty()) {
                    list.remove(list.size() - 1);
                }
            } else {
                list.add(p);
            }
        }
        return String.join("/", list);
    }
}
