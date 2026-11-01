package net.nvidium.shaders.pipeline.gbuffer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Manages the Multiple Render Target (MRT) G-Buffer Framebuffer.
 *
 * Layout:
 * - Attachment 0 (GL_RGBA16F): Base Albedo (RGB) + Roughness (A)
 * - Attachment 1 (GL_RGBA16F): Encoded Normal (RGB) + Metalness/PBR Flags (A)
 * - Attachment 2 (GL_RGBA16F): Lightmap UVs (RG) + Emissive (B) + Material ID (A)
 * - Depth (GL_DEPTH_COMPONENT24): Scene Depth (shares mcTarget depth texture directly)
 */
public class GBuffer {
    private int fboId = 0;
    private int albedoRoughnessTex = 0;
    private int normalMetallicTex = 0;
    private int lightEmissionTex = 0;
    private int depthTex = 0;
    private int attachedDepthTex = 0;

    private int width;
    private int height;

    public GBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        createFbo();
    }

    private void createFbo() {
        fboId = GL30C.glGenFramebuffers();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);

        // 1. Albedo + Roughness (RGBA16F for HDR & high precision)
        albedoRoughnessTex = createTexture(GL30C.GL_RGBA16F, GL11C.GL_RGBA, GL11C.GL_FLOAT, GL11C.GL_NEAREST);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, albedoRoughnessTex, 0);

        // 2. Normal + Metalness (RGBA16F)
        normalMetallicTex = createTexture(GL30C.GL_RGBA16F, GL11C.GL_RGBA, GL11C.GL_FLOAT, GL11C.GL_NEAREST);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT1, GL11C.GL_TEXTURE_2D, normalMetallicTex, 0);

        // 3. Light UVs + Emissive + MatID (RGBA16F)
        lightEmissionTex = createTexture(GL30C.GL_RGBA16F, GL11C.GL_RGBA, GL11C.GL_FLOAT, GL11C.GL_NEAREST);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT2, GL11C.GL_TEXTURE_2D, lightEmissionTex, 0);

        // 4. Depth buffer (DEPTH24 matching Minecraft's internal depth format)
        depthTex = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL14C.GL_DEPTH_COMPONENT24, width, height, 0, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_UNSIGNED_INT, (ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, depthTex, 0);

        // Set draw buffers
        int[] drawBuffers = {
                GL30C.GL_COLOR_ATTACHMENT0,
                GL30C.GL_COLOR_ATTACHMENT1,
                GL30C.GL_COLOR_ATTACHMENT2
        };
        GL20C.glDrawBuffers(drawBuffers);

        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("GBuffer Framebuffer creation failed with status: " + status);
        }

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    private int createTexture(int internalFormat, int format, int type, int filter) {
        int texId = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texId);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, filter);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, filter);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        return texId;
    }

    public void bindWrite() {
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fboId);
        // Attach Minecraft's main depth buffer directly so terrain depth writes straight into mcTarget
        RenderTarget mcTarget = Minecraft.getInstance().getMainRenderTarget();
        if (mcTarget != null && mcTarget.getDepthTextureId() > 0) {
            int mcDepth = mcTarget.getDepthTextureId();
            if (this.attachedDepthTex != mcDepth) {
                this.attachedDepthTex = mcDepth;
                GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, mcDepth, 0);
            }
        }
        GL11C.glViewport(0, 0, width, height);
    }

    public void clear() {
        bindWrite();
        GL11C.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT);
    }

    public void bindTextures(int startUnit) {
        // Bind Albedo+Roughness
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + startUnit);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, albedoRoughnessTex);

        // Bind Normal+Metallic
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + startUnit + 1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, normalMetallicTex);

        // Bind Light+Emissive
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + startUnit + 2);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lightEmissionTex);

        // Bind Depth
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + startUnit + 3);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, getDepthTex());
    }

    public void resize(int newWidth, int newHeight) {
        if (newWidth == width && newHeight == height) return;
        destroy();
        this.width = newWidth;
        this.height = newHeight;
        createFbo();
    }

    public void destroy() {
        attachedDepthTex = 0;
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
        if (depthTex != 0) {
            GL11C.glDeleteTextures(depthTex);
            depthTex = 0;
        }
    }

    public int getFboId() { return fboId; }
    public int getAlbedoRoughnessTex() { return albedoRoughnessTex; }
    public int getNormalMetallicTex() { return normalMetallicTex; }
    public int getLightEmissionTex() { return lightEmissionTex; }
    public int getDepthTex() { return attachedDepthTex > 0 ? attachedDepthTex : depthTex; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
