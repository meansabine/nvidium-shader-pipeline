package net.nvidium.shaders.pipeline.gbuffer;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * G-Buffer Framebuffer Object (MRT) using OpenGL 4.5 Direct State Access (DSA)
 * so Blaze3D's active texture state is never corrupted during creation or resize.
 *
 * <ul>
 *   <li>Attachment 0 ({@code GL_RGBA16F}): RGB = Albedo (linear), A = Roughness</li>
 *   <li>Attachment 1 ({@code GL_RGBA16F}): RGB = World Normal, A = Packed Metallic + Material ID</li>
 *   <li>Attachment 2 ({@code GL_RGBA16F}): R = BlockLight, G = SkyLight, B = Emissive, A = AO</li>
 *   <li>Depth Attachment: Shared with Minecraft's main render target depth texture</li>
 * </ul>
 */
public class GBuffer {
    private int width;
    private int height;

    private int fboId;
    private int albedoRoughnessTex;
    private int normalMetallicTex;
    private int lightEmissionTex;
    private int attachedDepthTex = -1;

    public GBuffer(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        init();
    }

    private void init() {
        fboId = GL45C.glCreateFramebuffers();

        albedoRoughnessTex = createColorAttachment(0);
        normalMetallicTex = createColorAttachment(1);
        lightEmissionTex = createColorAttachment(2);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer drawBuffers = stack.ints(
                    GL30C.GL_COLOR_ATTACHMENT0,
                    GL30C.GL_COLOR_ATTACHMENT1,
                    GL30C.GL_COLOR_ATTACHMENT2
            );
            GL45C.glNamedFramebufferDrawBuffers(fboId, drawBuffers);
        }
    }

    private int createColorAttachment(int index) {
        int tex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(tex, 1, GL30C.GL_RGBA16F, width, height);
        GL45C.glTextureParameteri(tex, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(tex, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(tex, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(tex, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

        GL45C.glNamedFramebufferTexture(fboId, GL30C.GL_COLOR_ATTACHMENT0 + index, tex, 0);
        return tex;
    }

    public void attachDepthTexture(int depthTexId) {
        if (depthTexId != attachedDepthTex && depthTexId > 0) {
            GL45C.glNamedFramebufferTexture(fboId, GL30C.GL_DEPTH_ATTACHMENT, depthTexId, 0);
            attachedDepthTex = depthTexId;
        }
    }

    public void bindForWriting(int mcDepthTextureId) {
        attachDepthTexture(mcDepthTextureId);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
        GL11C.glViewport(0, 0, width, height);
    }

    public void clearColorAttachments() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer zero = stack.floats(0.0f, 0.0f, 0.0f, 0.0f);
            GL45C.glClearNamedFramebufferfv(fboId, GL11C.GL_COLOR, 0, zero);
            GL45C.glClearNamedFramebufferfv(fboId, GL11C.GL_COLOR, 1, zero);
            GL45C.glClearNamedFramebufferfv(fboId, GL11C.GL_COLOR, 2, zero);
        }
    }

    public void bindForReading(int startUnit, int depthTextureId) {
        GL45C.glBindTextureUnit(startUnit, albedoRoughnessTex);
        GL45C.glBindTextureUnit(startUnit + 1, normalMetallicTex);
        GL45C.glBindTextureUnit(startUnit + 2, lightEmissionTex);
        if (depthTextureId > 0) {
            GL45C.glBindTextureUnit(startUnit + 3, depthTextureId);
        }
    }

    public void resize(int newWidth, int newHeight) {
        int w = Math.max(1, newWidth);
        int h = Math.max(1, newHeight);
        if (w == this.width && h == this.height) {
            return;
        }
        delete();
        this.width = w;
        this.height = h;
        init();
    }

    public void delete() {
        if (fboId != 0) {
            GL30C.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (albedoRoughnessTex != 0) {
            GL11C.glDeleteTextures(albedoRoughnessTex);
            albedoRoughnessTex = 0;
        }
        if (normalMetallicTex != 0) {
            GL11C.glDeleteTextures(normalMetallicTex);
            normalMetallicTex = 0;
        }
        if (lightEmissionTex != 0) {
            GL11C.glDeleteTextures(lightEmissionTex);
            lightEmissionTex = 0;
        }
        attachedDepthTex = -1;
    }

    public int getFboId() { return fboId; }
    public int getAlbedoRoughnessTex() { return albedoRoughnessTex; }
    public int getNormalMetallicTex() { return normalMetallicTex; }
    public int getLightEmissionTex() { return lightEmissionTex; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
