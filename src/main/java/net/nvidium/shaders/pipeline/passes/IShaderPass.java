package net.nvidium.shaders.pipeline.passes;

/**
 * Interface for all passes in the Nvidium Shader Pipeline.
 */
public interface IShaderPass {
    void init();
    void execute(RenderContext ctx);
    void resize(int width, int height);
    void destroy();
    String getName();
    boolean isEnabled();
}
