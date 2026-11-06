package net.nvidium.shaders.pipeline.passes;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.nvidium.shaders.pack.ShaderPack;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pipeline.gbuffer.GBuffer;
import net.nvidium.shaders.pipeline.gl.FullscreenQuad;
import net.nvidium.shaders.pipeline.gl.ShaderProgram;
import net.nvidium.shaders.pipeline.targets.HdrTargets;
import net.nvidium.shaders.pipeline.targets.SceneCopyTargets;
import net.nvidium.shaders.pipeline.uniforms.PackOptionsUniforms;
import net.nvidium.shaders.pipeline.uniforms.SceneUniforms;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

/**
 * Two-phase post-processing pass:
 * <ol>
 *   <li>{@link #execute(RenderContext)}: Resolves HDR Deferred Lighting + Volumetric Fog + Multi-Mip Bloom +
 *       Temporal Accumulation (TAA) + AgX/ACES Tonemapping into history buffer and blits to {@code mcTarget}
 *       so entities and translucent geometry render over the lit scene.</li>
 *   <li>{@link #executeFinalComposite(RenderContext)}: Runs after translucent water/glass to apply
 *       underwater lens distortion/caustics, FXAA, and Contrast Adaptive Sharpening (CAS).</li>
 * </ol>
 */
public class PostProcessPass implements IShaderPass {
    private final GBuffer gBuffer;
    private final HdrTargets hdrTargets;
    private final SceneCopyTargets sceneCopyTargets;
    private final SceneUniforms sceneUniforms;
    private final PackOptionsUniforms packOptionsUniforms;

    private ShaderProgram tonemapBloomProgram;
    private ShaderProgram finalCompositeProgram;
    private boolean enabled = true;

    public PostProcessPass(
            GBuffer gBuffer,
            HdrTargets hdrTargets,
            SceneCopyTargets sceneCopyTargets,
            SceneUniforms sceneUniforms,
            PackOptionsUniforms packOptionsUniforms) {
        this.gBuffer = gBuffer;
        this.hdrTargets = hdrTargets;
        this.sceneCopyTargets = sceneCopyTargets;
        this.sceneUniforms = sceneUniforms;
        this.packOptionsUniforms = packOptionsUniforms;
    }

    @Override
    public void init() {
        ShaderPack pack = ShaderPackManager.get().getActivePack();
        this.tonemapBloomProgram = ShaderProgram.createFullscreenStage(pack, "post");
        this.finalCompositeProgram = ShaderProgram.createFullscreenStage(pack, "final");
    }

    @Override
    public void execute(RenderContext ctx) {
        if (!enabled || tonemapBloomProgram == null) return;

        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        RenderTarget mcTarget = Minecraft.getInstance().getMainRenderTarget();

        // Generate hardware mip pyramid on HDR scene texture for wide multi-octave bloom
        hdrTargets.generateSceneMipmaps();

        int writeFbo = hdrTargets.getCurrentHistoryWriteFbo();
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, writeFbo);
        GL11C.glViewport(0, 0, w, h);

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_BLEND);

        sceneUniforms.bindStandard();
        packOptionsUniforms.bindStandard();

        tonemapBloomProgram.bind();

        GL45C.glBindTextureUnit(0, hdrTargets.getSceneTex());
        GL45C.glBindTextureUnit(1, hdrTargets.getFogTex());
        GL45C.glBindTextureUnit(2, gBuffer.getLightEmissionTex());
        GL45C.glBindTextureUnit(3, mcTarget.getDepthTextureId());
        GL45C.glBindTextureUnit(4, hdrTargets.getPreviousHistoryReadTex());
        GL45C.glBindTextureUnit(5, gBuffer.getNormalMetallicTex());

        FullscreenQuad.render();

        tonemapBloomProgram.unbind();

        // Blit resolved color to Minecraft's main render target while preserving terrain depth in mcTarget
        GL45C.glBlitNamedFramebuffer(
                writeFbo,
                mcTarget.frameBufferId,
                0, 0, w, h,
                0, 0, w, h,
                GL11C.GL_COLOR_BUFFER_BIT,
                GL11C.GL_NEAREST
        );

        hdrTargets.swapHistory();

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mcTarget.frameBufferId);
        GL11C.glDepthMask(true);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    public void executeFinalComposite(RenderContext ctx) {
        if (!enabled || finalCompositeProgram == null) return;

        int w = ctx.getScreenWidth();
        int h = ctx.getScreenHeight();
        RenderTarget mcTarget = Minecraft.getInstance().getMainRenderTarget();

        // Copy current mcTarget color (including entities & translucent water/glass) to sceneCopyTargets
        sceneCopyTargets.copyFromFbo(mcTarget.frameBufferId, mcTarget.getDepthTextureId(), true, false, false);

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mcTarget.frameBufferId);
        GL11C.glViewport(0, 0, w, h);

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_BLEND);

        sceneUniforms.bindStandard();
        packOptionsUniforms.bindStandard();

        finalCompositeProgram.bind();

        GL45C.glBindTextureUnit(0, sceneCopyTargets.getColorCopyTex());
        GL45C.glBindTextureUnit(1, mcTarget.getDepthTextureId());
        GL45C.glBindTextureUnit(2, gBuffer.getNormalMetallicTex());
        GL45C.glBindTextureUnit(3, gBuffer.getLightEmissionTex());

        FullscreenQuad.render();

        finalCompositeProgram.unbind();

        GL11C.glDepthMask(true);
        Gl11RestoreState();
    }

    private void Gl11RestoreState() {
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void destroy() {
        if (tonemapBloomProgram != null) {
            tonemapBloomProgram.delete();
            tonemapBloomProgram = null;
        }
        if (finalCompositeProgram != null) {
            finalCompositeProgram.delete();
            finalCompositeProgram = null;
        }
    }

    @Override
    public String getName() {
        return "PostProcessPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
