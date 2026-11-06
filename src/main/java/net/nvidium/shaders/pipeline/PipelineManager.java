package net.nvidium.shaders.pipeline;

import me.cortex.nvidium.api0.INvidiumShaderHook;
import me.cortex.nvidium.gl.shader.Shader;
import net.minecraft.client.Minecraft;
import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.config.PipelineConfig;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pipeline.gbuffer.GBuffer;
import net.nvidium.shaders.pipeline.gl.FullscreenQuad;
import net.nvidium.shaders.pipeline.passes.*;
import net.nvidium.shaders.pipeline.shadow.CascadedShadowMap;
import net.nvidium.shaders.pipeline.targets.HdrTargets;
import net.nvidium.shaders.pipeline.targets.SceneCopyTargets;
import net.nvidium.shaders.pipeline.textures.PbrAtlasHooks;
import net.nvidium.shaders.pipeline.uniforms.PackOptionsUniforms;
import net.nvidium.shaders.pipeline.uniforms.SceneUniforms;

import java.util.ArrayList;
import java.util.List;

/**
 * Central orchestrator for the Nvidium Deferred & Mesh-Shader Pipeline.
 */
public class PipelineManager {
    private static PipelineManager INSTANCE;

    private final List<IShaderPass> passes = new ArrayList<>();
    private boolean initialized = false;
    private int lastWidth = -1;
    private int lastHeight = -1;

    // Shared resources
    private GBuffer gBuffer;
    private CascadedShadowMap csm;
    private HdrTargets hdrTargets;
    private SceneCopyTargets sceneCopyTargets;
    private SceneUniforms sceneUniforms;
    private PackOptionsUniforms packOptionsUniforms;
    private PbrAtlasHooks pbrHooks;

    // Active passes
    private ShadowPass shadowPass;
    private GBufferPass gBufferPass;
    private DeferredLightingPass deferredLightingPass;
    private VolumetricFogPass volumetricFogPass;
    private WaterTranslucentPass waterTranslucentPass;
    private PostProcessPass postProcessPass;

    public static PipelineManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PipelineManager();
        }
        return INSTANCE;
    }

    private PipelineManager() {}

    public synchronized void init() {
        if (initialized) return;

        NvidiumShaderPipeline.LOGGER.info("Initializing Nvidium Shader Pipeline (Active pack: {})...",
                ShaderPackManager.get().getActivePack().getName());
        ShaderPackManager.get().clearError();

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow() != null ? Math.max(1, mc.getWindow().getWidth()) : 1920;
        int height = mc.getWindow() != null ? Math.max(1, mc.getWindow().getHeight()) : 1080;
        this.lastWidth = width;
        this.lastHeight = height;

        PipelineConfig cfg = PipelineConfig.get();

        // 1. Initialize shared GPU resources
        gBuffer = new GBuffer(width, height);
        csm = new CascadedShadowMap(cfg.shadowResolution, cfg.shadowDistance);
        hdrTargets = new HdrTargets(width, height);
        sceneCopyTargets = new SceneCopyTargets(width, height);
        sceneUniforms = new SceneUniforms();
        packOptionsUniforms = new PackOptionsUniforms();
        pbrHooks = new PbrAtlasHooks();

        // 2. Initialize passes
        shadowPass = new ShadowPass(csm, sceneUniforms, packOptionsUniforms);
        gBufferPass = new GBufferPass(gBuffer, sceneUniforms, packOptionsUniforms, pbrHooks);
        deferredLightingPass = new DeferredLightingPass(gBuffer, csm, hdrTargets, sceneUniforms, packOptionsUniforms);
        volumetricFogPass = new VolumetricFogPass(csm, hdrTargets, sceneUniforms, packOptionsUniforms);
        waterTranslucentPass = new WaterTranslucentPass(sceneCopyTargets, csm, sceneUniforms, packOptionsUniforms, pbrHooks);
        postProcessPass = new PostProcessPass(gBuffer, hdrTargets, sceneCopyTargets, sceneUniforms, packOptionsUniforms);

        registerPass(shadowPass);
        registerPass(gBufferPass);
        registerPass(deferredLightingPass);
        registerPass(volumetricFogPass);
        registerPass(waterTranslucentPass);
        registerPass(postProcessPass);

        for (IShaderPass pass : passes) {
            pass.init();
        }

        initialized = true;
        NvidiumShaderPipeline.LOGGER.info("Nvidium Shader Pipeline initialized successfully ({} passes).", passes.size());
    }

    public void registerPass(IShaderPass pass) {
        this.passes.add(pass);
    }

    public void onFrameSetup(RenderContext ctx) {
        if (!initialized) {
            init();
        }
        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        if (w != lastWidth || h != lastHeight) {
            onResize(w, h);
        }
        sceneUniforms.updateFromFrame(ctx, packOptionsUniforms);
    }

    public void executeShadowPass(INvidiumShaderHook.ShadowPassContext shadowCtx, RenderContext ctx) {
        if (initialized && shadowPass != null && shadowPass.isEnabled()) {
            shadowPass.executeShadowCascades(shadowCtx, ctx);
        }
    }

    public Shader getCustomTerrainShader() {
        if (!initialized || gBufferPass == null) return null;
        return gBufferPass.getCustomTerrainShader();
    }

    public Shader getCustomTemporalTerrainShader() {
        if (!initialized || gBufferPass == null) return null;
        return gBufferPass.getCustomTemporalTerrainShader();
    }

    public Shader getCustomTranslucentShader() {
        if (!initialized || waterTranslucentPass == null) return null;
        return waterTranslucentPass.getCustomTranslucentShader();
    }

    public void bindTerrainTextures() {
        if (initialized && gBufferPass != null) {
            gBufferPass.bindLabPbrTextures();
        }
    }

    public void bindTranslucentTextures() {
        if (initialized && waterTranslucentPass != null) {
            waterTranslucentPass.bindTranslucentTextures();
        }
    }

    public void executePreTerrain(RenderContext ctx) {
        if (initialized && gBufferPass != null && gBufferPass.isEnabled()) {
            gBufferPass.execute(ctx);
        }
    }

    public void executePostTerrain(RenderContext ctx) {
        if (!initialized) return;
        if (deferredLightingPass != null && deferredLightingPass.isEnabled()) {
            deferredLightingPass.execute(ctx);
        }
        if (volumetricFogPass != null && volumetricFogPass.isEnabled()) {
            volumetricFogPass.execute(ctx);
        }
        if (postProcessPass != null && postProcessPass.isEnabled()) {
            postProcessPass.execute(ctx);
        }
    }

    public void executePreTranslucent(RenderContext ctx) {
        if (initialized && waterTranslucentPass != null && waterTranslucentPass.isEnabled()) {
            waterTranslucentPass.execute(ctx);
        }
    }

    public void executePostTranslucent(RenderContext ctx) {
        if (initialized && postProcessPass != null && postProcessPass.isEnabled()) {
            postProcessPass.executeFinalComposite(ctx);
        }
    }

    public synchronized void onResize(int width, int height) {
        if (!initialized || width <= 0 || height <= 0) return;
        this.lastWidth = width;
        this.lastHeight = height;

        gBuffer.resize(width, height);
        hdrTargets.resize(width, height);
        sceneCopyTargets.resize(width, height);
        sceneUniforms.resetTemporalHistory();

        for (IShaderPass pass : passes) {
            pass.resize(width, height);
        }
    }

    public synchronized void reload() {
        NvidiumShaderPipeline.LOGGER.info("Reloading Nvidium Shader Pipeline...");
        ShaderPackManager.get().discoverPacks();
        destroy();
        init();
    }

    public synchronized void destroy() {
        if (!initialized) return;
        for (IShaderPass pass : passes) {
            pass.destroy();
        }
        passes.clear();

        if (gBuffer != null) { gBuffer.delete(); gBuffer = null; }
        if (csm != null) { csm.delete(); csm = null; }
        if (hdrTargets != null) { hdrTargets.delete(); hdrTargets = null; }
        if (sceneCopyTargets != null) { sceneCopyTargets.delete(); sceneCopyTargets = null; }
        if (sceneUniforms != null) { sceneUniforms.delete(); sceneUniforms = null; }
        if (packOptionsUniforms != null) { packOptionsUniforms.delete(); packOptionsUniforms = null; }
        if (pbrHooks != null) { pbrHooks.delete(); pbrHooks = null; }

        FullscreenQuad.destroy();
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public GBuffer getGBuffer() { return gBuffer; }
    public CascadedShadowMap getCsm() { return csm; }
    public HdrTargets getHdrTargets() { return hdrTargets; }
    public SceneUniforms getSceneUniforms() { return sceneUniforms; }
    public PackOptionsUniforms getPackOptionsUniforms() { return packOptionsUniforms; }
}
