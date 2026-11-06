package net.nvidium.shaders.pipeline.uniforms;

import net.nvidium.shaders.config.PipelineConfig;
import net.nvidium.shaders.pack.ShaderPack;
import net.nvidium.shaders.pack.ShaderPackManager;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.NVShaderBufferLoad;
import org.lwjgl.opengl.NVUniformBufferUnifiedMemory;
import org.lwjgl.opengl.NVVertexBufferUnifiedMemory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Manages the std140 UBO at binding point 4 containing 64 pack option floats
 * ({@code vec4 u_PackOptions[16]}), supporting both standard UBO binding and
 * NVIDIA Unified Memory UBO binding ({@code GL_UNIFORM_BUFFER_UNIFIED_NV}).
 */
public class PackOptionsUniforms {
    public static final int BINDING_POINT = 4;
    public static final int FLOAT_COUNT = 64;
    public static final int BUFFER_SIZE = FLOAT_COUNT * Float.BYTES; // 256 bytes

    private int uboId;
    private long gpuAddress;
    private final ByteBuffer rawBuffer;
    private final FloatBuffer floatBuffer;

    public PackOptionsUniforms() {
        this.rawBuffer = MemoryUtil.memCalloc(BUFFER_SIZE);
        this.floatBuffer = rawBuffer.asFloatBuffer();

        this.uboId = GL45C.glCreateBuffers();
        GL45C.glNamedBufferData(uboId, rawBuffer, GL15C.GL_DYNAMIC_DRAW);

        long[] addrOut = new long[1];
        NVShaderBufferLoad.glGetNamedBufferParameterui64vNV(uboId, NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV, addrOut);
        this.gpuAddress = addrOut[0];
        NVShaderBufferLoad.glMakeNamedBufferResidentNV(uboId, GL15C.GL_READ_ONLY);
    }

    public void updateAndBind() {
        PipelineConfig cfg = PipelineConfig.get();
        ShaderPack pack = ShaderPackManager.get().getActivePack();

        for (int i = 0; i < FLOAT_COUNT; i++) {
            floatBuffer.put(i, 0.0f);
        }

        // Populate defaults from built-in options first so slots 0..15 always have sensible defaults
        for (ShaderPack.PackOption opt : ShaderPackManager.defaultBuiltinOptions()) {
            if (opt.slot >= 0 && opt.slot < FLOAT_COUNT) {
                floatBuffer.put(opt.slot, opt.defaultValue);
            }
        }

        if (pack != null) {
            for (ShaderPack.PackOption opt : pack.getOptions()) {
                if (opt.slot >= 0 && opt.slot < FLOAT_COUNT) {
                    float val = cfg.getPackOptionValue(pack.getId(), opt.id, opt.defaultValue);
                    floatBuffer.put(opt.slot, val);
                }
            }
        }

        GL45C.glNamedBufferSubData(uboId, 0, rawBuffer);
        bindStandard();
    }

    public void bindStandard() {
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, BINDING_POINT, uboId);
    }

    public void bindUnified() {
        if (gpuAddress != 0L) {
            NVVertexBufferUnifiedMemory.glBufferAddressRangeNV(
                    NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV,
                    BINDING_POINT,
                    gpuAddress,
                    BUFFER_SIZE
            );
        }
    }

    public void delete() {
        if (uboId != 0) {
            NVShaderBufferLoad.glMakeNamedBufferNonResidentNV(uboId);
            GL15C.glDeleteBuffers(uboId);
            uboId = 0;
            gpuAddress = 0L;
        }
        if (rawBuffer != null) {
            MemoryUtil.memFree(rawBuffer);
        }
    }
}
