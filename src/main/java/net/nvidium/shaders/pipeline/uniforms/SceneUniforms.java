package net.nvidium.shaders.pipeline.uniforms;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.nvidium.shaders.pipeline.shadow.CascadedShadowMap;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Manages the Scene Uniform Buffer Object (UBO) matching std140 layout.
 * Bound to Uniform Buffer Binding Point 0 across all shader stages.
 */
public class SceneUniforms {
    public static final int BINDING_POINT = 0;
    public static final int BUFFER_SIZE = 1024;

    private int uboId = 0;
    private final ByteBuffer cpuBuffer = BufferUtils.createByteBuffer(BUFFER_SIZE);

    private final Matrix4f viewProj = new Matrix4f();
    private final Matrix4f invViewProj = new Matrix4f();

    public SceneUniforms() {
        uboId = GL15C.glGenBuffers();
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, uboId);
        GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, BUFFER_SIZE, GL15C.GL_DYNAMIC_DRAW);
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);

        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, BINDING_POINT, uboId);
    }

    public void update(Matrix4f projection, Matrix4f modelView, Camera camera, CascadedShadowMap shadowMap, ClientLevel level, int width, int height, float time, int frameIndex) {
        viewProj.set(projection).mul(modelView);
        invViewProj.set(viewProj).invert();

        long addr = MemoryUtil.memAddress(cpuBuffer);
        cpuBuffer.clear();

        // 0: mat4 u_ViewMatrix (64)
        modelView.getToAddress(addr);
        addr += 64;

        // 64: mat4 u_ProjMatrix (64)
        projection.getToAddress(addr);
        addr += 64;

        // 128: mat4 u_ViewProjMatrix (64)
        viewProj.getToAddress(addr);
        addr += 64;

        // 192: mat4 u_InvViewProjMatrix (64)
        invViewProj.getToAddress(addr);
        addr += 64;

        // 256: mat4 u_CascadeViewProj[3] (3 * 64 = 192)
        for (int i = 0; i < CascadedShadowMap.NUM_CASCADES; i++) {
            shadowMap.getCascadeMatrix(i).getToAddress(addr);
            addr += 64;
        }

        // 448: vec4 u_CascadeSplits (16)
        MemoryUtil.memPutFloat(addr, shadowMap.getSplitDistance(0));
        MemoryUtil.memPutFloat(addr + 4, shadowMap.getSplitDistance(1));
        MemoryUtil.memPutFloat(addr + 8, shadowMap.getSplitDistance(2));
        MemoryUtil.memPutFloat(addr + 12, 0.0f);
        addr += 16;

        // 464: vec4 u_SunDirection (16)
        Vector3f sunDir = shadowMap.getSunDirection();
        MemoryUtil.memPutFloat(addr, sunDir.x);
        MemoryUtil.memPutFloat(addr + 4, sunDir.y);
        MemoryUtil.memPutFloat(addr + 8, sunDir.z);
        MemoryUtil.memPutFloat(addr + 12, 0.0f);
        addr += 16;

        // 480: vec4 u_SunColor (16)
        MemoryUtil.memPutFloat(addr, 1.0f);
        MemoryUtil.memPutFloat(addr + 4, 0.95f);
        MemoryUtil.memPutFloat(addr + 8, 0.85f);
        MemoryUtil.memPutFloat(addr + 12, 1.0f);
        addr += 16;

        // 496: vec4 u_AmbientColor (16)
        MemoryUtil.memPutFloat(addr, 0.15f);
        MemoryUtil.memPutFloat(addr + 4, 0.2f);
        MemoryUtil.memPutFloat(addr + 8, 0.3f);
        MemoryUtil.memPutFloat(addr + 12, 1.0f);
        addr += 16;

        // 512: vec4 u_CameraPos (16)
        MemoryUtil.memPutFloat(addr, (float) camera.getPosition().x);
        MemoryUtil.memPutFloat(addr + 4, (float) camera.getPosition().y);
        MemoryUtil.memPutFloat(addr + 8, (float) camera.getPosition().z);
        MemoryUtil.memPutFloat(addr + 12, 1.0f);
        addr += 16;

        // 528: vec2 u_ScreenSize + padding (16)
        MemoryUtil.memPutFloat(addr, (float) width);
        MemoryUtil.memPutFloat(addr + 4, (float) height);
        MemoryUtil.memPutFloat(addr + 8, 0.0f);
        MemoryUtil.memPutFloat(addr + 12, 0.0f);
        addr += 16;

        // 544: time, frameIndex, fogStart, fogEnd (16)
        MemoryUtil.memPutFloat(addr, time);
        MemoryUtil.memPutInt(addr + 4, frameIndex);
        MemoryUtil.memPutFloat(addr + 8, RenderSystem.getShaderFogStart());
        MemoryUtil.memPutFloat(addr + 12, RenderSystem.getShaderFogEnd());
        addr += 16;

        // 560: vec4 u_FogColor (16)
        float[] fog = RenderSystem.getShaderFogColor();
        MemoryUtil.memPutFloat(addr, fog[0]);
        MemoryUtil.memPutFloat(addr + 4, fog[1]);
        MemoryUtil.memPutFloat(addr + 8, fog[2]);
        MemoryUtil.memPutFloat(addr + 12, fog[3]);

        // Upload to GPU buffer
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, uboId);
        GL15C.glBufferSubData(GL31C.GL_UNIFORM_BUFFER, 0, cpuBuffer);
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);

        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, BINDING_POINT, uboId);
    }

    public void destroy() {
        if (uboId != 0) {
            GL15C.glDeleteBuffers(uboId);
            uboId = 0;
        }
    }
}
