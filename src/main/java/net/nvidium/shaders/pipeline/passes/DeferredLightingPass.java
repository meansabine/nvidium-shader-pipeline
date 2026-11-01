package net.nvidium.shaders.pipeline.passes;

import net.minecraft.resources.ResourceLocation;
import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.pipeline.PipelineManager;
import net.nvidium.shaders.pipeline.gl.FullscreenQuad;
import net.nvidium.shaders.pipeline.gl.ShaderProgram;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;

/**
 * Deferred Lighting Pass.
 * Reads the G-Buffer textures and shadow cascades, applies Cook-Torrance GGX lighting,
 * evaluates PCF cascaded shadows, and writes to an HDR color target.
 */
public class DeferredLightingPass implements IShaderPass {
    private boolean enabled = true;
    private ShaderProgram lightingShader;

    private int hdrFbo = 0;
    private int hdrColorTexture = 0;
    private int width;
    private int height;

    @Override
    public String getId() {
        return "deferred_lighting";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void init(PipelineManager pipeline) {
        this.width = pipeline.getWidth();
        this.height = pipeline.getHeight();
        createHdrFbo();
        reload();
    }

    private void createHdrFbo() {
        hdrFbo = GL30C.glGenFramebuffers();
        hdrColorTexture = GL11C.glGenTextures();

        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, hdrColorTexture);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, width, height, 0, GL11C.GL_RGBA, GL11C.GL_FLOAT, (ByteBuffer) null);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, hdrFbo);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, hdrColorTexture, 0);

        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("HDR Framebuffer creation failed with status: " + status);
        }
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void execute(PipelineManager pipeline, RenderContext context) {
        if (!enabled) return;

        // Bind HDR FBO
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, hdrFbo);
        GL11C.glViewport(0, 0, width, height);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);

        if (lightingShader != null) {
            lightingShader.bind();

            // Bind G-Buffer textures: Units 0, 1, 2, 3
            pipeline.getGbuffer().bindTextures(0);
            lightingShader.setUniform1i("u_AlbedoRoughness", 0);
            lightingShader.setUniform1i("u_NormalMetallic", 1);
            lightingShader.setUniform1i("u_LightEmission", 2);
            lightingShader.setUniform1i("u_Depth", 3);

            // Bind Shadow map: Unit 4
            pipeline.getShadowMap().bindTexture(4);
            lightingShader.setUniform1i("u_ShadowMap", 4);

            FullscreenQuad.render();
            ShaderProgram.unbind();
        }

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    public int getHdrColorTexture() {
        return hdrColorTexture;
    }

    public int getHdrFbo() {
        return hdrFbo;
    }

    @Override
    public void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
        destroyFbo();
        createHdrFbo();
    }

    @Override
    public void reload() {
        try {
            if (lightingShader != null) lightingShader.destroy();
            lightingShader = new ShaderProgram("DeferredLighting")
                    .attachStage(GL20C.GL_VERTEX_SHADER, ResourceLocation.fromNamespaceAndPath(NvidiumShaderPipeline.MOD_ID, "shaders/common/fullscreen.vert"))
                    .attachStage(GL20C.GL_FRAGMENT_SHADER, ResourceLocation.fromNamespaceAndPath(NvidiumShaderPipeline.MOD_ID, "shaders/deferred/deferred_lighting.frag"))
                    .link();
        } catch (Exception e) {
            NvidiumShaderPipeline.LOGGER.error("Failed to compile DeferredLighting shader", e);
        }
    }

    private void destroyFbo() {
        if (hdrFbo != 0) {
            GL30C.glDeleteFramebuffers(hdrFbo);
            hdrFbo = 0;
        }
        if (hdrColorTexture != 0) {
            GL11C.glDeleteTextures(hdrColorTexture);
            hdrColorTexture = 0;
        }
    }

    @Override
    public void destroy() {
        destroyFbo();
        if (lightingShader != null) {
            lightingShader.destroy();
            lightingShader = null;
        }
    }
}
