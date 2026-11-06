package net.nvidium.shaders.pipeline.passes;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.nvidium.shaders.pack.ShaderPack;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pipeline.gbuffer.GBuffer;
import net.nvidium.shaders.pipeline.gl.FullscreenQuad;
import net.nvidium.shaders.pipeline.gl.ShaderProgram;
import net.nvidium.shaders.pipeline.shadow.CascadedShadowMap;
import net.nvidium.shaders.pipeline.targets.HdrTargets;
import net.nvidium.shaders.pipeline.uniforms.PackOptionsUniforms;
import net.nvidium.shaders.pipeline.uniforms.SceneUniforms;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;

/**
 * Fullscreen Deferred PBR + Atmosphere + GTAO + Cascaded Shadows lighting pass.
 * Writes linear HDR color into {@link HdrTargets#getSceneFbo()}.
 */
public class DeferredLightingPass implements IShaderPass {
    private final GBuffer gBuffer;
    private final CascadedShadowMap csm;
    private final HdrTargets hdrTargets;
    private final SceneUniforms sceneUniforms;
    private final PackOptionsUniforms packOptionsUniforms;

    private ShaderProgram lightingProgram;
    private boolean enabled = true;

    public DeferredLightingPass(
            GBuffer gBuffer,
            CascadedShadowMap csm,
            HdrTargets hdrTargets,
            SceneUniforms sceneUniforms,
            PackOptionsUniforms packOptionsUniforms) {
        this.gBuffer = gBuffer;
        this.csm = csm;
        this.hdrTargets = hdrTargets;
        this.sceneUniforms = sceneUniforms;
        this.packOptionsUniforms = packOptionsUniforms;
    }

    @Override
    public void init() {
        ShaderPack pack = ShaderPackManager.get().getActivePack();
        this.lightingProgram = ShaderProgram.createFullscreenStage(pack, "deferred");
    }

    @Override
    public void execute(RenderContext ctx) {
        if (!enabled || lightingProgram == null) return;

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, hdrTargets.getSceneFbo());
        GL11C.glViewport(0, 0, ctx.getScreenWidth(), ctx.getScreenHeight());

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_BLEND);

        sceneUniforms.bindStandard();
        packOptionsUniforms.bindStandard();

        lightingProgram.bind();

        RenderTarget mcTarget = Minecraft.getInstance().getMainRenderTarget();
        gBuffer.bindForReading(0, mcTarget.getDepthTextureId());
        csm.bindTextureArray(4);

        FullscreenQuad.render();

        lightingProgram.unbind();
        GL11C.glDepthMask(true);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void destroy() {
        if (lightingProgram != null) {
            lightingProgram.delete();
            lightingProgram = null;
        }
    }

    @Override
    public String getName() {
        return "DeferredLightingPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
