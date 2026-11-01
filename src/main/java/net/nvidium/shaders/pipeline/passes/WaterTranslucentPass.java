package net.nvidium.shaders.pipeline.passes;

import net.minecraft.resources.ResourceLocation;
import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.pipeline.PipelineManager;
import net.nvidium.shaders.pipeline.gl.ShaderProgram;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * Water & Translucent Pass.
 * Evaluates Screen-Space Reflections (SSR), depth-based water refraction,
 * and Beer-Lambert physical absorption for oceans and rivers.
 */
public class WaterTranslucentPass implements IShaderPass {
    private boolean enabled = false;
    private ShaderProgram waterShader;

    @Override
    public String getId() {
        return "water_translucent";
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
        if (lightingPass == null) return;

        // Render into HDR color target with depth testing against G-Buffer depth
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, lightingPass.getHdrFbo());
        GL11C.glViewport(0, 0, pipeline.getWidth(), pipeline.getHeight());

        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);

        if (waterShader != null) {
            waterShader.bind();

            // Bind opaque scene color: Unit 0
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, lightingPass.getHdrColorTexture());
            waterShader.setUniform1i("u_OpaqueSceneColor", 0);

            // Bind depth texture: Unit 1
            GL13C.glActiveTexture(GL13C.GL_TEXTURE1);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, pipeline.getGbuffer().getDepthTex());
            waterShader.setUniform1i("u_SceneDepth", 1);

            // Bind shadow map: Unit 4
            pipeline.getShadowMap().bindTexture(4);
            waterShader.setUniform1i("u_ShadowMap", 4);

            // Hook for Nvidium's TranslucentTerrainRasterizer here
            ShaderProgram.unbind();
        }

        GL11C.glDisable(GL11C.GL_BLEND);
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void resize(int width, int height) {}

    @Override
    public void reload() {
        try {
            if (waterShader != null) waterShader.destroy();
            waterShader = new ShaderProgram("WaterTranslucent")
                    .attachStage(GL20C.GL_VERTEX_SHADER, ResourceLocation.fromNamespaceAndPath(NvidiumShaderPipeline.MOD_ID, "shaders/common/fullscreen.vert"))
                    .attachStage(GL20C.GL_FRAGMENT_SHADER, ResourceLocation.fromNamespaceAndPath(NvidiumShaderPipeline.MOD_ID, "shaders/water/water_surface.frag"))
                    .link();
        } catch (Exception e) {
            NvidiumShaderPipeline.LOGGER.error("Failed to compile WaterTranslucent shader", e);
        }
    }

    @Override
    public void destroy() {
        if (waterShader != null) {
            waterShader.destroy();
            waterShader = null;
        }
    }
}
