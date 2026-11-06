package net.nvidium.shaders.pipeline.shadow;

import net.nvidium.shaders.config.PipelineConfig;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

/**
 * 3-Cascade Shadow Map (CSM) backed by a 2D Depth Texture Array ({@code GL_DEPTH_COMPONENT32F})
 * allocated with OpenGL 4.5 Direct State Access (DSA).
 *
 * <p>Computes camera-relative frustum slice bounding spheres with light-space texel snapping
 * so shadows never shimmer when the camera translates or rotates.
 */
public class CascadedShadowMap {
    public static final int NUM_CASCADES = 3;

    private int resolution;
    private float maxDistance;

    private final float[] cascadeSplitDistances = new float[NUM_CASCADES];
    private final Matrix4f[] lightViewProjMatrices = new Matrix4f[NUM_CASCADES];

    private int fboId;
    private int depthTextureArray;

    private double anchorX = Double.NaN;
    private double anchorY = Double.NaN;
    private double anchorZ = Double.NaN;

    public CascadedShadowMap(int resolution, float maxDistance) {
        this.resolution = Math.max(512, resolution);
        this.maxDistance = Math.max(48.0f, maxDistance);
        for (int i = 0; i < NUM_CASCADES; i++) {
            lightViewProjMatrices[i] = new Matrix4f();
        }
        updateSplitRatios(this.maxDistance);
        init();
    }

    private void updateSplitRatios(float maxDist) {
        this.maxDistance = Math.max(48.0f, maxDist);
        cascadeSplitDistances[0] = Math.min(28.0f, this.maxDistance * 0.18f);
        cascadeSplitDistances[1] = Math.min(80.0f, this.maxDistance * 0.48f);
        cascadeSplitDistances[2] = this.maxDistance;
    }

    private void init() {
        fboId = GL45C.glCreateFramebuffers();

        depthTextureArray = GL45C.glCreateTextures(GL30C.GL_TEXTURE_2D_ARRAY);
        GL45C.glTextureStorage3D(depthTextureArray, 1, GL30C.GL_DEPTH_COMPONENT32F, resolution, resolution, NUM_CASCADES);

        GL45C.glTextureParameteri(depthTextureArray, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(depthTextureArray, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(depthTextureArray, GL11C.GL_TEXTURE_WRAP_S, GL13C.GL_CLAMP_TO_BORDER);
        GL45C.glTextureParameteri(depthTextureArray, GL11C.GL_TEXTURE_WRAP_T, GL13C.GL_CLAMP_TO_BORDER);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer border = stack.floats(1.0f, 1.0f, 1.0f, 1.0f);
            GL45C.glTextureParameterfv(depthTextureArray, GL11C.GL_TEXTURE_BORDER_COLOR, border);
        }

        GL45C.glNamedFramebufferTextureLayer(fboId, GL30C.GL_DEPTH_ATTACHMENT, depthTextureArray, 0, 0);
        GL45C.glNamedFramebufferDrawBuffer(fboId, GL11C.GL_NONE);
        GL45C.glNamedFramebufferReadBuffer(fboId, GL11C.GL_NONE);
    }

    public void syncConfig() {
        PipelineConfig cfg = PipelineConfig.get();
        updateSplitRatios(cfg.shadowDistance);
        int targetRes = Math.max(512, cfg.shadowResolution);
        if (targetRes != this.resolution) {
            delete();
            this.resolution = targetRes;
            init();
        }
    }

    /**
     * Updates the 3 camera-relative light View-Projection matrices for the active shadow-casting light.
     *
     * <p>Cascades are centered spherically on the player's position (rotation-invariant) and
     * texel-snapped in world-space light coordinates (translation-invariant) so shadows never
     * move, swim, or pop when the player moves or rotates their camera.
     *
     * @param cameraX  world-space camera X
     * @param cameraY  world-space camera Y
     * @param cameraZ  world-space camera Z
     * @param lightDir normalized world direction TOWARD the active light (sun or moon)
     */
    public void updateCascades(double cameraX, double cameraY, double cameraZ, Vector3f lightDir) {
        syncConfig();

        Vector3f dirToLight = new Vector3f(lightDir);
        if (dirToLight.lengthSquared() < 1.0e-5f) {
            dirToLight.set(0.3f, 0.9f, 0.25f);
        }
        dirToLight.normalize();

        if (Double.isNaN(anchorX)
                || Math.abs(cameraX - anchorX) > 4096.0
                || Math.abs(cameraY - anchorY) > 4096.0
                || Math.abs(cameraZ - anchorZ) > 4096.0) {
            anchorX = Math.floor(cameraX);
            anchorY = Math.floor(cameraY);
            anchorZ = Math.floor(cameraZ);
        }

        double relWorldCamX = cameraX - anchorX;
        double relWorldCamY = cameraY - anchorY;
        double relWorldCamZ = cameraZ - anchorZ;

        Vector3f up = Math.abs(dirToLight.y) > 0.98f
                ? new Vector3f(0.0f, 0.0f, 1.0f)
                : new Vector3f(0.0f, 1.0f, 0.0f);

        Matrix4f lightViewOrigin = new Matrix4f().lookAt(
                dirToLight.x * 160.0f, dirToLight.y * 160.0f, dirToLight.z * 160.0f,
                0.0f, 0.0f, 0.0f,
                up.x, up.y, up.z
        );

        // Project world-space camera displacement onto light-view axes in double precision
        double camLightX = relWorldCamX * lightViewOrigin.m00() + relWorldCamY * lightViewOrigin.m10() + relWorldCamZ * lightViewOrigin.m20();
        double camLightY = relWorldCamX * lightViewOrigin.m01() + relWorldCamY * lightViewOrigin.m11() + relWorldCamZ * lightViewOrigin.m21();
        double camLightZ = relWorldCamX * lightViewOrigin.m02() + relWorldCamY * lightViewOrigin.m12() + relWorldCamZ * lightViewOrigin.m22();

        for (int i = 0; i < NUM_CASCADES; i++) {
            float splitDist = cascadeSplitDistances[i];
            // Add margin for inter-cascade blending and PCF kernel so spherical distance never clips
            float radius = (float) Math.ceil(splitDist + 4.0f);

            double worldUnitsPerTexel = (radius * 2.0) / (double) resolution;
            double snappedWorldX = Math.floor(camLightX / worldUnitsPerTexel) * worldUnitsPerTexel;
            double snappedWorldY = Math.floor(camLightY / worldUnitsPerTexel) * worldUnitsPerTexel;
            double zQuant = 16.0;
            double snappedWorldZ = Math.floor(camLightZ / zQuant) * zQuant;

            // Camera-relative light-space center offset that locks the orthographic grid to world space
            float snappedRelX = (float) (snappedWorldX - camLightX);
            float snappedRelY = (float) (snappedWorldY - camLightY);
            float snappedRelZ = (float) (camLightZ - snappedWorldZ);

            float zPullback = 320.0f;
            Matrix4f lightProj = new Matrix4f().ortho(
                    snappedRelX - radius,
                    snappedRelX + radius,
                    snappedRelY - radius,
                    snappedRelY + radius,
                    -zPullback + snappedRelZ,
                    zPullback + snappedRelZ,
                    false
            );

            lightProj.mul(lightViewOrigin, lightViewProjMatrices[i]);
        }
    }

    public void bindCascadeForWriting(int cascadeIndex) {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
        GL32C.glFramebufferTextureLayer(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, depthTextureArray, 0, cascadeIndex);
        GL11C.glViewport(0, 0, resolution, resolution);
        GL11C.glClear(GL11C.GL_DEPTH_BUFFER_BIT);
    }

    public void bindTextureArray(int unit) {
        GL45C.glBindTextureUnit(unit, depthTextureArray);
    }

    public void delete() {
        if (fboId != 0) {
            GL30C.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (depthTextureArray != 0) {
            GL11C.glDeleteTextures(depthTextureArray);
            depthTextureArray = 0;
        }
    }

    public Matrix4f getLightViewProjMatrix(int cascade) { return lightViewProjMatrices[cascade]; }
    public float getCascadeSplit(int cascade) { return cascadeSplitDistances[cascade]; }
    public int getResolution() { return resolution; }
    public int getDepthTextureArray() { return depthTextureArray; }
}
