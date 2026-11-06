package net.nvidium.shaders.pipeline.passes;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;

/**
 * Frame rendering context passed across pipeline stages.
 */
public class RenderContext {
    private Viewport viewport;
    private ChunkRenderMatrices chunkRenderMatrices;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private int screenWidth = 1920;
    private int screenHeight = 1080;

    private int terrainRegionCount;
    private long terrainCommandAddress;
    private int translucentRegionCount;
    private long translucentCommandAddress;

    public void updateFrame(Viewport viewport, ChunkRenderMatrices crm, double px, double py, double pz, int width, int height) {
        this.viewport = viewport;
        this.chunkRenderMatrices = crm;
        this.cameraX = px;
        this.cameraY = py;
        this.cameraZ = pz;
        this.screenWidth = Math.max(1, width);
        this.screenHeight = Math.max(1, height);
    }

    public void updateTerrainBuffer(int regionCount, long commandAddr) {
        this.terrainRegionCount = regionCount;
        this.terrainCommandAddress = commandAddr;
    }

    public void updateTranslucentBuffer(int regionCount, long commandAddr) {
        this.translucentRegionCount = regionCount;
        this.translucentCommandAddress = commandAddr;
    }

    public Viewport getViewport() { return viewport; }
    public ChunkRenderMatrices getChunkRenderMatrices() { return chunkRenderMatrices; }
    public double getCameraX() { return cameraX; }
    public double getCameraY() { return cameraY; }
    public double getCameraZ() { return cameraZ; }
    public int getScreenWidth() { return screenWidth; }
    public int getScreenHeight() { return screenHeight; }
    public int getTerrainRegionCount() { return terrainRegionCount; }
    public long getTerrainCommandAddress() { return terrainCommandAddress; }
    public int getTranslucentRegionCount() { return translucentRegionCount; }
    public long getTranslucentCommandAddress() { return translucentCommandAddress; }
}
