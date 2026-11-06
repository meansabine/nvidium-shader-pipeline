package net.nvidium.shaders.pipeline.passes;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.nvidium.gl.shader.Shader;
import net.minecraft.client.Minecraft;
import net.nvidium.shaders.pack.NvidiumShaderPrograms;
import net.nvidium.shaders.pack.ShaderPack;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pipeline.shadow.CascadedShadowMap;
import net.nvidium.shaders.pipeline.targets.SceneCopyTargets;
import net.nvidium.shaders.pipeline.textures.PbrAtlasHooks;
import net.nvidium.shaders.pipeline.uniforms.PackOptionsUniforms;
import net.nvidium.shaders.pipeline.uniforms.SceneUniforms;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

/**
 * Provides Nvidium's mesh-shader translucent pass ({@code translucent/task.glsl + translucent/mesh.glsl + water_surface.frag}),
 * binding the feedback-loop-free scene color & depth copy textures for physical water refraction,
 * Beer-Lambert absorption, seabed caustics, SSR, and dielectric stained-glass/ice shading.
 */
public class WaterTranslucentPass implements IShaderPass {
    private final SceneCopyTargets sceneCopyTargets;
    private final CascadedShadowMap csm;
    private final SceneUniforms sceneUniforms;
    private final PackOptionsUniforms packOptionsUniforms;
    private final PbrAtlasHooks pbrHooks;

    private Shader customTranslucentShader;
    private boolean enabled = true;

    public WaterTranslucentPass(
            SceneCopyTargets sceneCopyTargets,
            CascadedShadowMap csm,
            SceneUniforms sceneUniforms,
            PackOptionsUniforms packOptionsUniforms,
            PbrAtlasHooks pbrHooks) {
        this.sceneCopyTargets = sceneCopyTargets;
        this.csm = csm;
        this.sceneUniforms = sceneUniforms;
        this.packOptionsUniforms = packOptionsUniforms;
        this.pbrHooks = pbrHooks;
    }

    @Override
    public void init() {
        ShaderPack pack = ShaderPackManager.get().getActivePack();
        this.customTranslucentShader = NvidiumShaderPrograms.compileTranslucentShader(pack);
    }

    @Override
    public void execute(RenderContext ctx) {
        if (!enabled) return;

        RenderTarget mcTarget = Minecraft.getInstance().getMainRenderTarget();

        // Snapshot opaque + entity + particle color & depth before translucent geometry renders
        sceneCopyTargets.copyFromFbo(mcTarget.frameBufferId, mcTarget.getDepthTextureId(), true, true, true);

        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mcTarget.frameBufferId);
        GL11C.glViewport(0, 0, ctx.getScreenWidth(), ctx.getScreenHeight());

        // Bind UBOs into NVIDIA Unified Memory address slots 3 and 4
        sceneUniforms.bindUnified();
        packOptionsUniforms.bindUnified();
    }

    public void bindTranslucentTextures() {
        pbrHooks.bindPbrTextures(2, 3);
        GL45C.glBindTextureUnit(4, sceneCopyTargets.getColorCopyTex());
        GL45C.glBindTextureUnit(5, sceneCopyTargets.getDepthCopyTex());
        csm.bindTextureArray(6);

        sceneUniforms.bindUnified();
        packOptionsUniforms.bindUnified();

        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    public Shader getCustomTranslucentShader() {
        return enabled ? customTranslucentShader : null;
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void destroy() {
        if (customTranslucentShader != null) {
            customTranslucentShader.delete();
            customTranslucentShader = null;
        }
    }

    @Override
    public String getName() {
        return "WaterTranslucentPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
