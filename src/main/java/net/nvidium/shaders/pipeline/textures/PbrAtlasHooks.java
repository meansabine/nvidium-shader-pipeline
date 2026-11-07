package net.nvidium.shaders.pipeline.textures;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.nvidium.shaders.NvidiumShaderPipeline;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Provides LabPBR 1.3 normal ({@code _n}) and specular ({@code _s}) companion texture atlases
 * on texture units 2 & 3 during terrain and translucent rasterization.
 *
 * <p>Automatically inspects {@link TextureAtlas#LOCATION_BLOCKS} and queries {@link ResourceManager}
 * for {@code textures/<sprite_path>_n.png} and {@code textures/<sprite_path>_s.png}. When a LabPBR
 * resource pack is active, stitches companion DSA mipmapped atlases matching the exact UV layout
 * of the main block atlas. When no LabPBR textures are present, binds lightweight 1x1 neutral
 * fallback textures so zero extra VRAM is consumed.
 */
public class PbrAtlasHooks {
    private int defaultNormalTex;
    private int defaultSpecularTex;

    private int customNormalAtlasTex = 0;
    private int customSpecularAtlasTex = 0;
    private int lastBlockAtlasGlId = -1;
    private int lastAtlasWidth = -1;
    private int lastAtlasHeight = -1;
    private boolean hasCustomPbr = false;

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
        ensureAtlasSynced();
        int normTex = (hasCustomPbr && customNormalAtlasTex != 0) ? customNormalAtlasTex : defaultNormalTex;
        int specTex = (hasCustomPbr && customSpecularAtlasTex != 0) ? customSpecularAtlasTex : defaultSpecularTex;
        GL45C.glBindTextureUnit(normalUnit, normTex);
        GL45C.glBindTextureUnit(specularUnit, specTex);
    }

    private void ensureAtlasSynced() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getTextureManager() == null || mc.getResourceManager() == null) {
            return;
        }
        AbstractTexture tex = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (!(tex instanceof TextureAtlas blockAtlas)) {
            return;
        }
        int glId = blockAtlas.getId();
        if (glId <= 0) {
            return;
        }
        int w = GL45C.glGetTextureLevelParameteri(glId, 0, GL11C.GL_TEXTURE_WIDTH);
        int h = GL45C.glGetTextureLevelParameteri(glId, 0, GL11C.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) {
            return;
        }
        if (glId == lastBlockAtlasGlId && w == lastAtlasWidth && h == lastAtlasHeight) {
            return;
        }

        lastBlockAtlasGlId = glId;
        lastAtlasWidth = w;
        lastAtlasHeight = h;
        rebuildCompanionAtlases(blockAtlas, mc.getResourceManager(), w, h);
    }

    private void rebuildCompanionAtlases(TextureAtlas blockAtlas, ResourceManager rm, int atlasW, int atlasH) {
        deleteCustomAtlases();
        Collection<TextureAtlasSprite> sprites = extractSprites(blockAtlas);
        if (sprites.isEmpty()) {
            return;
        }

        // First quick pass: check if the active resource pack supplies any _n or _s textures
        boolean foundAny = false;
        for (TextureAtlasSprite sprite : sprites) {
            SpriteContents contents = sprite.contents();
            if (contents == null) continue;
            ResourceLocation id = contents.name();
            if (id == null) continue;
            ResourceLocation normLoc = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "textures/" + id.getPath() + "_n.png");
            ResourceLocation specLoc = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "textures/" + id.getPath() + "_s.png");
            if (rm.getResource(normLoc).isPresent() || rm.getResource(specLoc).isPresent()) {
                foundAny = true;
                break;
            }
        }

        if (!foundAny) {
            hasCustomPbr = false;
            return;
        }

        int mipLevels = Math.max(1, Math.min(5, 1 + (int) Math.floor(Math.log(Math.max(atlasW, atlasH)) / Math.log(2.0))));

        customNormalAtlasTex = createAtlasTexture(atlasW, atlasH, mipLevels);
        customSpecularAtlasTex = createAtlasTexture(atlasW, atlasH, mipLevels);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer normClear = stack.bytes((byte) 128, (byte) 128, (byte) 255, (byte) 255);
            GL45C.glClearTexImage(customNormalAtlasTex, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, normClear);

            ByteBuffer specClear = stack.bytes((byte) 0, (byte) 0, (byte) 0, (byte) 0);
            GL45C.glClearTexImage(customSpecularAtlasTex, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, specClear);
        }

        int normCount = 0;
        int specCount = 0;

        for (TextureAtlasSprite sprite : sprites) {
            SpriteContents contents = sprite.contents();
            if (contents == null) continue;
            ResourceLocation id = contents.name();
            if (id == null) continue;

            int x = sprite.getX();
            int y = sprite.getY();
            int sw = contents.width();
            int sh = contents.height();
            if (sw <= 0 || sh <= 0 || x < 0 || y < 0 || x + sw > atlasW || y + sh > atlasH) {
                continue;
            }

            ResourceLocation normLoc = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "textures/" + id.getPath() + "_n.png");
            if (uploadSpriteResource(rm, normLoc, customNormalAtlasTex, x, y, sw, sh)) {
                normCount++;
            }

            ResourceLocation specLoc = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "textures/" + id.getPath() + "_s.png");
            if (uploadSpriteResource(rm, specLoc, customSpecularAtlasTex, x, y, sw, sh)) {
                specCount++;
            }
        }

        if (mipLevels > 1) {
            GL45C.glGenerateTextureMipmap(customNormalAtlasTex);
            GL45C.glGenerateTextureMipmap(customSpecularAtlasTex);
        }

        hasCustomPbr = (normCount > 0 || specCount > 0);
        NvidiumShaderPipeline.LOGGER.info(
                "Stitched LabPBR 1.3 companion atlases ({}x{}, {} mips): {} normal (_n) and {} specular (_s) sprites.",
                atlasW, atlasH, mipLevels, normCount, specCount
        );
    }

    private static int createAtlasTexture(int w, int h, int mipLevels) {
        int tex = GL45C.glCreateTextures(GL11C.GL_TEXTURE_2D);
        GL45C.glTextureStorage2D(tex, mipLevels, GL11C.GL_RGBA8, w, h);
        GL45C.glTextureParameteri(tex, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_LINEAR);
        GL45C.glTextureParameteri(tex, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL45C.glTextureParameteri(tex, GL12C.GL_TEXTURE_MAX_LEVEL, mipLevels - 1);
        GL45C.glTextureParameteri(tex, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL45C.glTextureParameteri(tex, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        return tex;
    }

    private static boolean uploadSpriteResource(
            ResourceManager rm,
            ResourceLocation loc,
            int targetAtlasTex,
            int dstX,
            int dstY,
            int spriteW,
            int spriteH) {
        Optional<Resource> resOpt = rm.getResource(loc);
        if (resOpt.isEmpty()) {
            return false;
        }
        try (InputStream in = resOpt.get().open();
             NativeImage img = NativeImage.read(in)) {
            int imgW = img.getWidth();
            int imgH = img.getHeight();
            if (imgW <= 0 || imgH <= 0) {
                return false;
            }
            ByteBuffer uploadBuf = MemoryUtil.memAlloc(spriteW * spriteH * 4);
            try {
                for (int py = 0; py < spriteH; py++) {
                    int srcY = Math.min(imgH - 1, (py * imgW) / Math.max(1, spriteW));
                    if (imgW == spriteW && imgH >= spriteH) {
                        srcY = py;
                    }
                    for (int px = 0; px < spriteW; px++) {
                        int srcX = (imgW == spriteW) ? px : Math.min(imgW - 1, (px * imgW) / Math.max(1, spriteW));
                        // NativeImage.getPixelRGBA returns ABGR packed int in little-endian memory order (R, G, B, A bytes)
                        int abgr = img.getPixelRGBA(srcX, srcY);
                        int idx = (py * spriteW + px) * 4;
                        uploadBuf.putInt(idx, abgr);
                    }
                }
                GL45C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
                GL45C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
                GL45C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
                GL45C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
                GL45C.glTextureSubImage2D(
                        targetAtlasTex,
                        0,
                        dstX,
                        dstY,
                        spriteW,
                        spriteH,
                        GL11C.GL_RGBA,
                        GL11C.GL_UNSIGNED_BYTE,
                        uploadBuf
                );
                return true;
            } finally {
                MemoryUtil.memFree(uploadBuf);
            }
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<TextureAtlasSprite> extractSprites(TextureAtlas blockAtlas) {
        for (Field field : TextureAtlas.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    Object obj = field.get(blockAtlas);
                    if (obj instanceof Map<?, ?> map && !map.isEmpty()) {
                        Object firstVal = map.values().iterator().next();
                        if (firstVal instanceof TextureAtlasSprite) {
                            return (Collection<TextureAtlasSprite>) map.values();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return Collections.emptyList();
    }

    private void deleteCustomAtlases() {
        if (customNormalAtlasTex != 0) {
            GL11C.glDeleteTextures(customNormalAtlasTex);
            customNormalAtlasTex = 0;
        }
        if (customSpecularAtlasTex != 0) {
            GL11C.glDeleteTextures(customSpecularAtlasTex);
            customSpecularAtlasTex = 0;
        }
        hasCustomPbr = false;
    }

    public void delete() {
        deleteCustomAtlases();
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
