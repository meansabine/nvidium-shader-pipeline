package net.nvidium.shaders.pipeline;

import net.minecraft.client.Minecraft;
import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.pipeline.gbuffer.GBuffer;
import net.nvidium.shaders.pipeline.gl.FullscreenQuad;
import net.nvidium.shaders.pipeline.passes.*;
import net.nvidium.shaders.pipeline.shadow.CascadedShadowMap;
import net.nvidium.shaders.pipeline.uniforms.SceneUniforms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central orchestrator for the Nvidium Shader Pipeline.
 * Manages framebuffers, uniform updates, shadow cascades, and pass execution sequence.
 */
public class PipelineManager {
    private boolean enabled = true;
    private int width;
    private int height;
    private int frameIndex = 0;
    private float totalTime = 0.0f;

    private GBuffer gbuffer;
    private CascadedShadowMap shadowMap;
    private SceneUniforms sceneUniforms;

    private final Map<String, IShaderPass> passes = new LinkedHashMap<>();

    public void init() {
        Minecraft mc = Minecraft.getInstance();
        this.width = Math.max(1, mc.getWindow().getWidth());
        this.height = Math.max(1, mc.getWindow().getHeight());

        FullscreenQuad.init();

        this.gbuffer = new GBuffer(width, height);
        this.shadowMap = new CascadedShadowMap();
        this.sceneUniforms = new SceneUniforms();

        // Register default passes in sequential order
        registerPass(new ShadowPass());
        registerPass(new GBufferPass());
        registerPass(new DeferredLightingPass());
        registerPass(new VolumetricFogPass());
        registerPass(new WaterTranslucentPass());
        registerPass(new PostProcessPass());

        // Initialize all passes
        for (IShaderPass pass : passes.values()) {
            pass.init(this);
        }

        NvidiumShaderPipeline.LOGGER.info("PipelineManager initialized with {} render passes.", passes.size());
    }

    public void registerPass(IShaderPass pass) {
        passes.put(pass.getId(), pass);
    }

    public IShaderPass getPass(String id) {
        return passes.get(id);
    }

    public void render(RenderContext context) {
        if (!enabled) return;

        // Check for window resize
        if (context.screenWidth() != width || context.screenHeight() != height) {
            resize(context.screenWidth(), context.screenHeight());
        }

        frameIndex++;
        totalTime += context.tickDelta() / 20.0f;

        // 1. Update UBO with scene parameters
        sceneUniforms.update(
                context.projectionMatrix(),
                context.modelViewMatrix(),
                context.camera(),
                shadowMap,
                context.level(),
                width,
                height,
                totalTime,
                frameIndex
        );

        // 2. Execute pipeline passes in order
        for (IShaderPass pass : passes.values()) {
            if (pass.isEnabled()) {
                pass.execute(this, context);
            }
        }
    }

    public void resize(int newWidth, int newHeight) {
        this.width = Math.max(1, newWidth);
        this.height = Math.max(1, newHeight);

        if (gbuffer != null) gbuffer.resize(width, height);
        for (IShaderPass pass : passes.values()) {
            pass.resize(width, height);
        }
    }

    public void reloadShaders() {
        NvidiumShaderPipeline.LOGGER.info("Reloading all Nvidium pipeline shaders...");
        for (IShaderPass pass : passes.values()) {
            pass.reload();
        }
    }

    public void destroy() {
        for (IShaderPass pass : passes.values()) {
            pass.destroy();
        }
        passes.clear();

        if (gbuffer != null) { gbuffer.destroy(); gbuffer = null; }
        if (shadowMap != null) { shadowMap.destroy(); shadowMap = null; }
        if (sceneUniforms != null) { sceneUniforms.destroy(); sceneUniforms = null; }
        FullscreenQuad.destroy();
    }

    public GBuffer getGbuffer() { return gbuffer; }
    public CascadedShadowMap getShadowMap() { return shadowMap; }
    public SceneUniforms getSceneUniforms() { return sceneUniforms; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
