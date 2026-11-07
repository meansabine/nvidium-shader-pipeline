package net.nvidium.shaders.pipeline.gl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL45C;

/**
 * Synchronizes raw OpenGL 4.5 DSA state changes (texture units, sampler objects, FBOs, VAOs,
 * depth/color masks) back with Mojang's Blaze3D {@link GlStateManager} software cache.
 *
 * <p>Without invalidating {@code GlStateManager.TEXTURES[unit].binding} after calling
 * {@code glBindTextureUnit(unit, tex)}, subsequent vanilla passes (such as {@code ParticleEngine}
 * rendering block-destruction particles on {@code TERRAIN_SHEET}) see a stale cached texture ID
 * in {@code GlStateManager._bindTexture} and skip rebinding the block atlas (Unit 0) and lightmap
 * (Unit 1/2), causing particles to sample intermediate G-Buffer / depth textures and turn red.
 */
public final class GlStateRestore {

    private GlStateRestore() {}

    /**
     * Restores FBO, viewport, VAO, shader program, sampler objects, and texture unit bindings (0..7)
     * so Blaze3D's cached state matches the actual OpenGL hardware state.
     */
    public static void restoreAfterPass(int width, int height) {
        RenderTarget mcTarget = Minecraft.getInstance().getMainRenderTarget();
        if (mcTarget != null) {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, mcTarget.frameBufferId);
        }
        if (width > 0 && height > 0) {
            GL11C.glViewport(0, 0, width, height);
        }

        GL20C.glUseProgram(0);
        GL30C.glBindVertexArray(0);
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();

        // Clear DSA texture bindings (including GL_TEXTURE_2D_ARRAY) and force GlStateManager's
        // per-unit texture cache to 0 so Blaze3D genuinely rebinds textures for entities & particles.
        for (int i = 7; i >= 0; i--) {
            GL45C.glBindTextureUnit(i, 0);
            GL33C.glBindSampler(i, 0);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + i);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GlStateManager._bindTexture(0);
        }

        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);

        GlStateManager._depthMask(true);
        GL11C.glDepthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        GL11C.glColorMask(true, true, true, true);
        GlStateManager._enableDepthTest();
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
        GlStateManager._depthFunc(GL11C.GL_LEQUAL);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GlStateManager._disableBlend();
        GL11C.glDisable(GL11C.GL_BLEND);
    }

    /**
     * Clears texture units 0..7 in both OpenGL and {@link GlStateManager} without altering FBO/depth state.
     */
    public static void invalidateTextureUnits() {
        for (int i = 7; i >= 0; i--) {
            GL45C.glBindTextureUnit(i, 0);
            GL33C.glBindSampler(i, 0);
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + i);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GlStateManager._bindTexture(0);
        }
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
    }
}
