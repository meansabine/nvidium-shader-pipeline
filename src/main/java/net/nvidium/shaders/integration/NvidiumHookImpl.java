package net.nvidium.shaders.integration;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.nvidium.api0.INvidiumShaderHook;
import me.cortex.nvidium.gl.shader.Shader;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.nvidium.shaders.NvidiumShaderPipeline;
import net.nvidium.shaders.pipeline.PipelineManager;
import net.nvidium.shaders.pipeline.passes.DeferredLightingPass;
import net.nvidium.shaders.pipeline.passes.PostProcessPass;
import net.nvidium.shaders.pipeline.passes.RenderContext;
import net.nvidium.shaders.pipeline.passes.VolumetricFogPass;
import net.nvidium.shaders.pipeline.passes.WaterTranslucentPass;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * Concrete implementation connecting Nvidium's GPU rendering engine
 * to the Nvidium Shader Pipeline (NSP).
 */
public class NvidiumHookImpl implements INvidiumShaderHook {
    private final PipelineManager pipeline;
    private RenderContext currentContext;
    private int frameIndex = 0;
    private float totalTime = 0.0f;

    public NvidiumHookImpl(PipelineManager pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void onSetupFrame(Viewport viewport, ChunkRenderMatrices crm, double px, double py, double pz) {
        if (!pipeline.isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Camera camera = mc.gameRenderer.getMainCamera();
        int width = mc.getWindow().getWidth();
        int height = mc.getWindow().getHeight();

        float tickDelta = mc.getTimer().getGameTimeDeltaPartialTick(true);
        totalTime += tickDelta / 20.0f;
        frameIndex++;

        Matrix4f proj = new Matrix4f(crm.projection());
        Matrix4f view = new Matrix4f(crm.modelView());

        this.currentContext = new RenderContext(
                proj,
                view,
                camera,
                level,
                width,
                height,
                tickDelta,
                totalTime,
                frameIndex
        );

        // Check for resize
        if (width != pipeline.getWidth() || height != pipeline.getHeight()) {
            pipeline.resize(width, height);
        }

        // 1. Update global scene uniforms UBO
        pipeline.getSceneUniforms().update(
                proj,
                view,
                camera,
                pipeline.getShadowMap(),
                level,
                width,
                height,
                totalTime,
                frameIndex
        );

        // 2. Update and evaluate Cascaded Shadow Map
        pipeline.getShadowMap().update(camera, level, tickDelta);
    }

    @Override
    public void onPreRenderTerrain(int regionCount, long commandAddr) {
        if (!pipeline.isEnabled()) return;

        // Bind G-Buffer Multiple Render Targets (MRT)
        pipeline.getGbuffer().bindWrite();
        pipeline.getGbuffer().clear();

        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GL11C.glDepthMask(true);
    }

    @Override
    public void onBindTerrainTextures() {
        if (!pipeline.isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        // Bind LabPBR Normal Atlas (unit 2) and Specular Atlas (unit 3) if present
        try {
            var normalTex = mc.getTextureManager().getTexture(ResourceLocation.parse("textures/atlas/blocks_n.png"));
            if (normalTex != null) {
                GlStateManager._activeTexture(33984 + 2); // GL_TEXTURE2
                GlStateManager._bindTexture(normalTex.getId());
            }
            var specTex = mc.getTextureManager().getTexture(ResourceLocation.parse("textures/atlas/blocks_s.png"));
            if (specTex != null) {
                GlStateManager._activeTexture(33984 + 3); // GL_TEXTURE3
                GlStateManager._bindTexture(specTex.getId());
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onPostRenderTerrain() {
        if (!pipeline.isEnabled() || currentContext == null) return;

        // 1. Execute Deferred Lighting Pass (Cook-Torrance GGX + Cascaded Shadows)
        var lightingPass = (DeferredLightingPass) pipeline.getPass("deferred_lighting");
        if (lightingPass != null && lightingPass.isEnabled()) {
            lightingPass.execute(pipeline, currentContext);
        }

        // 2. Execute Volumetric Fog Pass (Raymarched sun shafts)
        var fogPass = (VolumetricFogPass) pipeline.getPass("volumetric_fog");
        if (fogPass != null && fogPass.isEnabled()) {
            fogPass.execute(pipeline, currentContext);
        }
    }

    @Override
    public void onPreRenderTranslucent(int regionCount, long commandAddr) {
        if (!pipeline.isEnabled()) return;

        // Bind HDR Color target for translucent rasterization
        var lightingPass = (DeferredLightingPass) pipeline.getPass("deferred_lighting");
        if (lightingPass != null) {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, lightingPass.getHdrFbo());
        }
    }

    @Override
    public void onPostRenderTranslucent() {
        if (!pipeline.isEnabled() || currentContext == null) return;

        // 1. Execute Water Surface Shading (Screen-Space Reflections & Refraction)
        var waterPass = (WaterTranslucentPass) pipeline.getPass("water_translucent");
        if (waterPass != null && waterPass.isEnabled()) {
            waterPass.execute(pipeline, currentContext);
        }

        // 2. Execute Post-Processing & Tone Mapping (ACES, Bloom, Blit to Screen)
        var postPass = (PostProcessPass) pipeline.getPass("post_process");
        if (postPass != null && postPass.isEnabled()) {
            postPass.execute(pipeline, currentContext);
        }
    }

    @Override
    public Shader getCustomTerrainShader() {
        // Can optionally supply custom G-Buffer shader instance here
        return null;
    }
}
