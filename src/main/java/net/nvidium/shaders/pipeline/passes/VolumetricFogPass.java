package net.nvidium.shaders.pipeline.passes;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.nvidium.shaders.config.PipelineConfig;
import net.nvidium.shaders.pack.ShaderPack;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pipeline.gl.FullscreenQuad;
import net.nvidium.shaders.pipeline.gl.ShaderProgram;
import net.nvidium.shaders.pipeline.shadow.CascadedShadowMap;
import net.nvidium.shaders.pipeline.targets.HdrTargets;
import net.nvidium.shaders.pipeline.uniforms.PackOptionsUniforms;
import net.nvidium.shaders.pipeline.uniforms.SceneUniforms;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

/**
 * Raymarched volumetric light-shafts (sun godrays / moonbeams / underwater shafts) and
 * exponential height fog pass. Writes RGBA16F (`rgb = inscatter, a = transmittance`)
 * into {@link HdrTargets#getFogFbo()}.
 */
public class VolumetricFogPass implements IShaderPass {
    private final CascadedShadowMap csm;
    private final HdrTargets hdrTargets;
    private final SceneUniforms sceneUniforms;
    private final PackOptionsUniforms packOptionsUniforms;

    private ShaderProgram fogProgram;
    private boolean enabled = true;

    public VolumetricFogPass(
            CascadedShadowMap csm,
            HdrTargets hdrTargets,
            SceneUniforms sceneUniforms,
            PackOptionsUniforms packOptionsUniforms) {
        this.csm = csm;
        this.hdrTargets = hdrTargets;
        this.sceneUniforms = sceneUniforms;
        this.packOptionsUniforms = packOptionsUniforms;
    }

    @Override
    public void init() {
        ShaderPack pack = ShaderPackManager.get().getActivePack();
        this.fogProgram = ShaderProgram.createFullscreenStage(pack, "fog");
    }

    @Override
    public void execute(RenderContext ctx) {
        if (!enabled || fogProgram == null) return;

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, hdrTargets.getFogFbo());
        GL11C.glViewport(0, 0, ctx.getScreenWidth(), ctx.getScreenHeight());

        if (!PipelineConfig.get().volumetricFog) {
            GL11C.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            return;
        }

        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_BLEND);

        sceneUniforms.bindStandard();
        packOptionsUniforms.bindStandard();

        fogProgram.bind();

        RenderTarget mcTarget = Minecraft.getInstance().getMainRenderTarget();
        GL45C.glBindTextureUnit(0, mcTarget.getDepthTextureId());
        csm.bindTextureArray(1);

        FullscreenQuad.render();

        fogProgram.unbind();
        GL11C.glDepthMask(true);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void destroy() {
        if (fogProgram != null) {
            fogProgram.delete();
            fogProgram = null;
        }
    }

    @Override
    public String getName() {
        return "VolumetricFogPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
