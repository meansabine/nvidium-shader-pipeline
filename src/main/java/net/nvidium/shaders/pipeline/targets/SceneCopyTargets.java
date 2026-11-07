package net.nvidium.shaders.pipeline.targets;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

/**
 * Feedback-loop-free copy targets for scene color (with mipmaps for rough glass/ice refraction)
 * and scene depth (so translucent water/glass can read opaque depth while depth-testing against
 * Minecraft's main depth buffer).
 */
public class SceneCopyTargets {
    private int width;
    private int height;
    private int mipLevels;
    private int depthInternalFormat = GL11C.GL_DEPTH_COMPONENT;

    private int copyFbo;
    private int colorCopyTex;
    private int depthCopyTex;

    public SceneCopyTargets(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        init();
    }

    private void init() {
        this.mipLevels = Math.max(1, Math.min(5, 1 + (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2.0))));

        copyFbo = GL45C.glCreateFramebuffers();

        colorCopyTex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(colorCopyTex, mipLevels, GL11C.GL_RGBA8, width, height);
        GL45C.glTextureParameteri(colorCopyTex, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
        GL45C.glTextureParameteri(colorCopyTex, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
        GL45C.glTextureParameteri(colorCopyTex, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(colorCopyTex, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);

        GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_COLOR_ATTACHMENT0, colorCopyTex, 0);
        allocateDepthTexture(this.depthInternalFormat);
    }

    private void allocateDepthTexture(int internalFormat) {
        if (depthCopyTex != 0) {
            GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_DEPTH_ATTACHMENT, 0, 0);
            GL45C.glNamedFramebufferTexture(copyFbo, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, 0, 0);
            GL11C.glDeleteTextures(depthCopyTex);
        }
        int resolvedFormat = internalFormat;
        if (resolvedFormat == 0 || resolvedFormat == GL11C.GL_DEPTH_COMPONENT) {
            resolvedFormat = GL30C.GL_DEPTH_COMPONENT32F;
        } else if (resolvedFormat == GL30C.GL_DEPTH_STENCIL) {
            resolvedFormat = GL30C.GL_DEPTH24_STENCIL8;
        }
        this.depthInternalFormat = resolvedFormat;

        depthCopyTex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(depthCopyTex, 1, this.depthInternalFormat, width, height);
        GL45C.glTextureParameteri(depthCopyTex, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(depthCopyTex, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(depthCopyTex, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(depthCopyTex, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(depthCopyTex, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);

        boolean hasStencil = (this.depthInternalFormat == GL30C.GL_DEPTH24_STENCIL8
                || this.depthInternalFormat == GL30C.GL_DEPTH32F_STENCIL8);
        int attachment = hasStencil ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT;
        GL45C.glNamedFramebufferTexture(copyFbo, attachment, depthCopyTex, 0);
    }

    public void copyFromFbo(int srcFboId, int srcDepthTexId, boolean copyColor, boolean copyDepth, boolean generateColorMips) {
        if (copyColor) {
            GL45C.glBlitNamedFramebuffer(
                    srcFboId,
                    copyFbo,
                    0, 0, width, height,
                    0, 0, width, height,
                    GL11C.GL_COLOR_BUFFER_BIT,
                    GL11C.GL_NEAREST
            );
            if (generateColorMips && mipLevels > 1) {
                GL45C.glGenerateTextureMipmap(colorCopyTex);
            }
        }
        if (copyDepth) {
            if (srcDepthTexId > 0) {
                int srcFormat = GL45C.glGetTextureLevelParameteri(srcDepthTexId, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
                if (srcFormat != 0 && srcFormat != this.depthInternalFormat) {
                    allocateDepthTexture(srcFormat);
                }
            }
            GL45C.glBlitNamedFramebuffer(
                    srcFboId,
                    copyFbo,
                    0, 0, width, height,
                    0, 0, width, height,
                    GL11C.GL_DEPTH_BUFFER_BIT,
                    GL11C.GL_NEAREST
            );
        }
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
        if (copyFbo != 0) {
            GL30C.glDeleteFramebuffers(copyFbo);
            copyFbo = 0;
        }
        if (colorCopyTex != 0) {
            GL11C.glDeleteTextures(colorCopyTex);
            colorCopyTex = 0;
        }
        if (depthCopyTex != 0) {
            GL11C.glDeleteTextures(depthCopyTex);
            depthCopyTex = 0;
        }
    }

    public int getCopyFbo() { return copyFbo; }
    public int getColorCopyTex() { return colorCopyTex; }
    public int getDepthCopyTex() { return depthCopyTex; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
