package net.nvidium.shaders.pipeline.textures;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * Provides LabPBR 1.3 normal ({@code _n}) and specular ({@code _s}) texture bindings on units 2 & 3
 * during terrain and translucent rasterization, with neutral 1x1 DSA textures when the active resource
 * pack does not supply separate PBR atlases.
 */
public class PbrAtlasHooks {
    private int defaultNormalTex;
    private int defaultSpecularTex;

    public PbrAtlasHooks() {
        initFallbacks();
    }

    private void initFallbacks() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Neutral tangent normal (0.5, 0.5, 1.0, AO/Height = 1.0) -> (128, 128, 255, 255)
            ByteBuffer normPixel = stack.bytes((byte) 128, (byte) 128, (byte) 255, (byte) 255);
            defaultNormalTex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(defaultNormalTex, 1, GL11C.GL_RGBA8, 1, 1);
            GL45C.glTextureSubImage2D(defaultNormalTex, 0, 0, 0, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, normPixel);
            GL45C.glTextureParameteri(defaultNormalTex, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL45C.glTextureParameteri(defaultNormalTex, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL45C.glTextureParameteri(defaultNormalTex, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL45C.glTextureParameteri(defaultNormalTex, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

            // Neutral LabPBR specular (0, 0, 0, 0) -> signals shader to use procedural + material-ID PBR
            ByteBuffer specPixel = stack.bytes((byte) 0, (byte) 0, (byte) 0, (byte) 0);
            defaultSpecularTex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(defaultSpecularTex, 1, GL11C.GL_RGBA8, 1, 1);
            GL45C.glTextureSubImage2D(defaultSpecularTex, 0, 0, 0, 1, 1, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, specPixel);
            GL45C.glTextureParameteri(defaultSpecularTex, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL45C.glTextureParameteri(defaultSpecularTex, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL45C.glTextureParameteri(defaultSpecularTex, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL45C.glTextureParameteri(defaultSpecularTex, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        }
    }

    public void bindPbrTextures(int normalUnit, int specularUnit) {
        GL45C.glBindTextureUnit(normalUnit, defaultNormalTex);
        GL45C.glBindTextureUnit(specularUnit, defaultSpecularTex);
    }

    public void delete() {
        if (defaultNormalTex != 0) {
            GL11C.glDeleteTextures(defaultNormalTex);
            defaultNormalTex = 0;
        }
        if (defaultSpecularTex != 0) {
            GL11C.glDeleteTextures(defaultSpecularTex);
            defaultSpecularTex = 0;
        }
    }
}
