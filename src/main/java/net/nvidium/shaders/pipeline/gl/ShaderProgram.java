package net.nvidium.shaders.pipeline.gl;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.nvidium.shaders.NvidiumShaderPipeline;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Modern OpenGL shader program supporting standard pipeline stages
 * as well as NVIDIA Mesh & Task shaders (GL_NV_mesh_shader).
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

    private static String loadShaderSource(ResourceLocation location) {
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(location);
            if (res.isPresent()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(res.get().open(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            }
        } catch (Exception e) {
            NvidiumShaderPipeline.LOGGER.error("Error loading shader resource: {}", location, e);
        }
        return null;
    }
}
