package net.nvidium.shaders.pipeline.passes;

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
 * Volumetric Fog & Light Shaft Pass.
 * Raymarches the Sun/Moon Cascaded Shadow Map from camera to surface depth,
 * blending in-scattered light shafts additively into the HDR color buffer.
 */
public class VolumetricFogPass implements IShaderPass {
    private boolean enabled = true;
    private ShaderProgram fogShader;

    @Override
    public String getId() {
        return "volumetric_fog";
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
        if (lightingPass == null || fogShader == null) return;

        // Render additively onto HDR FBO
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, lightingPass.getHdrFbo());
        GL11C.glViewport(0, 0, pipeline.getWidth(), pipeline.getHeight());

        GL11C.glEnable(GL11C.GL_BLEND);
        GL11C.glBlendFunc(GL11C.GL_ONE, GL11C.GL_ONE); // Additive blending for scattering
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);

        fogShader.bind();

        // Bind Depth Texture: Unit 0
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, pipeline.getGbuffer().getDepthTex());
        fogShader.setUniform1i("u_DepthTexture", 0);

        // Bind Shadow Map: Unit 1
        pipeline.getShadowMap().bindTexture(1);
        fogShader.setUniform1i("u_ShadowMap", 1);

        FullscreenQuad.render();
        ShaderProgram.unbind();

        GL11C.glDisable(GL11C.GL_BLEND);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void resize(int width, int height) {}

    @Override
    public void reload() {
        try {
            if (fogShader != null) fogShader.destroy();
            fogShader = new ShaderProgram("VolumetricFog")
                    .attachStage(GL20C.GL_VERTEX_SHADER, ResourceLocation.fromNamespaceAndPath(NvidiumShaderPipeline.MOD_ID, "shaders/common/fullscreen.vert"))
                    .attachStage(GL20C.GL_FRAGMENT_SHADER, ResourceLocation.fromNamespaceAndPath(NvidiumShaderPipeline.MOD_ID, "shaders/deferred/volumetric_fog.frag"))
                    .link();
        } catch (Exception e) {
            NvidiumShaderPipeline.LOGGER.error("Failed to compile VolumetricFog shader", e);
        }
    }

    @Override
    public void destroy() {
        if (fogShader != null) {
            fogShader.destroy();
            fogShader = null;
        }
    }
}
