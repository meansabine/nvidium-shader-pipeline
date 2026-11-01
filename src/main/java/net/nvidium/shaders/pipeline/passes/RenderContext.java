package net.nvidium.shaders.pipeline.passes;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;

public record RenderContext(
        Matrix4f projectionMatrix,
        Matrix4f modelViewMatrix,
        Camera camera,
        ClientLevel level,
        int screenWidth,
        int screenHeight,
        float tickDelta,
        float time,
        int frameIndex
) {}
