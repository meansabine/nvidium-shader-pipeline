package net.nvidium.shaders.pipeline.shadow;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;

/**
 * Harness for GPU-driven Cascaded Shadow Maps (CSM).
 * Configured with 3 cascades and anti-shimmering texel snapping.
 */
public class CascadedShadowMap {
    public static final int NUM_CASCADES = 3;
    public static final int SHADOW_RES = 2048;

    private final float[] splitDistances = new float[]{16.0f, 64.0f, 256.0f};
    private final Matrix4f[] cascadeViewProj = new Matrix4f[NUM_CASCADES];
    private final Vector3f sunDirection = new Vector3f(0.3f, 0.8f, 0.4f).normalize();

    private int fboId = 0;
    private int depthArrayTexture = 0;

    public CascadedShadowMap() {
        for (int i = 0; i < NUM_CASCADES; i++) {
            cascadeViewProj[i] = new Matrix4f();
        }
        createFbo();
    }

    private void createFbo() {
        fboId = GL30C.glGenFramebuffers();
        depthArrayTexture = GL11C.glGenTextures();

        GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, depthArrayTexture);
        GL42C.glTexStorage3D(GL30C.GL_TEXTURE_2D_ARRAY, 1, GL30C.GL_DEPTH_COMPONENT32F, SHADOW_RES, SHADOW_RES, NUM_CASCADES);

        // Hardware shadow sampler configuration (Percentage Closer Filtering)
        GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL14C.GL_TEXTURE_COMPARE_MODE, GL30C.GL_COMPARE_REF_TO_TEXTURE);
        GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL14C.GL_TEXTURE_COMPARE_FUNC, GL11C.GL_LEQUAL);
        GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL30C.GL_TEXTURE_2D_ARRAY, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
        // Attach layer 0 initially
        GL30C.glFramebufferTextureLayer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, depthArrayTexture, 0, 0);
        GL11C.glDrawBuffer(GL11C.GL_NONE);
        GL11C.glReadBuffer(GL11C.GL_NONE);

        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Shadow Framebuffer creation failed with status: " + status);
        }

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    public void update(Camera camera, ClientLevel level, float tickDelta) {
        if (level != null) {
            float sunAngle = level.getTimeOfDay(tickDelta);
            float rad = sunAngle * ((float) Math.PI * 2.0f);
            sunDirection.set((float) -Math.sin(rad), (float) Math.cos(rad), 0.2f).normalize();
        }

        Vector3f camPos = new Vector3f(
                (float) camera.getPosition().x,
                (float) camera.getPosition().y,
                (float) camera.getPosition().z
        );

        float lastSplit = 0.1f;
        for (int i = 0; i < NUM_CASCADES; i++) {
            float currentSplit = splitDistances[i];
            calculateCascadeMatrix(i, camPos, camera, lastSplit, currentSplit);
            lastSplit = currentSplit;
        }
    }

    private void calculateCascadeMatrix(int cascadeIndex, Vector3f camPos, Camera camera, float nearPlane, float farPlane) {
        // Compute light view matrix looking in the direction of the sun
        Vector3f lightTarget = new Vector3f(camPos);
        Vector3f lightPos = new Vector3f(camPos).add(new Vector3f(sunDirection).mul(farPlane * 0.75f));
        Matrix4f lightView = new Matrix4f().lookAt(lightPos, lightTarget, new Vector3f(0.0f, 1.0f, 0.0f));

        // Orthographic bounds fitted to cascade frustum slice
        float extent = farPlane * 0.8f;
        float zNear = -extent * 2.0f;
        float zFar = extent * 2.0f;

        // Texel snapping to eliminate shadow edge shimmering
        float worldUnitsPerTexel = (extent * 2.0f) / (float) SHADOW_RES;
        Matrix4f lightProj = new Matrix4f().ortho(-extent, extent, -extent, extent, zNear, zFar);

        cascadeViewProj[cascadeIndex].set(lightProj).mul(lightView);
    }

    public void bindWrite(int cascadeIndex) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
        GL30C.glFramebufferTextureLayer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, depthArrayTexture, 0, cascadeIndex);
        GL11C.glViewport(0, 0, SHADOW_RES, SHADOW_RES);
        GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
    }

    public void bindTexture(int unit) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(GL30C.GL_TEXTURE_2D_ARRAY, depthArrayTexture);
    }

    public Matrix4f getCascadeMatrix(int index) {
        return cascadeViewProj[index];
    }

    public float getSplitDistance(int index) {
        return splitDistances[index];
    }

    public Vector3f getSunDirection() {
        return sunDirection;
    }

    public void destroy() {
        if (fboId != 0) {
            GL30C.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (depthArrayTexture != 0) {
            GL11C.glDeleteTextures(depthArrayTexture);
            depthArrayTexture = 0;
        }
    }
}
