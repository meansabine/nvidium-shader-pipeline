package net.nvidium.shaders.integration;

import me.cortex.nvidium.api0.INvidiumShaderHook;
import me.cortex.nvidium.gl.shader.Shader;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.nvidium.shaders.config.PipelineConfig;
import net.nvidium.shaders.gui.NvidiumShaderScreen;
import net.nvidium.shaders.pipeline.PipelineManager;
import net.nvidium.shaders.pipeline.passes.RenderContext;

/**
 * Concrete implementation of {@link INvidiumShaderHook} bridging Nvidium's mesh-shader
 * terrain/translucent/shadow rendering with the Nvidium Deferred Shader Pipeline.
 */
public class NvidiumHookImpl implements INvidiumShaderHook {

    private final RenderContext currentContext = new RenderContext();

    @Override
    public void onSetupFrame(Viewport viewport, ChunkRenderMatrices crm, double px, double py, double pz) {
        if (!PipelineConfig.get().enabled) return;

        int width = Minecraft.getInstance().getWindow().getWidth();
        int height = Minecraft.getInstance().getWindow().getHeight();

        currentContext.updateFrame(viewport, crm, px, py, pz, width, height);
        PipelineManager.getInstance().onFrameSetup(currentContext);
    }

    @Override
    public void onShadowPass(ShadowPassContext shadowContext) {
        if (!PipelineConfig.get().enabled) return;
        PipelineManager.getInstance().executeShadowPass(shadowContext, currentContext);
    }

    @Override
    public void onPreRenderTerrain(int regionCount, long commandAddr) {
        if (!PipelineConfig.get().enabled) return;
        currentContext.updateTerrainBuffer(regionCount, commandAddr);
        PipelineManager.getInstance().executePreTerrain(currentContext);
    }

    @Override
    public void onBindTerrainTextures() {
        if (!PipelineConfig.get().enabled) return;
        PipelineManager.getInstance().bindTerrainTextures();
    }

    @Override
    public Shader getCustomTerrainShader() {
        if (!PipelineConfig.get().enabled) return null;
        return PipelineManager.getInstance().getCustomTerrainShader();
    }

    @Override
    public Shader getCustomTemporalTerrainShader() {
        if (!PipelineConfig.get().enabled) return null;
        return PipelineManager.getInstance().getCustomTemporalTerrainShader();
    }

    @Override
    public void onPostRenderTerrain() {
        if (!PipelineConfig.get().enabled) return;
        PipelineManager.getInstance().executePostTerrain(currentContext);
    }

    @Override
    public void onPreRenderTranslucent(int regionCount, long commandAddr) {
        if (!PipelineConfig.get().enabled) return;
        currentContext.updateTranslucentBuffer(regionCount, commandAddr);
        PipelineManager.getInstance().executePreTranslucent(currentContext);
    }

    @Override
    public Shader getCustomTranslucentShader() {
        if (!PipelineConfig.get().enabled) return null;
        return PipelineManager.getInstance().getCustomTranslucentShader();
    }

    @Override
    public void onBindTranslucentTextures() {
        if (!PipelineConfig.get().enabled) return;
        PipelineManager.getInstance().bindTranslucentTextures();
    }

    @Override
    public void onPostRenderTranslucent() {
        if (!PipelineConfig.get().enabled) return;
        PipelineManager.getInstance().executePostTranslucent(currentContext);
    }

    @Override
    public Screen createShaderConfigScreen(Screen parent) {
        return new NvidiumShaderScreen(parent);
    }
}
