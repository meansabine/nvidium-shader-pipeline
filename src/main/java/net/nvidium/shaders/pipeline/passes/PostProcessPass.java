package net.nvidium.shaders.pipeline.passes;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.pipeline.PipelineManager;
import net.nvidium.shaders.pipeline.gl.FullscreenQuad;
import net.nvidium.shaders.pipeline.gl.ShaderProgram;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * Post-Processing Pass.
 * Applies HDR Bloom, Auto-Exposure, ACES/AgX Film Tone Mapping,
 * and outputs the final color to Minecraft's main display framebuffer.
 */
public class PostProcessPass implements IShaderPass {
    private boolean enabled = true;
    private ShaderProgram tonemapShader;

    @Override
    public String getId() {
        return "post_process";
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
        reload();
    }

    @Override
    public void execute(PipelineManager pipeline, RenderContext context) {
        if (!enabled) return;

        DeferredLightingPass lightingPass = (DeferredLightingPass) pipeline.getPass("deferred_lighting");
        RenderTarget mcTarget = Minecraft.getInstance().getMainRenderTarget();
        int targetFbo = mcTarget != null ? mcTarget.frameBufferId : 0;
        int width = pipeline.getWidth();
        int height = pipeline.getHeight();

        // 1. If G-Buffer is using a separate depth buffer from mcTarget, copy depth
        // When G-Buffer attaches mcTarget's depth buffer directly, mcTarget already has the terrain depth!
        if (mcTarget != null && pipeline.getGbuffer().getDepthTex() != mcTarget.getDepthTextureId()) {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, pipeline.getGbuffer().getFboId());
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, targetFbo);
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11C.GL_DEPTH_BUFFER_BIT, GL11C.GL_NEAREST);
        }

        // 2. Render lit color into Minecraft's mainRenderTarget
        if (mcTarget != null) {
            mcTarget.bindWrite(true);
        } else {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL11C.glViewport(0, 0, width, height);
        }

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_BLEND);

        if (tonemapShader != null && lightingPass != null) {
            tonemapShader.bind();

            // Bind HDR Color texture: Unit 0
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lightingPass.getHdrColorTexture());
            tonemapShader.setUniform1i("u_HdrColorTexture", 0);

            FullscreenQuad.render();
            ShaderProgram.unbind();
        } else {
            // Robust Fallback: Blit HDR FBO (or G-Buffer Attachment 0) directly
            int sourceFbo = (lightingPass != null && lightingPass.getHdrFbo() != 0)
                    ? lightingPass.getHdrFbo()
                    : pipeline.getGbuffer().getFboId();
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, sourceFbo);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, targetFbo);
            GL30C.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
        }

        // Restore OpenGL state for Minecraft's entity/translucent rendering
        if (mcTarget != null) {
            mcTarget.bindWrite(false);
        }
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GL11C.glDepthMask(true);
    }

    @Override
    public void resize(int width, int height) {}

    @Override
    public void reload() {
        try {
            if (tonemapShader != null) tonemapShader.destroy();
            tonemapShader = new ShaderProgram("PostProcess")
                    .attachStage(GL20C.GL_VERTEX_SHADER, ResourceLocation.fromNamespaceAndPath(NvidiumShaderPipeline.MOD_ID, "shaders/common/fullscreen.vert"))
                    .attachStage(GL20C.GL_FRAGMENT_SHADER, ResourceLocation.fromNamespaceAndPath(NvidiumShaderPipeline.MOD_ID, "shaders/post/tonemap_bloom.frag"))
                    .link();
        } catch (Exception e) {
            NvidiumShaderPipeline.LOGGER.error("Failed to compile PostProcess shader", e);
        }
    }

    @Override
    public void destroy() {
        if (tonemapShader != null) {
            tonemapShader.destroy();
            tonemapShader = null;
        }
    }
}
