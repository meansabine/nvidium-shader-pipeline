package net.nvidium.shaders;

import com.mojang.blaze3d.platform.InputConstants;
import me.cortex.nvidium.api0.NvidiumAPI;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.nvidium.shaders.config.PipelineConfig;
import net.nvidium.shaders.gui.NvidiumShaderScreen;
import net.nvidium.shaders.integration.NvidiumHookImpl;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pipeline.PipelineManager;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = NvidiumShaderPipeline.MOD_ID, dist = Dist.CLIENT)
public class NvidiumShaderPipeline {
    public static final String MOD_ID = "nvidium_shaders";
    public static final Logger LOGGER = LoggerFactory.getLogger("NvidiumShaderPipeline");

    private static KeyMapping openGuiKey;
    private static KeyMapping reloadShadersKey;

    public NvidiumShaderPipeline(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Loading Nvidium Frontier Shader Pipeline...");

        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::onRegisterKeyMappings);

        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (mc, parent) -> new NvidiumShaderScreen(parent)
        );

        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        PipelineConfig.get();
        ShaderPackManager.get().discoverPacks();
        NvidiumAPI.registerShaderHook(new NvidiumHookImpl());
        LOGGER.info("Registered Nvidium Shader Hook successfully (Active pack: {}).",
                ShaderPackManager.get().getActivePack().getName());
    }

    private void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        openGuiKey = new KeyMapping(
                "key.nvidium_shaders.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "key.categories.misc"
        );
        reloadShadersKey = new KeyMapping(
                "key.nvidium_shaders.reload",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                "key.categories.misc"
        );
        event.register(openGuiKey);
        event.register(reloadShadersKey);
    }

    private void onClientTick(final ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (openGuiKey != null) {
            while (openGuiKey.consumeClick()) {
                mc.setScreen(new NvidiumShaderScreen(mc.screen));
            }
        }
        if (reloadShadersKey != null) {
            while (reloadShadersKey.consumeClick()) {
                PipelineManager.getInstance().reload();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("[Nvidium Shaders] Reloaded shader pack: "
                                    + ShaderPackManager.get().getActivePack().getName()),
                            true
                    );
                }
            }
        }
    }

    private void onRegisterClientCommands(final RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("nvidium_shaders")
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    Minecraft.getInstance().execute(() -> {
                                        PipelineManager.getInstance().reload();
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("[Nvidium Shaders] Reloaded pack: "
                                                        + ShaderPackManager.get().getActivePack().getName()),
                                                false
                                        );
                                    });
                                    return 1;
                                }))
                        .then(Commands.literal("ui")
                                .executes(ctx -> {
                                    Minecraft mc = Minecraft.getInstance();
                                    mc.execute(() -> mc.setScreen(new NvidiumShaderScreen(mc.screen)));
                                    return 1;
                                }))
        );
    }
}
