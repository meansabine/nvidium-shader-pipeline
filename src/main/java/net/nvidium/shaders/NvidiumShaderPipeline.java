package net.nvidium.shaders;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.nvidium.shaders.integration.NvidiumHookImpl;
import net.nvidium.shaders.pipeline.PipelineManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Nvidium Shader Pipeline (NSP).
 * This mod provides a dedicated, GPU-driven shader harness engineered specifically
 * for Nvidium's Task + Mesh Shader and unified memory rendering architecture.
 */
@Mod(NvidiumShaderPipeline.MOD_ID)
public class NvidiumShaderPipeline {
    public static final String MOD_ID = "nvidium_shaders";
    public static final Logger LOGGER = LoggerFactory.getLogger("NvidiumShaderPipeline");

    private static PipelineManager pipelineManager;

    public NvidiumShaderPipeline(IEventBus modEventBus) {
        LOGGER.info("Initializing Nvidium Shader Pipeline (NSP) Harness...");
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            pipelineManager = new PipelineManager();
            pipelineManager.init();

            // Connect to Nvidium engine if present
            try {
                if (ModList.get() != null && ModList.get().isLoaded("nvidium")) {
                    me.cortex.nvidium.api0.NvidiumAPI.registerShaderHook(new NvidiumHookImpl(pipelineManager));
                    LOGGER.info("Successfully registered Nvidium Shader Pipeline hook with Nvidium engine.");
                } else {
                    LOGGER.info("Nvidium mod not loaded; pipeline initialized in standalone mode.");
                }
            } catch (Throwable t) {
                LOGGER.error("Failed to connect to Nvidium API", t);
            }

            LOGGER.info("Nvidium Shader Pipeline successfully initialized.");
        });
    }

    public static PipelineManager getPipelineManager() {
        return pipelineManager;
    }
}
