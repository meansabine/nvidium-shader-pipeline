package net.nvidium.shaders.pipeline.passes;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.nvidium.api0.INvidiumShaderHook;
import me.cortex.nvidium.gl.shader.Shader;
import net.nvidium.shaders.pack.NvidiumShaderPrograms;
import net.nvidium.shaders.pack.ShaderPack;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pipeline.gl.GlStateRestore;
import net.nvidium.shaders.pipeline.shadow.CascadedShadowMap;
import net.nvidium.shaders.pipeline.uniforms.PackOptionsUniforms;
import net.nvidium.shaders.pipeline.uniforms.SceneUniforms;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;

/**
 * Executes the 3-cascade shadow map pass using Nvidium's mesh-shader terrain geometry.
 */
public class ShadowPass implements IShaderPass {
    private final CascadedShadowMap csm;
    private final SceneUniforms sceneUniforms;
    private final PackOptionsUniforms packOptionsUniforms;
    private Shader shadowDepthShader;
    private boolean enabled = true;

    public ShadowPass(CascadedShadowMap csm, SceneUniforms sceneUniforms, PackOptionsUniforms packOptionsUniforms) {
        this.csm = csm;
        this.sceneUniforms = sceneUniforms;
        this.packOptionsUniforms = packOptionsUniforms;
    }

    @Override
    public void init() {
        ShaderPack pack = ShaderPackManager.get().getActivePack();
        this.shadowDepthShader = NvidiumShaderPrograms.compileShadowDepthShader(pack);
    }

    @Override
    public void execute(RenderContext ctx) {
        // Invoked via executeShadowCascades from NvidiumHookImpl.onShadowPass
    }

    public void executeShadowCascades(INvidiumShaderHook.ShadowPassContext shadowCtx, RenderContext ctx) {
        if (!enabled || shadowDepthShader == null || shadowCtx.regionCount() <= 0) {
            return;
        }

        // Update cascade matrices for the active light (Sun by day, Moon by night) anchored to world space
        csm.updateCascades(
                ctx.getCameraX(),
                ctx.getCameraY(),
                ctx.getCameraZ(),
                sceneUniforms.getShadowLightDirection()
        );

        sceneUniforms.updateCascadesInUbo(
                csm.getLightViewProjMatrix(0),
                csm.getLightViewProjMatrix(1),
                csm.getLightViewProjMatrix(2),
                csm.getCascadeSplit(0),
                csm.getCascadeSplit(1),
                csm.getCascadeSplit(2)
        );

        // Nvidium has GL_UNIFORM_BUFFER_UNIFIED_NV active during onShadowPass
        sceneUniforms.bindUnified();
        packOptionsUniforms.bindUnified();

        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GL11C.glDepthMask(true);
        GL11C.glColorMask(false, false, false, false);

        boolean wasCullEnabled = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        GL11C.glEnable(GL11C.GL_POLYGON_OFFSET_FILL);
        GL11C.glPolygonOffset(2.2f, 4.5f);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glEnable(org.lwjgl.opengl.GL32C.GL_DEPTH_CLAMP);

        for (int i = 0; i < CascadedShadowMap.NUM_CASCADES; i++) {
            csm.bindCascadeForWriting(i);
            shadowCtx.drawOpaqueTerrainDepth(shadowDepthShader, csm.getLightViewProjMatrix(i));
        }

        GL11C.glDisable(org.lwjgl.opengl.GL32C.GL_DEPTH_CLAMP);
        GL11C.glDisable(GL11C.GL_POLYGON_OFFSET_FILL);
        if (wasCullEnabled) {
            GL11C.glEnable(GL11C.GL_CULL_FACE);
        } else {
            GL11C.glDisable(GL11C.GL_CULL_FACE);
        }
        GL11C.glColorMask(true, true, true, true);
        GL11C.glViewport(0, 0, ctx.getScreenWidth(), ctx.getScreenHeight());

        GlStateRestore.invalidateTextureUnits();
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void destroy() {
        if (shadowDepthShader != null) {
            shadowDepthShader.delete();
            shadowDepthShader = null;
        }
    }

    @Override
    public String getName() {
        return "CascadedShadowMapPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
