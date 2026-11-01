package net.nvidium.shaders.pipeline.passes;

import net.nvidium.shaders.pipeline.PipelineManager;
import org.lwjgl.opengl.GL11C;

/**
 * G-Buffer render pass.
 * Sets up Multiple Render Targets (MRT) to receive geometry output from Nvidium's mesh shaders.
 */
public class GBufferPass implements IShaderPass {
    private boolean enabled = true;

    @Override
    public String getId() {
        return "gbuffer";
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
        // G-buffer FBO is owned by PipelineManager
    }

    @Override
    public void execute(PipelineManager pipeline, RenderContext context) {
        if (!enabled) return;

        // Bind G-Buffer MRT for writing
        pipeline.getGbuffer().bindWrite();
        pipeline.getGbuffer().clear();

        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GL11C.glDepthMask(true);
    }

    @Override
    public void resize(int width, int height) {}

    @Override
    public void reload() {}

    @Override
    public void destroy() {}
}
