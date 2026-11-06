package net.nvidium.shaders.pipeline.targets;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

/**
 * Manages intermediate HDR and temporal render targets using OpenGL 4.5 Direct State Access (DSA):
 * <ul>
 *   <li>{@code sceneFbo / sceneTex}: RGBA16F output of Deferred Lighting + Volumetric Fog (with mip pyramid for bloom)</li>
 *   <li>{@code fogFbo / fogTex}: RGBA16F raymarched volumetric light-shaft & fog buffer</li>
 *   <li>{@code historyFbo[2] / historyTex[2]}: RGBA16F ping-pong temporal accumulation (TAA) history buffers</li>
 * </ul>
 */
public class HdrTargets {
    private int width;
    private int height;
    private int mipLevels;

    private int sceneFbo;
    private int sceneTex;

    private int fogFbo;
    private int fogTex;

    private final int[] historyFbo = new int[2];
    private final int[] historyTex = new int[2];
    private int historyWriteIndex = 0;

    public HdrTargets(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        init();
    }

    private void init() {
        this.mipLevels = Math.max(1, Math.min(6, 1 + (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2.0))));

        sceneFbo = GL45C.glCreateFramebuffers();
        sceneTex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(sceneTex, mipLevels, GL30C.GL_RGBA16F, width, height);
        GL45C.glTextureParameteri(sceneTex, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
        GL45C.glTextureParameteri(sceneTex, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL45C.glTextureParameteri(sceneTex, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(sceneTex, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glNamedFramebufferTexture(sceneFbo, GL30C.GL_COLOR_ATTACHMENT0, sceneTex, 0);

        fogFbo = GL45C.glCreateFramebuffers();
        fogTex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(fogTex, 1, GL30C.GL_RGBA16F, width, height);
        GL45C.glTextureParameteri(fogTex, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
        GL45C.glTextureParameteri(fogTex, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL45C.glTextureParameteri(fogTex, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(fogTex, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glNamedFramebufferTexture(fogFbo, GL30C.GL_COLOR_ATTACHMENT0, fogTex, 0);

        for (int i = 0; i < 2; i++) {
            historyFbo[i] = GL45C.glCreateFramebuffers();
            historyTex[i] = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
            GL45C.glTextureStorage2D(historyTex[i], mipLevels, GL30C.GL_RGBA16F, width, height);
            GL45C.glTextureParameteri(historyTex[i], GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
            GL45C.glTextureParameteri(historyTex[i], GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL45C.glTextureParameteri(historyTex[i], GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL45C.glTextureParameteri(historyTex[i], GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL45C.glNamedFramebufferTexture(historyFbo[i], GL30C.GL_COLOR_ATTACHMENT0, historyTex[i], 0);
        }
    }

    public void generateSceneMipmaps() {
        if (sceneTex != 0 && mipLevels > 1) {
            GL45C.glGenerateTextureMipmap(sceneTex);
        }
    }

    public void generateCurrentHistoryMipmaps() {
        int tex = historyTex[historyWriteIndex];
        if (tex != 0 && mipLevels > 1) {
            GL45C.glGenerateTextureMipmap(tex);
        }
    }

    public int getCurrentHistoryWriteFbo() {
        return historyFbo[historyWriteIndex];
    }

    public int getCurrentHistoryWriteTex() {
        return historyTex[historyWriteIndex];
    }

    public int getPreviousHistoryReadTex() {
        return historyTex[1 - historyWriteIndex];
    }

    public void swapHistory() {
        historyWriteIndex = 1 - historyWriteIndex;
    }

    public void resize(int newWidth, int newHeight) {
        int w = Math.max(1, newWidth);
        int h = Math.max(1, newHeight);
        if (w == this.width && h == this.height) {
            return;
        }
        delete();
        this.width = w;
        this.height = h;
        init();
    }

    public void delete() {
        if (sceneFbo != 0) {
            GL30C.glDeleteFramebuffers(sceneFbo);
            sceneFbo = 0;
        }
        if (sceneTex != 0) {
            GL11C.glDeleteTextures(sceneTex);
            sceneTex = 0;
        }
        if (fogFbo != 0) {
            GL30C.glDeleteFramebuffers(fogFbo);
            fogFbo = 0;
        }
        if (fogTex != 0) {
            GL11C.glDeleteTextures(fogTex);
            fogTex = 0;
        }
        for (int i = 0; i < 2; i++) {
            if (historyFbo[i] != 0) {
                GL30C.glDeleteFramebuffers(historyFbo[i]);
                historyFbo[i] = 0;
            }
            if (historyTex[i] != 0) {
                GL11C.glDeleteTextures(historyTex[i]);
                historyTex[i] = 0;
            }
        }
    }

    public int getSceneFbo() { return sceneFbo; }
    public int getSceneTex() { return sceneTex; }
    public int getFogFbo() { return fogFbo; }
    public int getFogTex() { return fogTex; }
    public int getMipLevels() { return mipLevels; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
