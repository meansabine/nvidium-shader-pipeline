package net.nvidium.shaders.gui;

import me.cortex.nvidium.api0.NvidiumAPI;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.nvidium.shaders.config.PipelineConfig;
import net.nvidium.shaders.pack.ShaderPack;
import net.nvidium.shaders.pack.ShaderPackManager;
import net.nvidium.shaders.pipeline.PipelineManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * In-game configuration screen for Nvidium Shader Pipeline:
 * <ul>
 *   <li>Tab 0: Shader Pack Selector (built-in, folder, and .zip packs in {@code shaderpacks/})</li>
 *   <li>Tab 1: Pipeline Settings (Shadows, Volumetric Fog, SSR, GTAO, TAA, Waving, Tonemapper, Exposure, Bloom, Debug)</li>
 *   <li>Tab 2: Pack Options (Dynamic controls generated from the active pack's {@code pack.json})</li>
 * </ul>
 */
public class NvidiumShaderScreen extends Screen {
    private final Screen parent;
    private int currentTab = 0; // 0 = Packs, 1 = Pipeline Settings, 2 = Pack Options
    private int packListOffset = 0;
    private int optionPageOffset = 0;
    private String statusBanner = null;
    private boolean requiresReloadOnClose = false;

    public NvidiumShaderScreen(Screen parent) {
        super(Component.literal("Nvidium Shader Pipeline"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        clearWidgets();

        ShaderPackManager.get().discoverPacks();
        PipelineConfig cfg = PipelineConfig.get();

        int centerX = this.width / 2;

        // Top Navigation Tabs
        addRenderableWidget(Button.builder(
                        Component.literal(currentTab == 0 ? "[ Shader Packs ]" : "Shader Packs"),
                        btn -> { currentTab = 0; rebuildWidgets(); })
                .bounds(centerX - 185, 22, 120, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal(currentTab == 1 ? "[ Pipeline Settings ]" : "Pipeline Settings"),
                        btn -> { currentTab = 1; rebuildWidgets(); })
                .bounds(centerX - 60, 22, 120, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal(currentTab == 2 ? "[ Pack Options ]" : "Pack Options"),
                        btn -> { currentTab = 2; optionPageOffset = 0; rebuildWidgets(); })
                .bounds(centerX + 65, 22, 120, 20)
                .build());

        if (currentTab == 0) {
            buildShaderPacksTab(centerX, cfg);
        } else if (currentTab == 1) {
            buildPipelineSettingsTab(centerX, cfg);
        } else {
            buildPackOptionsTab(centerX, cfg);
        }

        // Bottom Action Bar
        int bottomY = this.height - 28;
        boolean shadersOn = cfg.enabled && NvidiumAPI.areShadersEnabled();

        addRenderableWidget(Button.builder(
                        Component.literal("Shaders: " + (shadersOn ? "ON" : "OFF")),
                        btn -> {
                            boolean next = !shadersOn;
                            cfg.enabled = next;
                            cfg.save();
                            NvidiumAPI.setShadersEnabled(next);
                            requiresReloadOnClose = true;
                            rebuildWidgets();
                        })
                .bounds(centerX - 210, bottomY, 82, 20)
                .tooltip(Tooltip.create(Component.literal("Master toggle for the Nvidium Shader Pipeline")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("Open Folder"),
                        btn -> {
                            Path dir = PipelineConfig.getShaderPacksDir();
                            Util.getPlatform().openPath(dir);
                        })
                .bounds(centerX - 124, bottomY, 80, 20)
                .tooltip(Tooltip.create(Component.literal("Open the shaderpacks/ directory to drop custom folders or .zip shader packs")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("Export Template"),
                        btn -> {
                            try {
                                Path exported = ShaderPackManager.get().exportTemplatePack();
                                statusBanner = "Exported editable template to shaderpacks/" + exported.getFileName();
                                rebuildWidgets();
                            } catch (Exception e) {
                                statusBanner = "Export failed: " + e.getMessage();
                            }
                        })
                .bounds(centerX - 40, bottomY, 96, 20)
                .tooltip(Tooltip.create(Component.literal("Creates shaderpacks/nvidium-custom-template/ with full editable GLSL sources and pack.json")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("Reload Shaders"),
                        btn -> {
                            cfg.save();
                            PipelineManager.getInstance().reload();
                            requiresReloadOnClose = false;
                            statusBanner = "Reloaded active pack: " + ShaderPackManager.get().getActivePack().getName();
                            rebuildWidgets();
                        })
                .bounds(centerX + 60, bottomY, 86, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("Done"),
                        btn -> onClose())
                .bounds(centerX + 150, bottomY, 60, 20)
                .build());
    }

    private void buildShaderPacksTab(int centerX, PipelineConfig cfg) {
        List<ShaderPack> packs = ShaderPackManager.get().getAvailablePacks();
        int maxVisible = Math.max(3, (this.height - 130) / 24);
        if (packListOffset > Math.max(0, packs.size() - maxVisible)) {
            packListOffset = Math.max(0, packs.size() - maxVisible);
        }

        int listX = centerX - 190;
        int startY = 54;
        for (int i = 0; i < maxVisible && (i + packListOffset) < packs.size(); i++) {
            ShaderPack pack = packs.get(i + packListOffset);
            boolean active = pack.getId().equals(cfg.activePack)
                    || (pack.isBuiltin() && !packs.stream().anyMatch(p -> p.getId().equals(cfg.activePack)));

            String badge = switch (pack.getSourceType()) {
                case BUILTIN -> "[Built-in] ";
                case DIRECTORY -> "[Folder] ";
                case ZIP -> "[ZIP] ";
            };
            String label = (active ? ">> " : "   ") + badge + pack.getName();

            addRenderableWidget(Button.builder(
                            Component.literal(label),
                            btn -> {
                                cfg.activePack = pack.getId();
                                cfg.save();
                                PipelineManager.getInstance().reload();
                                statusBanner = "Activated pack: " + pack.getName();
                                rebuildWidgets();
                            })
                    .bounds(listX, startY + i * 24, 210, 20)
                    .tooltip(Tooltip.create(Component.literal(pack.getDescription())))
                    .build());
        }

        if (packs.size() > maxVisible) {
            addRenderableWidget(Button.builder(Component.literal("Up"), btn -> {
                packListOffset = Math.max(0, packListOffset - 1);
                rebuildWidgets();
            }).bounds(listX, startY + maxVisible * 24 + 2, 102, 18).build());

            addRenderableWidget(Button.builder(Component.literal("Down"), btn -> {
                packListOffset = Math.min(Math.max(0, packs.size() - maxVisible), packListOffset + 1);
                rebuildWidgets();
            }).bounds(listX + 108, startY + maxVisible * 24 + 2, 102, 18).build());
        }
    }

    private void buildPipelineSettingsTab(int centerX, PipelineConfig cfg) {
        int colLeft = centerX - 185;
        int colRight = centerX + 5;
        int btnW = 180;
        int rowH = 22;
        int y0 = 50;

        // Row 0: Shadow Resolution & Shadow Distance
        addRenderableWidget(Button.builder(
                        Component.literal("Shadow Res: " + cfg.shadowResolution + "x" + cfg.shadowResolution),
                        btn -> {
                            cfg.shadowResolution = switch (cfg.shadowResolution) {
                                case 1024 -> 2048;
                                case 2048 -> 3072;
                                case 3072 -> 4096;
                                default -> 1024;
                            };
                            cfg.save();
                            rebuildWidgets();
                        })
                .bounds(colLeft, y0, btnW, 20)
                .tooltip(Tooltip.create(Component.literal("Resolution per shadow cascade (3 cascades total)")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("Shadow Dist: " + Math.round(cfg.shadowDistance) + "m"),
                        btn -> {
                            if (cfg.shadowDistance < 96f) cfg.shadowDistance = 96f;
                            else if (cfg.shadowDistance < 128f) cfg.shadowDistance = 128f;
                            else if (cfg.shadowDistance < 160f) cfg.shadowDistance = 160f;
                            else if (cfg.shadowDistance < 224f) cfg.shadowDistance = 224f;
                            else if (cfg.shadowDistance < 320f) cfg.shadowDistance = 320f;
                            else cfg.shadowDistance = 64f;
                            cfg.save();
                            rebuildWidgets();
                        })
                .bounds(colRight, y0, btnW, 20)
                .tooltip(Tooltip.create(Component.literal("Maximum distance covered by cascaded sun/moon shadows")))
                .build());

        // Row 1: Volumetric Fog & SSR
        addRenderableWidget(Button.builder(
                        Component.literal("Volumetric Shafts: " + (cfg.volumetricFog ? "ON" : "OFF")),
                        btn -> { cfg.volumetricFog = !cfg.volumetricFog; cfg.save(); rebuildWidgets(); })
                .bounds(colLeft, y0 + rowH, btnW, 20)
                .tooltip(Tooltip.create(Component.literal("Raymarched crepuscular sun/moon light shafts and height fog")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("Screen-Space Refl: " + (cfg.ssrEnabled ? "ON" : "OFF")),
                        btn -> { cfg.ssrEnabled = !cfg.ssrEnabled; cfg.save(); rebuildWidgets(); })
                .bounds(colRight, y0 + rowH, btnW, 20)
                .tooltip(Tooltip.create(Component.literal("Raymarched screen-space reflections on water, ice, glass, metals, and puddles")))
                .build());

        // Row 2: GTAO & TAA
        addRenderableWidget(Button.builder(
                        Component.literal("Horizon GTAO: " + (cfg.gtaoEnabled ? "ON" : "OFF")),
                        btn -> { cfg.gtaoEnabled = !cfg.gtaoEnabled; cfg.save(); rebuildWidgets(); })
                .bounds(colLeft, y0 + rowH * 2, btnW, 20)
                .tooltip(Tooltip.create(Component.literal("Multi-direction screen-space ambient occlusion")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal("Temporal AA (TAA): " + (cfg.taaEnabled ? "ON" : "OFF")),
                        btn -> { cfg.taaEnabled = !cfg.taaEnabled; cfg.save(); rebuildWidgets(); })
                .bounds(colRight, y0 + rowH * 2, btnW, 20)
                .tooltip(Tooltip.create(Component.literal("Temporal accumulation & variance-clamped anti-aliasing")))
                .build());

        // Row 3: Waving Terrain & Tonemapper
        addRenderableWidget(Button.builder(
                        Component.literal("Waving Foliage/Water: " + (cfg.wavingTerrain ? "ON" : "OFF")),
                        btn -> {
                            cfg.wavingTerrain = !cfg.wavingTerrain;
                            cfg.save();
                            requiresReloadOnClose = true;
                            rebuildWidgets();
                        })
                .bounds(colLeft, y0 + rowH * 3, btnW, 20)
                .tooltip(Tooltip.create(Component.literal("GPU mesh-shader vertex wind animation for foliage, leaves, and water")))
                .build());

        String tonemapName = switch (cfg.tonemapOperator) {
            case 0 -> "AgX Filmic";
            case 1 -> "ACES Filmic";
            default -> "Khronos Neutral";
        };
        addRenderableWidget(Button.builder(
                        Component.literal("Tonemapper: " + tonemapName),
                        btn -> {
                            cfg.tonemapOperator = (cfg.tonemapOperator + 1) % 3;
                            cfg.save();
                            rebuildWidgets();
                        })
                .bounds(colRight, y0 + rowH * 3, btnW, 20)
                .build());

        // Row 4: Exposure & Bloom Intensity
        addRenderableWidget(Button.builder(
                        Component.literal(String.format(Locale.ROOT, "Exposure: %.2fx", cfg.exposure)),
                        btn -> {
                            cfg.exposure += 0.15f;
                            if (cfg.exposure > 2.05f) cfg.exposure = 0.60f;
                            cfg.save();
                            rebuildWidgets();
                        })
                .bounds(colLeft, y0 + rowH * 4, btnW, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal(String.format(Locale.ROOT, "Bloom Strength: %.2f", cfg.bloomIntensity)),
                        btn -> {
                            cfg.bloomIntensity += 0.10f;
                            if (cfg.bloomIntensity > 0.85f) cfg.bloomIntensity = 0.0f;
                            cfg.save();
                            rebuildWidgets();
                        })
                .bounds(colRight, y0 + rowH * 4, btnW, 20)
                .build());

        // Row 5: Saturation & CAS Sharpening
        addRenderableWidget(Button.builder(
                        Component.literal(String.format(Locale.ROOT, "Saturation: %.2fx", cfg.saturation)),
                        btn -> {
                            cfg.saturation += 0.10f;
                            if (cfg.saturation > 1.55f) cfg.saturation = 0.70f;
                            cfg.save();
                            rebuildWidgets();
                        })
                .bounds(colLeft, y0 + rowH * 5, btnW, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal(String.format(Locale.ROOT, "CAS Sharpening: %.2f", cfg.casSharpening)),
                        btn -> {
                            cfg.casSharpening += 0.15f;
                            if (cfg.casSharpening > 0.95f) cfg.casSharpening = 0.0f;
                            cfg.save();
                            rebuildWidgets();
                        })
                .bounds(colRight, y0 + rowH * 5, btnW, 20)
                .build());

        // Row 6: Debug Mode
        String debugLabel = switch (cfg.debugMode) {
            case 1 -> "Albedo + Roughness";
            case 2 -> "World Normals";
            case 3 -> "Light + Emissive";
            case 4 -> "Shadow Cascades";
            case 5 -> "Material IDs";
            case 6 -> "GTAO Ambient Occlusion";
            default -> "Off (Final Render)";
        };
        addRenderableWidget(Button.builder(
                        Component.literal("Debug View: " + debugLabel),
                        btn -> {
                            cfg.debugMode = (cfg.debugMode + 1) % 7;
                            cfg.save();
                            rebuildWidgets();
                        })
                .bounds(centerX - 120, y0 + rowH * 6, 240, 20)
                .tooltip(Tooltip.create(Component.literal("Inspect internal G-Buffer attachments, material IDs, shadow cascades, or GTAO")))
                .build());
    }

    private void buildPackOptionsTab(int centerX, PipelineConfig cfg) {
        ShaderPack pack = ShaderPackManager.get().getActivePack();
        List<ShaderPack.PackOption> options = pack.getOptions();

        int colLeft = centerX - 185;
        int colRight = centerX + 5;
        int btnW = 180;
        int rowH = 22;
        int y0 = 50;

        int itemsPerPage = 10; // 5 rows x 2 columns
        int startIdx = optionPageOffset * itemsPerPage;

        for (int i = 0; i < itemsPerPage && (startIdx + i) < options.size(); i++) {
            ShaderPack.PackOption opt = options.get(startIdx + i);
            int col = (i % 2 == 0) ? colLeft : colRight;
            int row = i / 2;
            float curVal = cfg.getPackOptionValue(pack.getId(), opt.id, opt.defaultValue);

            String valueText = formatOptionValue(opt, curVal);
            addRenderableWidget(Button.builder(
                            Component.literal(opt.displayName() + ": " + valueText),
                            btn -> {
                                float nextVal = stepOptionValue(opt, curVal);
                                cfg.setPackOptionValue(pack.getId(), opt.id, nextVal);
                                cfg.save();
                                if (opt.defineMacro) {
                                    requiresReloadOnClose = true;
                                }
                                rebuildWidgets();
                            })
                    .bounds(col, y0 + row * rowH, btnW, 20)
                    .tooltip(Tooltip.create(Component.literal(
                            "[" + opt.category + "] " + (opt.description != null ? opt.description : opt.id))))
                    .build());
        }

        int navY = y0 + 5 * rowH + 4;
        if (options.size() > itemsPerPage) {
            int totalPages = (options.size() + itemsPerPage - 1) / itemsPerPage;
            addRenderableWidget(Button.builder(Component.literal("< Prev Page"), btn -> {
                optionPageOffset = Math.max(0, optionPageOffset - 1);
                rebuildWidgets();
            }).bounds(colLeft, navY, 90, 20).build());

            addRenderableWidget(Button.builder(
                    Component.literal("Page " + (optionPageOffset + 1) + "/" + totalPages + " >"),
                    btn -> {
                        if ((optionPageOffset + 1) * itemsPerPage < options.size()) {
                            optionPageOffset++;
                        } else {
                            optionPageOffset = 0;
                        }
                        rebuildWidgets();
                    }).bounds(colLeft + 95, navY, 90, 20).build());
        }

        addRenderableWidget(Button.builder(
                        Component.literal("Reset Pack Defaults"),
                        btn -> {
                            cfg.resetPackOptions(pack.getId());
                            cfg.save();
                            statusBanner = "Reset options for " + pack.getName() + " to defaults";
                            rebuildWidgets();
                        })
                .bounds(colRight, navY, 180, 20)
                .build());
    }

    private static String formatOptionValue(ShaderPack.PackOption opt, float val) {
        if ("bool".equalsIgnoreCase(opt.type)) {
            return val >= 0.5f ? "ON" : "OFF";
        }
        if ("enum".equalsIgnoreCase(opt.type) && opt.choices != null && !opt.choices.isEmpty()) {
            int idx = Math.max(0, Math.min(opt.choices.size() - 1, Math.round(val)));
            return opt.choices.get(idx);
        }
        if ("int".equalsIgnoreCase(opt.type)) {
            return Integer.toString(Math.round(val));
        }
        return String.format(Locale.ROOT, "%.2f", val);
    }

    private static float stepOptionValue(ShaderPack.PackOption opt, float cur) {
        if ("bool".equalsIgnoreCase(opt.type)) {
            return cur >= 0.5f ? 0.0f : 1.0f;
        }
        if ("enum".equalsIgnoreCase(opt.type) && opt.choices != null && !opt.choices.isEmpty()) {
            int next = (Math.round(cur) + 1) % opt.choices.size();
            return (float) next;
        }
        float step = opt.step > 0.0001f ? opt.step : 0.1f;
        float next = cur + step * 2.0f;
        if (next > opt.max + 0.001f) {
            next = opt.min;
        }
        return Math.round(next / step) * step;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        graphics.drawCenteredString(this.font, "Nvidium Frontier Shader Pipeline", centerX, 7, 0xFFFFFF);

        ShaderPack activePack = ShaderPackManager.get().getActivePack();
        if (currentTab == 0) {
            int infoX = centerX + 30;
            int infoY = 56;
            graphics.drawString(this.font, "Active Pack:", infoX, infoY, 0xA0E0FF);
            graphics.drawString(this.font, activePack.getName(), infoX, infoY + 12, 0xFFFFFF);
            graphics.drawString(this.font, "Author: " + activePack.getAuthor() + " (v" + activePack.getVersion() + ")", infoX, infoY + 26, 0xBBBBBB);
            graphics.drawWordWrap(this.font, Component.literal(activePack.getDescription()), infoX, infoY + 42, Math.max(120, this.width - infoX - 16), 0xCCCCCC);
        }

        String err = ShaderPackManager.get().getLastError();
        if (err != null && !err.isBlank()) {
            graphics.drawCenteredString(this.font, "Shader Warning: " + err, centerX, this.height - 42, 0xFF6666);
        } else if (statusBanner != null) {
            graphics.drawCenteredString(this.font, statusBanner, centerX, this.height - 42, 0x66FF88);
        } else {
            graphics.drawCenteredString(
                    this.font,
                    "Active Pack: " + activePack.getName() + "  |  Drop custom folders or .zip packs into shaderpacks/",
                    centerX,
                    this.height - 42,
                    0xAAAAAA
            );
        }
    }

    @Override
    public void onClose() {
        PipelineConfig.get().save();
        if (requiresReloadOnClose) {
            PipelineManager.getInstance().reload();
            requiresReloadOnClose = false;
        }
        Minecraft.getInstance().setScreen(parent);
    }
}
