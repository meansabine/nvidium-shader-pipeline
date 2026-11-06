package net.nvidium.shaders.pipeline.uniforms;

import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.nvidium.shaders.config.PipelineConfig;
import net.nvidium.shaders.pipeline.passes.RenderContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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
import java.util.Locale;

/**
 * Manages the std140 SceneUniforms UBO at binding point 3 (1024 bytes).
 * Supports both standard OpenGL UBO binding ({@code glBindBufferBase}) and NVIDIA
 * Unified Memory UBO binding ({@code glBufferAddressRangeNV(GL_UNIFORM_BUFFER_ADDRESS_NV, 3, ...)}).
 */
public class SceneUniforms {
    public static final int BINDING_POINT = 3;
    public static final int BUFFER_SIZE = 1024;

    private int uboId;
    private long gpuAddress;
    private final ByteBuffer rawBuffer;
    private final FloatBuffer floatBuffer;

    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projMatrix = new Matrix4f();
    private final Matrix4f viewProjMatrix = new Matrix4f();
    private final Matrix4f invViewProjMatrix = new Matrix4f();
    private final Matrix4f prevViewProjMatrix = new Matrix4f();

    private final Matrix4f[] cascadeViewProj = new Matrix4f[] {
            new Matrix4f(), new Matrix4f(), new Matrix4f()
    };
    private final float[] cascadeSplits = new float[] { 16.0f, 48.0f, 160.0f, 160.0f };

    private final Vector3f sunDirection = new Vector3f(0.3f, 0.85f, 0.25f).normalize();
    private final Vector3f moonDirection = new Vector3f(-0.3f, -0.85f, -0.25f).normalize();
    private final Vector3f shadowLightDirection = new Vector3f(0.3f, 0.85f, 0.25f).normalize();
    private float shadowFade = 1.0f;

    private double prevCamX = Double.NaN;
    private double prevCamY = Double.NaN;
    private double prevCamZ = Double.NaN;
    private boolean hasPrevFrame = false;

    private long startTimeNanos = System.nanoTime();
    private long lastFrameNanos = System.nanoTime();
    private int frameCounter = 0;

    // Smoothed environmental state
    private float smoothedWetness = 0.0f;
    private float smoothedEyeSkyLight = 1.0f;
    private float smoothedEyeBlockLight = 0.0f;
    private float smoothedHeldLight = 0.0f;
    private final Vector3f smoothedHeldColor = new Vector3f(1.0f, 0.72f, 0.38f);

    public SceneUniforms() {
        this.rawBuffer = MemoryUtil.memCalloc(BUFFER_SIZE);
        this.floatBuffer = rawBuffer.asFloatBuffer();

        this.uboId = GL45C.glCreateBuffers();
        GL45C.glNamedBufferData(uboId, rawBuffer, GL15C.GL_DYNAMIC_DRAW);

        long[] addrOut = new long[1];
        NVShaderBufferLoad.glGetNamedBufferParameterui64vNV(uboId, NVShaderBufferLoad.GL_BUFFER_GPU_ADDRESS_NV, addrOut);
        this.gpuAddress = addrOut[0];
        NVShaderBufferLoad.glMakeNamedBufferResidentNV(uboId, GL15C.GL_READ_ONLY);
    }

    public void updateFromFrame(RenderContext ctx, PackOptionsUniforms packOptionsUbo) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        PipelineConfig cfg = PipelineConfig.get();

        long now = System.nanoTime();
        float renderTimeSec = (float) ((now - startTimeNanos) * 1.0e-9);
        float dt = Math.max(0.0005f, Math.min(0.1f, (float) ((now - lastFrameNanos) * 1.0e-9)));
        lastFrameNanos = now;

        // 1. Camera-relative matrices
        ChunkRenderMatrices crm = ctx.getChunkRenderMatrices();
        if (crm != null) {
            viewMatrix.set(crm.modelView());
            projMatrix.set(crm.projection());
        } else {
            viewMatrix.identity();
            projMatrix.identity();
        }
        projMatrix.mul(viewMatrix, viewProjMatrix);
        viewProjMatrix.invert(invViewProjMatrix);

        double camX = ctx.getCameraX();
        double camY = ctx.getCameraY();
        double camZ = ctx.getCameraZ();

        float camDeltaX = 0.0f;
        float camDeltaY = 0.0f;
        float camDeltaZ = 0.0f;
        if (hasPrevFrame && !Double.isNaN(prevCamX)) {
            camDeltaX = (float) (camX - prevCamX);
            camDeltaY = (float) (camY - prevCamY);
            camDeltaZ = (float) (camZ - prevCamZ);
        } else {
            prevViewProjMatrix.set(viewProjMatrix);
        }

        // 2. Time of day & Celestial directions (Sun & Moon)
        float dayTime01 = 0.25f;
        float rainStrength = 0.0f;
        float thunderStrength = 0.0f;
        float moonPhaseFactor = 0.75f;
        int dimensionId = 0; // 0 = Overworld, -1 = Nether, 1 = End

        if (level != null) {
            float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
            double exactDayTicks = (level.getDayTime() + (double) partialTick) % 24000.0;
            if (exactDayTicks < 0.0) exactDayTicks += 24000.0;
            dayTime01 = (float) (exactDayTicks / 24000.0);
            rainStrength = level.getRainLevel(partialTick);
            thunderStrength = level.getThunderLevel(partialTick);
            int moonPhase = level.getMoonPhase();
            moonPhaseFactor = 0.30f + 0.70f * (1.0f - Math.abs((moonPhase % 8) - 4) / 4.0f);

            if (level.dimension() == Level.NETHER) {
                dimensionId = -1;
            } else if (level.dimension() == Level.END) {
                dimensionId = 1;
            }
        }

        // Smooth wetness accumulation & drying
        float wetnessTarget = rainStrength;
        float wetnessRate = wetnessTarget > smoothedWetness ? dt * 0.35f : dt * 0.08f;
        smoothedWetness = Mth.clamp(Mth.lerp(Math.min(1.0f, wetnessRate), smoothedWetness, wetnessTarget), 0.0f, 1.0f);

        // Compute physical sun & moon trajectories (with slight orbital tilt for rich 3D shadows)
        // In Minecraft: dayTicks=0 is sunrise (+X), 6000 is noon (+Y), 12000 is sunset (-X), 18000 is midnight (-Y)
        float sunAngleRad = dayTime01 * ((float) Math.PI * 2.0f);
        float sx = (float) Math.cos(sunAngleRad);
        float sy = (float) Math.sin(sunAngleRad);
        float sz = 0.24f * (float) Math.sin(sunAngleRad * 0.5f + 0.35f);
        sunDirection.set(sx, sy, sz).normalize();
        moonDirection.set(-sunDirection.x, -sunDirection.y, -sunDirection.z);

        // Active shadow-casting light: Sun when sunDirection.y >= 0, Moon when sunDirection.y < 0
        if (dimensionId == 0) {
            if (sunDirection.y >= 0.0f) {
                shadowLightDirection.set(sunDirection);
                shadowFade = Mth.clamp(sunDirection.y / 0.14f, 0.0f, 1.0f);
            } else {
                shadowLightDirection.set(moonDirection);
                shadowFade = Mth.clamp(moonDirection.y / 0.14f, 0.0f, 1.0f) * 0.85f;
            }
            // Clamp minimum elevation angle for stable shadow ortho projection
            if (shadowLightDirection.y < 0.08f) {
                shadowLightDirection.y = 0.08f;
                shadowLightDirection.normalize();
            }
        } else {
            shadowLightDirection.set(0.25f, 0.92f, 0.28f).normalize();
            shadowFade = dimensionId == 1 ? 0.65f : 0.0f;
        }

        float sunElevation = sunDirection.y;
        float dayFactor = Mth.clamp((sunElevation + 0.12f) / 0.38f, 0.0f, 1.0f);
        float sunsetFactor = Mth.clamp(1.0f - Math.abs(sunElevation - 0.04f) / 0.24f, 0.0f, 1.0f)
                * (1.0f - rainStrength * 0.75f);
        float nightFactor = 1.0f - dayFactor;
        float stormDim = 1.0f - rainStrength * 0.58f - thunderStrength * 0.22f;

        // Sun & Moon physical colors and intensities
        float sunR = Mth.lerp(sunsetFactor, 1.00f, 1.00f);
        float sunG = Mth.lerp(sunsetFactor, 0.95f, 0.54f);
        float sunB = Mth.lerp(sunsetFactor, 0.88f, 0.26f);
        float sunIntensity = Mth.clamp((sunElevation + 0.05f) / 0.25f, 0.0f, 1.0f) * 2.85f * stormDim;

        float moonR = 0.46f;
        float moonG = 0.62f;
        float moonB = 0.95f;
        float moonIntensity = Mth.clamp((moonDirection.y + 0.05f) / 0.25f, 0.0f, 1.0f)
                * (0.42f * moonPhaseFactor) * stormDim;

        // Sky zenith & horizon colors
        float skyR = Mth.lerp(dayFactor, 0.025f, 0.22f);
        float skyG = Mth.lerp(dayFactor, 0.045f, 0.48f);
        float skyB = Mth.lerp(dayFactor, 0.110f, 0.92f);
        skyR = Mth.lerp(rainStrength, skyR, 0.22f) * stormDim;
        skyG = Mth.lerp(rainStrength, skyG, 0.25f) * stormDim;
        skyB = Mth.lerp(rainStrength, skyB, 0.30f) * stormDim;

        float horR = Mth.lerp(dayFactor, 0.05f, 0.55f);
        float horG = Mth.lerp(dayFactor, 0.08f, 0.72f);
        float horB = Mth.lerp(dayFactor, 0.16f, 0.95f);
        horR = Mth.lerp(sunsetFactor, horR, 1.00f);
        horG = Mth.lerp(sunsetFactor, horG, 0.46f);
        horB = Mth.lerp(sunsetFactor, horB, 0.20f);
        horR = Mth.lerp(rainStrength, horR, 0.28f) * stormDim;
        horG = Mth.lerp(rainStrength, horG, 0.31f) * stormDim;
        horB = Mth.lerp(rainStrength, horB, 0.36f) * stormDim;

        if (dimensionId == -1) {
            // Nether atmosphere
            skyR = 0.32f; skyG = 0.06f; skyB = 0.03f;
            horR = 0.55f; horG = 0.14f; horB = 0.05f;
            sunIntensity = 0.0f;
            moonIntensity = 0.0f;
        } else if (dimensionId == 1) {
            // End atmosphere
            skyR = 0.05f; skyG = 0.03f; skyB = 0.09f;
            horR = 0.14f; horG = 0.08f; horB = 0.22f;
            sunIntensity = 0.0f;
            moonIntensity = 0.55f;
        }

        // 3. Camera Submersion, Eye Light, Night Vision, and Handheld Dynamic Light
        float eyeSubmersion = 0.0f;
        Camera camera = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        if (camera != null) {
            FogType fogType = camera.getFluidInCamera();
            if (fogType == FogType.WATER) {
                eyeSubmersion = 1.0f;
            } else if (fogType == FogType.LAVA) {
                eyeSubmersion = 2.0f;
            } else if (fogType == FogType.POWDER_SNOW) {
                eyeSubmersion = 3.0f;
            }
        }

        float targetEyeSky = 1.0f;
        float targetEyeBlock = 0.0f;
        float nightVision = 0.0f;
        float targetHeldLight = 0.0f;
        Vector3f targetHeldColor = new Vector3f(1.0f, 0.72f, 0.38f);

        if (level != null) {
            BlockPos eyePos = BlockPos.containing(camX, camY, camZ);
            targetEyeSky = level.getBrightness(LightLayer.SKY, eyePos) / 15.0f;
            targetEyeBlock = level.getBrightness(LightLayer.BLOCK, eyePos) / 15.0f;
        }

        Entity camEntity = mc.getCameraEntity();
        if (camEntity instanceof LivingEntity living) {
            if (living.hasEffect(MobEffects.NIGHT_VISION)) {
                nightVision = 1.0f;
            }
            HeldLightInfo mainHeld = evaluateHeldItemLight(living.getMainHandItem());
            HeldLightInfo offHeld = evaluateHeldItemLight(living.getOffhandItem());
            HeldLightInfo chosen = mainHeld.level >= offHeld.level ? mainHeld : offHeld;
            targetHeldLight = chosen.level;
            if (chosen.level > 0.01f) {
                targetHeldColor.set(chosen.r, chosen.g, chosen.b);
            }
        }

        float envLerp = Math.min(1.0f, dt * 8.0f);
        smoothedEyeSkyLight = Mth.lerp(envLerp, smoothedEyeSkyLight, targetEyeSky);
        smoothedEyeBlockLight = Mth.lerp(envLerp, smoothedEyeBlockLight, targetEyeBlock);
        smoothedHeldLight = Mth.lerp(envLerp, smoothedHeldLight, targetHeldLight);
        smoothedHeldColor.lerp(targetHeldColor, envLerp);

        float lightningFlash = (level != null && level.getSkyFlashTime() > 0) ? 1.0f : 0.0f;

        // 4. Write all uniforms into FloatBuffer (std140 layout)
        floatBuffer.clear();

        // [0..16] u_ViewMatrix (offset 0)
        viewMatrix.get(0, floatBuffer);
        // [16..32] u_ProjMatrix (offset 64)
        projMatrix.get(16, floatBuffer);
        // [32..48] u_ViewProjMatrix (offset 128)
        viewProjMatrix.get(32, floatBuffer);
        // [48..64] u_InvViewProjMatrix (offset 192)
        invViewProjMatrix.get(48, floatBuffer);
        // [64..80] u_PrevViewProjMatrix (offset 256)
        prevViewProjMatrix.get(64, floatBuffer);

        // [80..96] u_CascadeViewProj0 (offset 320)
        cascadeViewProj[0].get(80, floatBuffer);
        // [96..112] u_CascadeViewProj1 (offset 384)
        cascadeViewProj[1].get(96, floatBuffer);
        // [112..128] u_CascadeViewProj2 (offset 448)
        cascadeViewProj[2].get(112, floatBuffer);

        // [128..132] u_CascadeSplits (offset 512)
        putVec4(128, cascadeSplits[0], cascadeSplits[1], cascadeSplits[2], cascadeSplits[3]);

        // [132..136] u_SunDirection (offset 528)
        putVec4(132, sunDirection.x, sunDirection.y, sunDirection.z, sunElevation);
        // [136..140] u_SunColor (offset 544)
        putVec4(136, sunR, sunG, sunB, sunIntensity);
        // [140..144] u_MoonDirection (offset 560)
        putVec4(140, moonDirection.x, moonDirection.y, moonDirection.z, moonPhaseFactor);
        // [144..148] u_MoonColor (offset 576)
        putVec4(144, moonR, moonG, moonB, moonIntensity);
        // [148..152] u_ShadowLightDir (offset 592)
        putVec4(148, shadowLightDirection.x, shadowLightDirection.y, shadowLightDirection.z, shadowFade);

        // [152..156] u_SkyColor (offset 608)
        putVec4(152, skyR, skyG, skyB, dayFactor);
        // [156..160] u_HorizonColor (offset 624)
        putVec4(156, horR, horG, horB, sunsetFactor);
        // [160..164] u_BlockLightColor (offset 640)
        putVec4(160, 1.00f, 0.68f, 0.36f, 1.35f);

        // [164..168] u_CameraPos (offset 656)
        putVec4(164, (float) camX, (float) camY, (float) camZ, eyeSubmersion);

        // [168..172] u_ScreenSize (offset 672)
        float w = Math.max(1, ctx.getScreenWidth());
        float h = Math.max(1, ctx.getScreenHeight());
        putVec4(168, w, h, 1.0f / w, 1.0f / h);

        // [172..176] u_Time (offset 688)
        putVec4(172, renderTimeSec, dayTime01, (float) (frameCounter++), dt);

        // [176..180] u_Weather (offset 704)
        putVec4(176, rainStrength, smoothedWetness, thunderStrength, lightningFlash);

        // [180..184] u_Environment (offset 720)
        putVec4(180, smoothedEyeSkyLight, smoothedEyeBlockLight, smoothedHeldLight, nightVision);

        // [184..188] u_HeldLightColor (offset 736)
        putVec4(184, smoothedHeldColor.x, smoothedHeldColor.y, smoothedHeldColor.z, Math.max(4.0f, smoothedHeldLight * 1.15f));

        // [188..192] u_FogColor (offset 752)
        float[] fogCol = RenderSystem.getShaderFogColor();
        putVec4(188,
                fogCol != null && fogCol.length > 0 ? fogCol[0] : horR,
                fogCol != null && fogCol.length > 1 ? fogCol[1] : horG,
                fogCol != null && fogCol.length > 2 ? fogCol[2] : horB,
                nightFactor);

        // [192..196] u_FogParams (offset 768)
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = Math.max(fogStart + 16.0f, RenderSystem.getShaderFogEnd());
        putVec4(192, fogStart, fogEnd, cfg.volumetricFog ? 1.0f : 0.0f, (float) dimensionId);

        // [196..200] u_PostParams (offset 784)
        putVec4(196, cfg.exposure, cfg.bloomIntensity, cfg.saturation, (float) cfg.tonemapOperator);

        // [200..204] u_ShadowParams (offset 800)
        float sRes = Math.max(512, cfg.shadowResolution);
        putVec4(200, sRes, 1.0f / sRes, cfg.ssrEnabled ? 1.0f : 0.0f, cfg.gtaoEnabled ? 1.0f : 0.0f);

        // [204..208] u_DebugParams (offset 816)
        putVec4(204, (float) cfg.debugMode, cfg.taaEnabled ? 1.0f : 0.0f, cfg.casSharpening, hasPrevFrame ? 0.90f : 0.0f);

        // [208..212] u_CameraDelta (offset 832)
        putVec4(208, camDeltaX, camDeltaY, camDeltaZ, hasPrevFrame ? 1.0f : 0.0f);

        uploadAndBind();

        if (packOptionsUbo != null) {
            packOptionsUbo.updateAndBind();
        }

        // Store current matrices & camera pos for next frame's temporal reprojection
        prevViewProjMatrix.set(viewProjMatrix);
        prevCamX = camX;
        prevCamY = camY;
        prevCamZ = camZ;
        hasPrevFrame = true;
    }

    public void updateCascadesInUbo(Matrix4f c0, Matrix4f c1, Matrix4f c2, float s0, float s1, float s2) {
        cascadeViewProj[0].set(c0);
        cascadeViewProj[1].set(c1);
        cascadeViewProj[2].set(c2);
        cascadeSplits[0] = s0;
        cascadeSplits[1] = s1;
        cascadeSplits[2] = s2;
        cascadeSplits[3] = s2;

        cascadeViewProj[0].get(80, floatBuffer);
        cascadeViewProj[1].get(96, floatBuffer);
        cascadeViewProj[2].get(112, floatBuffer);
        putVec4(128, s0, s1, s2, s2);

        GL45C.glNamedBufferSubData(uboId, 0, rawBuffer);
        org.lwjgl.opengl.GL42C.glMemoryBarrier(
                org.lwjgl.opengl.GL42C.GL_UNIFORM_BARRIER_BIT | org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT
        );
        bindStandard();
        bindUnified();
    }

    private void putVec4(int floatOffset, float x, float y, float z, float w) {
        floatBuffer.put(floatOffset, x);
        floatBuffer.put(floatOffset + 1, y);
        floatBuffer.put(floatOffset + 2, z);
        floatBuffer.put(floatOffset + 3, w);
    }

    public void uploadAndBind() {
        GL45C.glNamedBufferSubData(uboId, 0, rawBuffer);
        bindStandard();
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

    public Vector3f getShadowLightDirection() {
        return shadowLightDirection;
    }

    public float getShadowFade() {
        return shadowFade;
    }

    public Matrix4f getViewMatrix() {
        return viewMatrix;
    }

    public Matrix4f getProjMatrix() {
        return projMatrix;
    }

    public int getFrameCounter() {
        return frameCounter;
    }

    public void resetTemporalHistory() {
        this.hasPrevFrame = false;
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

    private record HeldLightInfo(float level, float r, float g, float b) {}

    private static HeldLightInfo evaluateHeldItemLight(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new HeldLightInfo(0.0f, 1.0f, 0.72f, 0.38f);
        }
        Item item = stack.getItem();

        if (item == Items.LAVA_BUCKET) {
            return new HeldLightInfo(15.0f, 1.00f, 0.45f, 0.12f);
        }
        if (item == Items.BLAZE_ROD || item == Items.BLAZE_POWDER || item == Items.FIRE_CHARGE) {
            return new HeldLightInfo(12.0f, 1.00f, 0.58f, 0.18f);
        }
        if (item == Items.GLOWSTONE_DUST || item == Items.SPECTRAL_ARROW) {
            return new HeldLightInfo(10.0f, 1.00f, 0.82f, 0.45f);
        }
        if (item == Items.GLOW_INK_SAC || item == Items.PRISMARINE_CRYSTALS || item == Items.HEART_OF_THE_SEA) {
            return new HeldLightInfo(10.0f, 0.35f, 0.90f, 0.95f);
        }
        if (item == Items.AMETHYST_SHARD || item == Items.ECHO_SHARD || item == Items.NETHER_STAR) {
            return new HeldLightInfo(11.0f, 0.78f, 0.52f, 1.00f);
        }

        if (item instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            int emission = block.defaultBlockState().getLightEmission();

            if (block == Blocks.SOUL_TORCH || block == Blocks.SOUL_LANTERN || block == Blocks.SOUL_CAMPFIRE) {
                return new HeldLightInfo(12.0f, 0.30f, 0.88f, 1.00f);
            }
            if (block == Blocks.REDSTONE_TORCH) {
                return new HeldLightInfo(9.0f, 1.00f, 0.22f, 0.15f);
            }
            if (block == Blocks.SEA_LANTERN || block == Blocks.CONDUIT) {
                return new HeldLightInfo(15.0f, 0.58f, 0.92f, 1.00f);
            }
            if (block == Blocks.VERDANT_FROGLIGHT) {
                return new HeldLightInfo(15.0f, 0.55f, 1.00f, 0.62f);
            }
            if (block == Blocks.PEARLESCENT_FROGLIGHT || block == Blocks.CRYING_OBSIDIAN || block == Blocks.END_ROD) {
                return new HeldLightInfo(14.0f, 0.85f, 0.62f, 1.00f);
            }
            if (emission > 0) {
                return new HeldLightInfo((float) emission, 1.00f, 0.72f, 0.38f);
            }

            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            if (key != null) {
                String path = key.getPath().toLowerCase(Locale.ROOT);
                if (path.contains("torch") || path.contains("lantern") || path.contains("lamp") || path.contains("glow")) {
                    return new HeldLightInfo(13.0f, 1.00f, 0.72f, 0.38f);
                }
            }
        }
        return new HeldLightInfo(0.0f, 1.0f, 0.72f, 0.38f);
    }
}
