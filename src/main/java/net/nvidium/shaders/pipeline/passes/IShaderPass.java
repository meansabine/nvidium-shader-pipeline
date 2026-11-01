package net.nvidium.shaders.pipeline.passes;

import net.nvidium.shaders.pipeline.PipelineManager;

/**
 * Interface representing a distinct render pass within the Nvidium Shader Pipeline.
 */
public interface IShaderPass {
    String getId();

    boolean isEnabled();

    void setEnabled(boolean enabled);

    void init(PipelineManager pipeline);

    void execute(PipelineManager pipeline, RenderContext context);

    void resize(int width, int height);

    void reload();

    void destroy();
}
