package net.nvidium.shaders.pipeline.uniforms;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.GlowSquid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.entity.projectile.Fireball;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nvidium.shaders.pipeline.passes.RenderContext;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.NVShaderBufferLoad;
import org.lwjgl.opengl.NVUniformBufferUnifiedMemory;
import org.lwjgl.opengl.NVVertexBufferUnifiedMemory;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Manages the std140 UBO at binding point 5 ({@code PointLightUniforms}) containing up to
 * {@link #MAX_LIGHTS} (64) dynamic colored point lights from emissive world blocks
 * (Soul Lanterns, Redstone Torches, Amethyst, Sea Lanterns, Froglights, Portals, Lava, Torches)
 * and dynamic glowing/burning entities.
 */
public class PointLightUniforms {
    public static final int BINDING_POINT = 5;
    public static final int MAX_LIGHTS = 64;
    // 1 metadata vec4 + 64 posRadius vec4 + 64 colorIntensity vec4 = 129 vec4s = 2064 bytes (padded to 2304)
    public static final int BUFFER_SIZE = 2304;

    private int uboId;
    private long gpuAddress;
    private final ByteBuffer rawBuffer;
    private final FloatBuffer floatBuffer;

    private final List<WorldPointLight> cachedBlockLights = new ArrayList<>(128);
    private final List<WorldPointLight> frameActiveLights = new ArrayList<>(128);
    private double lastScanCamX = Double.NaN;
    private double lastScanCamY = Double.NaN;
    private double lastScanCamZ = Double.NaN;
    private int framesSinceBlockScan = 999;

    private record WorldPointLight(
            double worldX,
            double worldY,
            double worldZ,
            float r,
            float g,
            float b,
            float radius,
            float intensity,
            double sortScore
    ) {}

    public PointLightUniforms() {
        this.rawBuffer = MemoryUtil.memCalloc(BUFFER_SIZE);
        this.floatBuffer = rawBuffer.asFloatBuffer();

        this.uboId = GL45C.glCreateBuffers();
        GL45C.glNamedBufferData(uboId, rawBuffer, GL15C.GL_DYNAMIC_DRAW);

        long[] addrOut = new long[1];
        NVShaderBufferLoad.glGetNamedBufferParameterui64vNV(uboId, NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV, addrOut);
        this.gpuAddress = addrOut[0];
        NVShaderBufferLoad.glMakeNamedBufferResidentNV(uboId, GL15C.GL_READ_ONLY);
    }

    public void updateFromFrame(RenderContext ctx, float renderTimeSec) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        double camX = ctx.getCameraX();
        double camY = ctx.getCameraY();
        double camZ = ctx.getCameraZ();

        if (level == null) {
            cachedBlockLights.clear();
            writeEmptyBuffer();
            return;
        }

        double moveDistSq = Double.isNaN(lastScanCamX)
                ? 999.0
                : (sq(camX - lastScanCamX) + sq(camY - lastScanCamY) + sq(camZ - lastScanCamZ));

        framesSinceBlockScan++;
        if (framesSinceBlockScan >= 8 || moveDistSq > 2.25) {
            framesSinceBlockScan = 0;
            lastScanCamX = camX;
            lastScanCamY = camY;
            lastScanCamZ = camZ;
            scanNearbyEmissiveBlocks(level, camX, camY, camZ);
        }

        frameActiveLights.clear();
        for (WorldPointLight bl : cachedBlockLights) {
            double d2 = sq(bl.worldX - camX) + sq(bl.worldY - camY) + sq(bl.worldZ - camZ);
            if (d2 <= sq(bl.radius + 16.0)) {
                double score = d2 / Math.max(0.25, bl.intensity * bl.radius);
                frameActiveLights.add(new WorldPointLight(
                        bl.worldX, bl.worldY, bl.worldZ,
                        bl.r, bl.g, bl.b,
                        bl.radius, bl.intensity,
                        score
                ));
            }
        }

        // Collect dynamic entity point lights every frame (smooth interpolated entity positions)
        float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
        scanNearbyGlowingEntities(level, mc.getCameraEntity(), camX, camY, camZ, partialTick);

        if (frameActiveLights.size() > 1) {
            frameActiveLights.sort(Comparator.comparingDouble(WorldPointLight::sortScore));
        }

        int count = Math.min(MAX_LIGHTS, frameActiveLights.size());
        floatBuffer.clear();

        // u_PointLightMeta (vec4 at float offset 0)
        floatBuffer.put(0, (float) count);
        floatBuffer.put(1, 28.0f);
        floatBuffer.put(2, 1.0f);
        floatBuffer.put(3, renderTimeSec);

        int posBase = 4;                  // offset 16 bytes
        int colBase = 4 + MAX_LIGHTS * 4; // offset 1040 bytes

        for (int i = 0; i < count; i++) {
            WorldPointLight light = frameActiveLights.get(i);
            float relX = (float) (light.worldX - camX);
            float relY = (float) (light.worldY - camY);
            float relZ = (float) (light.worldZ - camZ);

            int pIdx = posBase + i * 4;
            floatBuffer.put(pIdx, relX);
            floatBuffer.put(pIdx + 1, relY);
            floatBuffer.put(pIdx + 2, relZ);
            floatBuffer.put(pIdx + 3, light.radius);

            int cIdx = colBase + i * 4;
            floatBuffer.put(cIdx, light.r);
            floatBuffer.put(cIdx + 1, light.g);
            floatBuffer.put(cIdx + 2, light.b);
            floatBuffer.put(cIdx + 3, light.intensity);
        }

        GL45C.glNamedBufferSubData(uboId, 0, rawBuffer);
        bindStandard();
    }

    private void writeEmptyBuffer() {
        floatBuffer.put(0, 0.0f);
        floatBuffer.put(1, 0.0f);
        floatBuffer.put(2, 0.0f);
        floatBuffer.put(3, 0.0f);
        GL45C.glNamedBufferSubData(uboId, 0L, rawBuffer);
        bindStandard();
    }

    private void scanNearbyEmissiveBlocks(ClientLevel level, double camX, double camY, double camZ) {
        cachedBlockLights.clear();

        int centerBlockX = (int) Math.floor(camX);
        int centerBlockY = (int) Math.floor(camY);
        int centerBlockZ = (int) Math.floor(camZ);

        int radiusXZ = 24;
        int radiusY = 14;

        int minChunkX = (centerBlockX - radiusXZ) >> 4;
        int maxChunkX = (centerBlockX + radiusXZ) >> 4;
        int minChunkZ = (centerBlockZ - radiusXZ) >> 4;
        int maxChunkZ = (centerBlockZ + radiusXZ) >> 4;

        int minSectionY = Math.max(level.getMinSection(), (centerBlockY - radiusY) >> 4);
        int maxSectionY = Math.min(level.getMaxSection() - 1, (centerBlockY + radiusY) >> 4);

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;

                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    int secIdx = chunk.getSectionIndexFromSectionY(sy);
                    LevelChunkSection[] sections = chunk.getSections();
                    if (secIdx < 0 || secIdx >= sections.length) continue;

                    LevelChunkSection section = sections[secIdx];
                    if (section == null || section.hasOnlyAir()) continue;
                    if (!section.maybeHas(state -> state.getLightEmission() >= 6)) continue;

                    int baseX = cx << 4;
                    int baseY = sy << 4;
                    int baseZ = cz << 4;

                    for (int ly = 0; ly < 16; ly++) {
                        int wy = baseY + ly;
                        if (Math.abs(wy - centerBlockY) > radiusY) continue;

                        for (int lz = 0; lz < 16; lz++) {
                            int wz = baseZ + lz;
                            if (Math.abs(wz - centerBlockZ) > radiusXZ) continue;

                            for (int lx = 0; lx < 16; lx++) {
                                int wx = baseX + lx;
                                if (Math.abs(wx - centerBlockX) > radiusXZ) continue;

                                BlockState state = section.getBlockState(lx, ly, lz);
                                int emission = state.getLightEmission();
                                if (emission < 6) continue;

                                double bx = wx + 0.5;
                                double by = wy + 0.5;
                                double bz = wz + 0.5;

                                // Coalesce contiguous clusters of identical light blocks (lava pools, portals, glowstone veins)
                                if (isNearExistingCluster(bx, by, bz)) {
                                    continue;
                                }

                                LightClassification cls = classifyBlockLight(state, emission);
                                double distSq = sq(bx - camX) + sq(by - camY) + sq(bz - camZ);
                                cachedBlockLights.add(new WorldPointLight(
                                        bx, by, bz,
                                        cls.r, cls.g, cls.b,
                                        cls.radius, cls.intensity,
                                        distSq
                                ));
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isNearExistingCluster(double x, double y, double z) {
        for (int i = cachedBlockLights.size() - 1; i >= Math.max(0, cachedBlockLights.size() - 24); i--) {
            WorldPointLight existing = cachedBlockLights.get(i);
            double d2 = sq(existing.worldX - x) + sq(existing.worldY - y) + sq(existing.worldZ - z);
            if (d2 < 3.1) {
                return true;
            }
        }
        return false;
    }

    private void scanNearbyGlowingEntities(
            ClientLevel level,
            Entity cameraEntity,
            double camX,
            double camY,
            double camZ,
            float partialTick) {
        AABB searchBox = new AABB(camX - 24.0, camY - 16.0, camZ - 24.0, camX + 24.0, camY + 16.0, camZ + 24.0);
        List<Entity> entities = level.getEntities((Entity) null, searchBox, e -> e != null && e.isAlive());
        for (Entity entity : entities) {
            if (entity == cameraEntity) {
                // Camera player's handheld light is already evaluated smoothly via u_HeldLightColor
                continue;
            }

            float r = 0.0f, g = 0.0f, b = 0.0f, radius = 0.0f, intensity = 0.0f;

            if (entity instanceof Blaze || entity instanceof Fireball || entity.isOnFire()) {
                r = 1.00f; g = 0.48f; b = 0.12f; radius = 9.5f; intensity = 2.4f;
            } else if (entity instanceof MagmaCube) {
                r = 1.00f; g = 0.35f; b = 0.08f; radius = 7.5f; intensity = 1.8f;
            } else if (entity instanceof GlowSquid) {
                r = 0.22f; g = 0.95f; b = 0.88f; radius = 8.5f; intensity = 2.1f;
            } else if (entity instanceof Vex) {
                r = 0.45f; g = 0.78f; b = 1.00f; radius = 6.5f; intensity = 1.5f;
            } else if (entity instanceof EndCrystal || entity instanceof DragonFireball) {
                r = 0.85f; g = 0.25f; b = 1.00f; radius = 11.0f; intensity = 2.6f;
            } else if (entity instanceof SpectralArrow) {
                r = 1.00f; g = 0.88f; b = 0.35f; radius = 6.5f; intensity = 1.6f;
            } else if (entity instanceof LivingEntity living) {
                LightClassification main = classifyItemLight(living.getMainHandItem());
                LightClassification off = classifyItemLight(living.getOffhandItem());
                LightClassification best = (main != null && (off == null || main.intensity >= off.intensity)) ? main : off;
                if (best != null) {
                    r = best.r; g = best.g; b = best.b; radius = best.radius; intensity = best.intensity;
                }
            }

            if (intensity > 0.05f && radius > 0.5f) {
                Vec3 pos = entity.getPosition(partialTick);
                double ex = pos.x;
                double ey = pos.y + entity.getBbHeight() * 0.55;
                double ez = pos.z;
                double d2 = sq(ex - camX) + sq(ey - camY) + sq(ez - camZ);
                double score = d2 / Math.max(0.25, intensity * radius * 1.5);
                frameActiveLights.add(new WorldPointLight(ex, ey, ez, r, g, b, radius, intensity, score));
            }
        }
    }

    private record LightClassification(float r, float g, float b, float radius, float intensity) {}

    private static LightClassification classifyItemLight(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Item item = stack.getItem();
        if (item == Items.LAVA_BUCKET) {
            return new LightClassification(1.00f, 0.42f, 0.10f, 10.5f, 2.5f);
        }
        if (item instanceof BlockItem blockItem) {
            BlockState state = blockItem.getBlock().defaultBlockState();
            int em = state.getLightEmission();
            if (em >= 6) {
                return classifyBlockLight(state, em);
            }
        }
        return null;
    }

    private static LightClassification classifyBlockLight(BlockState state, int emission) {
        Block block = state.getBlock();
        float normEm = Math.max(0.35f, emission / 15.0f);
        float baseRadius = 4.5f + normEm * 6.5f;
        float baseIntensity = normEm * 2.15f;

        // 1. Soul Fire / Soul Torches / Soul Lanterns / Soul Campfires (Cyan-Blue)
        if (block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH
                || block == Blocks.SOUL_LANTERN || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.SOUL_FIRE) {
            return new LightClassification(0.20f, 0.85f, 1.00f, baseRadius * 1.15f, baseIntensity * 1.35f);
        }

        // 2. Redstone Torches / Redstone Lamps / Lit Redstone Ore (Crimson-Red)
        if (block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH
                || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return new LightClassification(1.00f, 0.16f, 0.10f, 7.5f, 1.85f);
        }
        if (block == Blocks.REDSTONE_LAMP) {
            return new LightClassification(1.00f, 0.58f, 0.24f, baseRadius, baseIntensity);
        }

        // 3. Sea Lantern / Conduit / Sculk / Glow Lichen (Aqua-Teal)
        if (block == Blocks.SEA_LANTERN || block == Blocks.CONDUIT
                || block == Blocks.SCULK_CATALYST || block == Blocks.SCULK_SENSOR
                || block == Blocks.CALIBRATED_SCULK_SENSOR || block == Blocks.GLOW_LICHEN) {
            return new LightClassification(0.32f, 0.92f, 0.98f, baseRadius * 1.1f, baseIntensity * 1.25f);
        }

        // 4. Verdant Froglight (Emerald-Green)
        if (block == Blocks.VERDANT_FROGLIGHT) {
            return new LightClassification(0.40f, 1.00f, 0.52f, baseRadius * 1.15f, baseIntensity * 1.3f);
        }

        // 5. Pearlescent Froglight / End Rod / Amethyst / Crying Obsidian / Respawn Anchor (Violet-Magenta)
        if (block == Blocks.PEARLESCENT_FROGLIGHT || block == Blocks.END_ROD
                || block == Blocks.AMETHYST_CLUSTER || block == Blocks.LARGE_AMETHYST_BUD
                || block == Blocks.CRYING_OBSIDIAN || block == Blocks.RESPAWN_ANCHOR) {
            return new LightClassification(0.84f, 0.46f, 1.00f, baseRadius * 1.1f, baseIntensity * 1.25f);
        }

        // 6. Nether Portal / End Portal / End Gateway (Electric Purple)
        if (block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY) {
            return new LightClassification(0.74f, 0.20f, 1.00f, 10.5f, 2.4f);
        }

        // 7. Lava / Magma / Fire / Campfire (Molten Orange-Red)
        if (block == Blocks.LAVA || block == Blocks.MAGMA_BLOCK
                || block == Blocks.FIRE || block == Blocks.CAMPFIRE) {
            return new LightClassification(1.00f, 0.42f, 0.10f, baseRadius * 1.1f, baseIntensity * 1.2f);
        }

        // 8. Ochre Froglight / Glowstone / Shroomlight / Beacon / Jack o'Lantern (Golden-Amber)
        if (block == Blocks.OCHRE_FROGLIGHT || block == Blocks.GLOWSTONE
                || block == Blocks.SHROOMLIGHT || block == Blocks.JACK_O_LANTERN) {
            return new LightClassification(1.00f, 0.76f, 0.34f, baseRadius, baseIntensity);
        }
        if (block == Blocks.BEACON) {
            return new LightClassification(0.55f, 0.96f, 1.00f, 12.5f, 2.6f);
        }

        // Check block registry name for modded colored lamps/torches
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        if (key != null) {
            String path = key.getPath().toLowerCase(Locale.ROOT);
            if (path.contains("soul") || path.contains("cyan") || path.contains("blue")) {
                return new LightClassification(0.24f, 0.82f, 1.00f, baseRadius, baseIntensity * 1.2f);
            }
            if (path.contains("amethyst") || path.contains("purple") || path.contains("magenta") || path.contains("ender")) {
                return new LightClassification(0.82f, 0.38f, 1.00f, baseRadius, baseIntensity * 1.2f);
            }
            if (path.contains("green") || path.contains("emerald") || path.contains("slime")) {
                return new LightClassification(0.38f, 0.96f, 0.45f, baseRadius, baseIntensity * 1.2f);
            }
            if (path.contains("redstone") || path.contains("crimson") || path.contains("red")) {
                return new LightClassification(1.00f, 0.22f, 0.14f, baseRadius, baseIntensity * 1.2f);
            }
        }

        // Default warm torch / lantern / candle tungsten-orange
        return new LightClassification(1.00f, 0.68f, 0.34f, baseRadius, baseIntensity);
    }

    private static double sq(double v) {
        return v * v;
    }

    public void bindStandard() {
        GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, BINDING_POINT, uboId);
    }

    public void bindUnified() {
        if (gpuAddress != 0L) {
            NVVertexBufferUnifiedMemory.glBufferAddressRangeNV(
                    NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_ADDRESS_NV,
                    BINDING_POINT,
                    gpuAddress,
                    BUFFER_SIZE
            );
        }
    }

    public void delete() {
        if (uboId != 0) {
            NVShaderBufferLoad.glMakeNamedBufferNonResidentNV(uboId);
            GL15C.glDeleteBuffers(uboId);
            uboId = 0;
            gpuAddress = 0L;
        }
        if (rawBuffer != null) {
            MemoryUtil.memFree(rawBuffer);
        }
    }
}
