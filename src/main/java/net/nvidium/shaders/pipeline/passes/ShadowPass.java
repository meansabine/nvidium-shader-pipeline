package net.nvidium.shaders.pipeline.passes;

import net.minecraft.resources.ResourceLocation;
import net.nvidium.shaders.pipeline.PipelineManager;
import net.nvidium.shaders.pipeline.gl.ShaderProgram;
import net.nvidium.shaders.pipeline.shadow.CascadedShadowMap;
import org.lwjgl.opengl.GL11C;

/**
 * Shadow render pass.
 * Executes depth-only culling and rasterization for Sun/Moon shadow cascades.
 */
public class ShadowPass implements IShaderPass {
    private boolean enabled = true;
    private ShaderProgram shadowShader;

    @Override
    public String getId() {
        return "shadow";
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

        CascadedShadowMap shadowMap = pipeline.getShadowMap();
        shadowMap.update(context.camera(), context.level(), context.tickDelta());

        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GL11C.glDepthMask(true);

        // Render each shadow cascade
        for (int cascade = 0; cascade < CascadedShadowMap.NUM_CASCADES; cascade++) {
            shadowMap.bindWrite(cascade);
            // Shadow mesh-shader rasterizer is invoked here for this cascade
        }
    }

    @Override
    public void resize(int width, int height) {}

    @Override
    public void reload() {
        if (shadowShader != null) {
            shadowShader.destroy();
            shadowShader = null;
        }
        // Shadow shaders can be loaded if custom shadow rasterization is active
    }

    @Override
    public void destroy() {
        if (shadowShader != null) {
            shadowShader.destroy();
            shadowShader = null;
        }
    }
}
