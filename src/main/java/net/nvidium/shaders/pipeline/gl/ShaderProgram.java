package net.nvidium.shaders.pipeline.gl;

import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.pack.ShaderPack;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pack.ShaderSourceLoader;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenGL shader program wrapper for fullscreen & compute passes with pack-aware loading
 * and automatic built-in stage fallback on compilation errors.
 */
public class ShaderProgram {
    private final int programId;
    private final String debugName;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    private ShaderProgram(int programId, String debugName) {
        this.programId = programId;
        this.debugName = debugName;
    }

    /**
     * Compiles a fullscreen pass shader (common fullscreen.vert + stage fragment shader)
     * from the active {@link ShaderPack}, falling back to the built-in stage if custom
     * compilation fails.
     */
    public static ShaderProgram createFullscreenStage(ShaderPack pack, String stageKey) {
        String vertSrc = ShaderSourceLoader.loadStageSource(pack, "fullscreen_vert", false);
        try {
            String fragSrc = ShaderSourceLoader.loadStageSource(pack, stageKey, false);
            return compileVertFrag(stageKey, vertSrc, fragSrc);
        } catch (Exception e) {
            ShaderPackManager.get().recordError("Fullscreen stage '" + stageKey + "' failed", e.getMessage());
            if (pack != null && !pack.isBuiltin()) {
                NvidiumShaderPipeline.LOGGER.warn("Falling back to built-in stage '{}' after compilation error in pack '{}'",
                        stageKey, pack.getName());
                String builtinVert = ShaderSourceLoader.loadBuiltinStageSource("fullscreen_vert", false);
                String builtinFrag = ShaderSourceLoader.loadBuiltinStageSource(stageKey, false);
                return compileVertFrag(stageKey + " (builtin-fallback)", builtinVert, builtinFrag);
            }
            throw e;
        }
    }

    public static ShaderProgram compileVertFrag(String debugName, String vertSource, String fragSource) {
        int program = GL20C.glCreateProgram();
        int vert = 0;
        int frag = 0;
        try {
            vert = compileShader(GL20C.GL_VERTEX_SHADER, vertSource, debugName + ".vert");
            frag = compileShader(GL20C.GL_FRAGMENT_SHADER, fragSource, debugName + ".frag");

            GL20C.glAttachShader(program, vert);
            GL20C.glAttachShader(program, frag);
            GL20C.glLinkProgram(program);

            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
                String log = GL20C.glGetProgramInfoLog(program);
                throw new RuntimeException("Shader link failure in [" + debugName + "]:\n" + log);
            }
            return new ShaderProgram(program, debugName);
        } catch (RuntimeException ex) {
            if (program != 0) {
                GL20C.glDeleteProgram(program);
            }
            throw ex;
        } finally {
            if (vert != 0) {
                GL20C.glDetachShader(program, vert);
                GL20C.glDeleteShader(vert);
            }
            if (frag != 0) {
                GL20C.glDetachShader(program, frag);
                GL20C.glDeleteShader(frag);
            }
        }
    }

    public static ShaderProgram createCompute(String debugName, String compSource) {
        int program = GL20C.glCreateProgram();
        int comp = 0;
        try {
            comp = compileShader(GL43C.GL_COMPUTE_SHADER, compSource, debugName + ".comp");
            GL20C.glAttachShader(program, comp);
            GL20C.glLinkProgram(program);

            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
                String log = GL20C.glGetProgramInfoLog(program);
                throw new RuntimeException("Compute link failure in [" + debugName + "]:\n" + log);
            }
            return new ShaderProgram(program, debugName);
        } catch (RuntimeException ex) {
            if (program != 0) {
                GL20C.glDeleteProgram(program);
            }
            throw ex;
        } finally {
            if (comp != 0) {
                GL20C.glDetachShader(program, comp);
                GL20C.glDeleteShader(comp);
            }
        }
    }

    private static int compileShader(int type, String source, String label) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);

        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new RuntimeException("GLSL compile error in [" + label + "]:\n" + log);
        }
        return shader;
    }

    public void bind() {
        GL20C.glUseProgram(programId);
    }

    public void unbind() {
        GL20C.glUseProgram(0);
    }

    public int getUniformLocation(String name) {
        return uniformLocations.computeIfAbsent(name, n -> GL20C.glGetUniformLocation(programId, n));
    }

    public void setUniform1i(String name, int val) {
        int loc = getUniformLocation(name);
        if (loc != -1) {
            GL20C.glUniform1i(loc, val);
        }
    }

    public void setUniform1f(String name, float val) {
        int loc = getUniformLocation(name);
        if (loc != -1) {
            GL20C.glUniform1f(loc, val);
        }
    }

    public void setUniform2f(String name, float x, float y) {
        int loc = getUniformLocation(name);
        if (loc != -1) {
            GL20C.glUniform2f(loc, x, y);
        }
    }

    public void setUniform3f(String name, float x, float y, float z) {
        int loc = getUniformLocation(name);
        if (loc != -1) {
            GL20C.glUniform3f(loc, x, y, z);
        }
    }

    public void setUniform4f(String name, float x, float y, float z, float w) {
        int loc = getUniformLocation(name);
        if (loc != -1) {
            GL20C.glUniform4f(loc, x, y, z, w);
        }
    }

    public int getProgramId() {
        return programId;
    }

    public String getDebugName() {
        return debugName;
    }

    public void delete() {
        if (programId != 0) {
            GL20C.glDeleteProgram(programId);
        }
    }
}
