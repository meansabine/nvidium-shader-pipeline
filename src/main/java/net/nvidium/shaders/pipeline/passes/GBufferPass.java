package net.nvidium.shaders.pipeline.passes;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.nvidium.gl.shader.Shader;
import net.minecraft.client.Minecraft;
import net.nvidium.shaders.pack.NvidiumShaderPrograms;
import net.nvidium.shaders.pack.ShaderPack;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pipeline.gbuffer.GBuffer;
import net.nvidium.shaders.pipeline.textures.PbrAtlasHooks;
import net.nvidium.shaders.pipeline.uniforms.PackOptionsUniforms;
import net.nvidium.shaders.pipeline.uniforms.SceneUniforms;
import org.lwjgl.opengl.GL13C;

/**
 * Binds the MRT G-Buffer FBO and provides Nvidium's mesh-shader terrain program
 * ({@code task.glsl + mesh.glsl + terrain_gbuffer.frag}).
 */
public class GBufferPass implements IShaderPass {
    private final GBuffer gBuffer;
    private final SceneUniforms sceneUniforms;
    private final PackOptionsUniforms packOptionsUniforms;
    private final PbrAtlasHooks pbrHooks;

    private Shader customTerrainShader;
    private Shader customTemporalTerrainShader;
    private boolean enabled = true;

    public GBufferPass(
            GBuffer gBuffer,
            SceneUniforms sceneUniforms,
            PackOptionsUniforms packOptionsUniforms,
            PbrAtlasHooks pbrHooks) {
        this.gBuffer = gBuffer;
        this.sceneUniforms = sceneUniforms;
        this.packOptionsUniforms = packOptionsUniforms;
        this.pbrHooks = pbrHooks;
    }

    @Override
    public void init() {
        ShaderPack pack = ShaderPackManager.get().getActivePack();
        this.customTerrainShader = NvidiumShaderPrograms.compileTerrainGBufferShader(pack);
        this.customTemporalTerrainShader = NvidiumShaderPrograms.compileTemporalTerrainGBufferShader(pack);
    }

    @Override
    public void execute(RenderContext ctx) {
        if (!enabled) return;

        RenderTarget mcTarget = Minecraft.getInstance().getMainRenderTarget();
        gBuffer.bindForWriting(mcTarget.getDepthTextureId());
        gBuffer.clearColorAttachments();

        // Bind UBOs into NVIDIA Unified Memory address slots 3 and 4
        sceneUniforms.bindUnified();
        packOptionsUniforms.bindUnified();
    }

    public void bindLabPbrTextures() {
        pbrHooks.bindPbrTextures(2, 3);
        sceneUniforms.bindUnified();
        packOptionsUniforms.bindUnified();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }

    public Shader getCustomTerrainShader() {
        return enabled ? customTerrainShader : null;
    }

    public Shader getCustomTemporalTerrainShader() {
        return enabled ? customTemporalTerrainShader : null;
    }

    @Override
    public void resize(int width, int height) {
        gBuffer.resize(width, height);
    }

    @Override
    public void destroy() {
        if (customTerrainShader != null) {
            customTerrainShader.delete();
            customTerrainShader = null;
        }
        if (customTemporalTerrainShader != null) {
            customTemporalTerrainShader.delete();
            customTemporalTerrainShader = null;
        }
    }

    @Override
    public String getName() {
        return "GBufferPass";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
