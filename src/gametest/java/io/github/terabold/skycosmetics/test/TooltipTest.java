package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.tooltip.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;

/** Real render and wheel paths, including a foreign image component and a resource pack's tooltip textures. */
public class TooltipTest implements FabricClientGameTest {
    private static final Identifier STYLE = Identifier.fromNamespaceAndPath("skycosmetics", "fixture");
    private static final int MAGENTA = 0xFFFF00FF;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        JsonObject before = ctx.computeOnClient(mc -> TooltipSettings.write());
        int[] dimensions = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
        List<String> packs = ctx.computeOnClient(mc -> List.copyOf(mc.getResourcePackRepository().getSelectedIds()));
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            settings(ctx);
            inventoryPreview(ctx);
            rendering(ctx);
            cache(ctx);
            packedBackground(ctx);
        } catch (IOException e) {
            throw new AssertionError("Tooltip test files", e);
        } finally {
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> {
                TooltipSettings.read(before);
                TooltipRenderer.reset();
                Settings.save();
                mc.getResourcePackRepository().setSelected(packs);
            });
            reload(ctx);
            window(ctx, dimensions[0], dimensions[1], dimensions[2]);
        }
        System.out.println("[SkyCosmeticsTest] tooltip customization checks passed");
    }

    private static void inventoryPreview(ClientGameTestContext ctx) {
        boolean autoFit = ctx.computeOnClient(mc -> TooltipSettings.autoFit);
        var original = ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0));
        var menu = ctx.computeOnClient(mc -> mc.player.containerMenu);
        Screen parent = ctx.computeOnClient(mc -> mc.screen);
        try {
            ctx.runOnClient(mc -> {
                var fixture = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
                fixture.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("Copied inventory sword"));
                List<Component> lore = new ArrayList<>();
                for (int i = 0; i < 40; i++) lore.add(Component.literal("Preview line " + i));
                lore.add(Component.literal("LEGENDARY SWORD"));
                fixture.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(lore));
                mc.player.getInventory().setItem(0, fixture);
            });
            var live = ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0));
            ctx.setScreen(() -> new TooltipPreviewScreen(parent));
            ctx.getInput().setCursorPos(0, 0); ctx.waitTicks(2);
            ctx.takeScreenshot("skycosmetics-tooltip-inventory");
            int[] at = ctx.computeOnClient(mc -> new int[]{(mc.screen.width / 2 - 108 + 10) * 2,
                (Math.max(60, mc.screen.height / 2 - 60) + 42 + 72 + 4 + 10) * 2});
            ctx.getInput().setCursorPos(at[0], at[1]); ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> ((TooltipPreviewScreen)mc.screen).tooltipItem() != live
                && ((TooltipPreviewScreen)mc.screen).tooltipItem().getHoverName().getString().equals("Copied inventory sword")
                && mc.player.containerMenu == menu && mc.player.getInventory().getItem(0) == live), "preview hovers a copy without opening a container or modifying the live item");
            check(TooltipRenderer.borderColor() == (0xFF000000 | io.github.terabold.skycosmetics.slots.RarityBackgrounds.color(io.github.terabold.skycosmetics.slots.Rarity.LEGENDARY)), "copied inventory uses the item's rarity border");
            ctx.getInput().scroll(-1); ctx.waitTicks(2);
            check(TooltipRenderer.scrollTarget() > 0, "real copied item tooltip scrolls through the normal pipeline");
            ctx.runOnClient(mc -> { TooltipSettings.autoFit = true; TooltipRenderer.reset(); }); ctx.waitTicks(3);
            ctx.getInput().scroll(-1); ctx.waitTicks(2);
            check(TooltipRenderer.scrollTarget() > 0, "auto-fit item tooltips still move with the mouse wheel");
            ctx.takeScreenshot("skycosmetics-tooltip-inventory-preview");
            ctx.runOnClient(mc -> mc.player.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY));
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> !((TooltipPreviewScreen)mc.screen).tooltipItem().isEmpty()), "live inventory updates cannot mutate the captured preview");
            ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE); ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> mc.screen == parent), "Escape returns to the exact settings screen");
            ctx.setScreen(() -> new TooltipPreviewScreen(parent)); ctx.clickScreenButton("Back");
            check(ctx.computeOnClient(mc -> mc.screen == parent), "Back returns to settings");
            ctx.setScreen(() -> new TooltipPreviewScreen(parent)); ctx.clickScreenButton("Done");
            check(ctx.computeOnClient(mc -> mc.screen == parent), "Done returns to settings");
        } finally { ctx.runOnClient(mc -> {
            mc.player.getInventory().setItem(0, original);
            TooltipSettings.autoFit = autoFit;
            TooltipRenderer.reset();
        }); }
    }

    private static void settings(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            TooltipSettings.reset();
            JsonObject bad = new JsonObject();
            bad.addProperty("size", 999);
            bad.addProperty("padding", -50);
            bad.addProperty("scroll", "no-such-mode");
            bad.addProperty("color", "bad");
            TooltipSettings.read(bad);
            check(TooltipSettings.size == 200 && TooltipSettings.padding == 4, "numeric settings are bounded");
            check(TooltipSettings.scroll == TooltipSettings.Scroll.SMOOTH && TooltipSettings.color == 0xF0181820,
                "invalid choices and colors keep valid defaults");
            TooltipSettings.reset();
        });
        window(ctx, 1280, 720, 2);
        ctx.setScreen(() -> new SettingsScreen(null, TooltipSection.ID));
        ctx.waitForScreen(SettingsScreen.class);
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTicks(3);
        check(ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).sectionId().equals(TooltipSection.ID)), "the tooltip section opened");
        int[] toggle = ctx.computeOnClient(mc -> {
            var widget = ((SettingsScreen) mc.screen).widget("enabled");
            check(widget != null && widget.visible && widget.active, "the tooltip switch is available");
            int scale = mc.getWindow().getGuiScale();
            return new int[]{(widget.getX() + widget.getWidth() / 2) * scale, (widget.getY() + widget.getHeight() / 2) * scale};
        });
        ctx.getInput().setCursorPos(toggle[0], toggle[1]);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
        check(ctx.computeOnClient(mc -> TooltipSettings.enabled), "the actual menu switch enables tooltips");
        ctx.runOnClient(mc -> {
            check(((SettingsScreen) mc.screen).widget("size") != null, "size is in the existing settings menu");
            var screen = (SettingsScreen) mc.screen;
            check(!TooltipSettings.shiftToScroll && TooltipSettings.rarityBorder, "whole-tooltip scrolling needs no Shift and rarity borders are the default");
            check(screen.widget("image") == null && screen.widget("pattern") == null, "unrelated background controls are hidden");
            JsonObject old = TooltipSettings.write(); old.addProperty("background", "PATTERN");
            TooltipSettings.read(old);
            check(TooltipSettings.effectiveBackground() == TooltipSettings.Background.SOLID, "an old pattern or image background reads as solid");
            TooltipSettings.background = TooltipSettings.Background.SOLID; screen.changed();
            JsonObject snapshot = TooltipSettings.write();
            TooltipSettings.reset();
            TooltipSettings.read(snapshot);
            check(TooltipSettings.enabled, "settings round-trip");
            check(!TooltipSettings.customBackground, "new settings preserve resource-pack backgrounds while enabling scrolling");
        });
        ctx.takeScreenshot("skycosmetics-100-tooltip-settings");
    }

    private static void rendering(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            boolean custom = TooltipSettings.customBackground;
            try {
                for (boolean styled : new boolean[]{false, true}) {
                    TooltipSettings.customBackground = styled; TooltipSettings.customBorder = styled;
                    var vanilla = DefaultTooltipPositioner.INSTANCE.positionTooltip(640, 360, 200, 160, 140, 50);
                    var box = TooltipRenderer.layout(640, 360, 140, 50, 200, 160, DefaultTooltipPositioner.INSTANCE);
                    check(box.x() + box.padding() * box.scale() == vanilla.x()
                        && box.y() + box.padding() * box.scale() == vanilla.y(), "padding adds no extra mouse offset");
                }
            } finally { TooltipSettings.customBackground = custom; }
        });
        AtomicReference<Sample> sample = new AtomicReference<>();
        ctx.setScreen(() -> {
            Sample view = new Sample(true, false);
            sample.set(view);
            return view;
        });
        ctx.getInput().setCursorPos(240, 160);
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            Sample s = sample.get();
            check(s.foreign.text > 0 && s.foreign.images > 0, "custom components retain both original render passes");
            check(s.parts.contains(s.foreign), "the original component list still contains the exact foreign instance");
            check(TooltipRenderer.lastLayout().scrollable() && TooltipRenderer.lastLayout().height() == TooltipRenderer.lastLayout().fullHeight(), "long tooltip retains its entire content height");
        });
        int builds = TooltipBackdrop.builds();
        ctx.waitTicks(3);
        check(TooltipBackdrop.builds() == builds, "an unchanged tooltip reuses the background texture");
        ctx.getInput().scroll(-1);
        ctx.waitTicks(2);
        check(sample.get().passedWheels == 0 && TooltipRenderer.scrollTarget() > 0, "whole-tooltip scrolling works without Shift by default");
        ctx.runOnClient(mc -> { TooltipSettings.shiftToScroll = true; TooltipRenderer.reset(); });
        ctx.waitTicks(2); int passedBefore = sample.get().passedWheels;
        ctx.getInput().scroll(-1); ctx.waitTicks(2);
        check(sample.get().passedWheels > passedBefore && TooltipRenderer.scrollTarget() == 0, "optional Shift requirement passes unmodified scrolling through");
        ctx.runOnClient(mc -> {
            TooltipSettings.shiftToScroll = false;
            TooltipSettings.scroll = TooltipSettings.Scroll.NORMAL;
        });
        int pass = sample.get().passedWheels;
        ctx.getInput().scroll(-2);
        ctx.waitTicks(2);
        check(TooltipRenderer.scrollOffset() > 0 && TooltipRenderer.scrollOffset() == TooltipRenderer.scrollTarget(), "normal scroll moves instantly");
        check(sample.get().passedWheels == pass, "the overflowing tooltip consumes its wheel event once");
        ctx.takeScreenshot("skycosmetics-100a-tooltip-normal-scroll");
        ctx.runOnClient(mc -> {
            TooltipSettings.scroll = TooltipSettings.Scroll.SMOOTH;
            double old = TooltipRenderer.scrollOffset();
            check(TooltipRenderer.wheel(mc.screen, 120, 80, -2, false), "smooth scroll accepts the wheel");
            check(TooltipRenderer.scrollOffset() == old && TooltipRenderer.scrollTarget() > old, "smooth scroll sets a target without jumping");
        });
        double old = TooltipRenderer.scrollOffset();
        ctx.waitTicks(3);
        check(TooltipRenderer.scrollOffset() > old && TooltipRenderer.scrollOffset() <= TooltipRenderer.scrollTarget(), "smooth scroll advances toward its target");
        ctx.takeScreenshot("skycosmetics-100b-tooltip-smooth-scroll");
        for (TooltipSettings.Background bg : new TooltipSettings.Background[]{TooltipSettings.Background.SOLID, TooltipSettings.Background.GRADIENT}) {
            ctx.runOnClient(mc -> { TooltipSettings.customBackground = true; TooltipSettings.customBorder = true; TooltipSettings.background = bg; });
            ctx.waitTicks(2);
            ctx.takeScreenshot("skycosmetics-100c-tooltip-" + bg.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (int scale : new int[]{2, 3, 4}) {
            window(ctx, 1920, 1080, scale);
            ctx.runOnClient(mc -> { TooltipSettings.size = 150; TooltipSettings.autoFit = true; });
            ctx.getInput().setCursorPos(360, 240);
            ctx.waitTicks(2);
            ctx.runOnClient(mc -> {
                var box = TooltipRenderer.lastLayout();
                check(box != null && box.x() >= 5 && box.y() >= 5, "scaled viewport is on screen");
                check(box.y() + (box.height() + 2 * box.padding()) * box.scale() <= mc.screen.height - 4,
                    "scaled viewport fits vertically at GUI " + scale);
            });
            ctx.takeScreenshot("skycosmetics-100d-tooltip-size150-gui" + scale);
        }
        window(ctx, 854, 480, 2);
        ctx.getInput().setCursorPos(240, 160);
        ctx.waitTicks(2);
        ctx.takeScreenshot("skycosmetics-100e-tooltip-small-window");
        ctx.setScreen(() -> new Sample(false, false));
        ctx.waitTicks(2);
        check(!TooltipRenderer.lastLayout().scrollable(), "short tooltip does not take scrolling");
        ctx.runOnClient(mc -> check(!TooltipRenderer.wheel(mc.screen, 120, 80, -1, false), "short tooltip passes the wheel through"));
        ctx.runOnClient(mc -> {
            TooltipSettings.enabled = false;
            TooltipRenderer.reset();
        });
        ctx.waitTicks(2);
        check(TooltipRenderer.lastLayout() == null, "disabled customization leaves the original renderer alone");
        ctx.runOnClient(mc -> { TooltipSettings.enabled = true; TooltipSettings.size = 100; });
    }

    private static void cache(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            for (int i = 0; i < 16; i++) {
                TooltipSettings.color = 0xFF101020 + i;
                TooltipBackdrop.draw(new GuiGraphicsExtractor(mc, new net.minecraft.client.renderer.state.gui.GuiRenderState(), 0, 0), 20, 20, 100 + i, 40);
            }
            check(TooltipBackdrop.cached() <= 8, "background cache remains bounded");
        });
    }

    private static void packedBackground(ClientGameTestContext ctx) throws IOException {
        Path pack = ctx.computeOnClient(mc -> mc.getResourcePackDirectory().resolve("skycosmetics-tooltip-test"));
        Path sprites = pack.resolve("assets/skycosmetics/textures/gui/sprites/tooltip");
        Files.createDirectories(sprites);
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"Tooltip priority fixture\",\"min_format\":" + format + ",\"max_format\":" + format + "}}");
        try (NativeImage img = new NativeImage(16, 16, false)) {
            img.fillRect(0, 0, 16, 16, MAGENTA);
            img.writeToFile(sprites.resolve("fixture_background.png"));
            img.fillRect(3, 3, 10, 10, 0);
            img.writeToFile(sprites.resolve("fixture_frame.png"));
        }
        String metadata = "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":16,\"height\":16,\"border\":3}}}";
        Files.writeString(sprites.resolve("fixture_background.png.mcmeta"), metadata);
        Files.writeString(sprites.resolve("fixture_frame.png.mcmeta"), metadata);
        ctx.runOnClient(mc -> {
            var repo = mc.getResourcePackRepository();
            repo.reload();
            List<String> selected = new ArrayList<>(repo.getSelectedIds());
            selected.add("file/" + pack.getFileName());
            repo.setSelected(selected);
            TooltipSettings.background = TooltipSettings.Background.SOLID;
            TooltipSettings.color = 0xFF102030;
            TooltipSettings.radius = 0;
            TooltipSettings.customBackground = false; TooltipSettings.customBorder = false;
        });
        reload(ctx);
        window(ctx, 1280, 720, 2);
        ctx.setScreen(PackedSample::new);
        ctx.getInput().setCursorPos(240, 160);
        ctx.waitTicks(3);
        Path packed = ctx.takeScreenshot("skycosmetics-100g-tooltip-pack-background");
        check(backgroundPixel(ctx, packed) == MAGENTA, "the resource pack's custom tooltip background is active");
        ctx.getInput().scroll(-1); ctx.waitTicks(2);
        check(TooltipRenderer.scrollTarget() > 0, "the complete resource-pack tooltip remains scrollable without overriding its artwork");
        ctx.runOnClient(mc -> TooltipRenderer.reset()); ctx.waitTicks(2);
        ctx.runOnClient(mc -> { TooltipSettings.customBackground = true; TooltipSettings.customBorder = true; });
        ctx.waitTicks(3);
        Path overridden = ctx.takeScreenshot("skycosmetics-100h-tooltip-over-pack");
        check(backgroundPixel(ctx, overridden) == 0xFF102030, "custom background takes priority over the loaded pack");
        ctx.runOnClient(mc -> { TooltipSettings.customBorder = false; TooltipRenderer.reset(); }); ctx.waitTicks(3);
        Path frameOnly = ctx.takeScreenshot("skycosmetics-tooltip-custom-fill-pack-frame");
        check(packPixel(ctx, frameOnly, 1) == MAGENTA, "the resource pack frame is retained independently");
        check(packPixel(ctx, frameOnly, 5) == 0xFF102030, "custom fill reaches the inside of the resource pack frame without a gap");
        ctx.runOnClient(mc -> {
            TooltipSettings.customBackground = false; TooltipSettings.customBorder = true;
            TooltipSettings.rarityBorder = false; TooltipSettings.border = 0xFF20A040; TooltipRenderer.reset();
        }); ctx.waitTicks(3);
        Path fillOnly = ctx.takeScreenshot("skycosmetics-tooltip-pack-fill-custom-frame");
        check(packPixel(ctx, fillOnly, 0) == 0xFF20A040, "custom border is drawn above the retained pack background");
        check(packPixel(ctx, fillOnly, 5) == MAGENTA, "the resource pack background is retained independently");
        nativePreviewStyle(ctx);
    }

    private static void nativePreviewStyle(ClientGameTestContext ctx) throws IOException {
        var original = ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0));
        var rarity = io.github.terabold.skycosmetics.slots.RarityBackgrounds.style();
        boolean on = io.github.terabold.skycosmetics.slots.RarityBackgrounds.on();
        try {
            ctx.runOnClient(mc -> {
                var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
                stack.set(net.minecraft.core.component.DataComponents.TOOLTIP_STYLE, STYLE);
                stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(List.of(Component.literal("LEGENDARY SWORD"))));
                mc.player.getInventory().setItem(0,stack);
                io.github.terabold.skycosmetics.slots.RarityBackgrounds.set(on,rarity.shape(),rarity.fill(),rarity.opacity(),false);
                TooltipSettings.customBackground = TooltipSettings.customBorder = false;
                TooltipSettings.autoFit = false; TooltipSettings.rarityBorder = true; TooltipRenderer.reset();
            });
            ctx.setScreen(() -> new TooltipPreviewScreen(null));
            int[] point = ctx.computeOnClient(mc -> new int[]{(mc.screen.width / 2 - 108 + 10) * 2,
                (Math.max(60,mc.screen.height / 2 - 60) + 42 + 72 + 4 + 10) * 2});
            ctx.getInput().setCursorPos(point[0],point[1]);ctx.waitTicks(4);
            var shot = ctx.takeScreenshot("skycosmetics-tooltip-preview-native-style");
            check(packPixel(ctx,shot,1) == MAGENTA, "inventory preview retains the item's native pack frame when custom outlines are off");
            check(TooltipRenderer.borderColor() == (0xFF000000 | io.github.terabold.skycosmetics.slots.RarityBackgrounds.color(
                io.github.terabold.skycosmetics.slots.Rarity.LEGENDARY)), "rarity color does not depend on slot-outline visibility");
        } finally { ctx.runOnClient(mc -> {
            mc.player.getInventory().setItem(0,original);
            io.github.terabold.skycosmetics.slots.RarityBackgrounds.set(on,rarity.shape(),rarity.fill(),rarity.opacity(),rarity.outline());
        }); }
    }

    private static int packPixel(ClientGameTestContext ctx, Path shot, int dy) throws IOException {
        int[] point = ctx.computeOnClient(mc -> {
            var box = TooltipRenderer.lastLayout(); float s = mc.getWindow().getGuiScale();
            return new int[]{(int)((box.x() + (box.padding() + box.width() / 2f) * box.scale()) * s),
                (int)((box.y() + (dy + .25f) * box.scale()) * s)};
        });
        try (NativeImage img = NativeImage.read(Files.newInputStream(shot))) { return img.getPixel(point[0], point[1]); }
    }

    private static int backgroundPixel(ClientGameTestContext ctx, Path shot) throws IOException {
        int[] point = ctx.computeOnClient(mc -> {
            var box = TooltipRenderer.lastLayout();
            double s = mc.getWindow().getGuiScale();
            return new int[]{(int) ((box.x() + (box.padding() + box.width() / 2f) * box.scale()) * s),
                (int) ((box.y() + 2 * box.scale()) * s)};
        });
        try (NativeImage img = NativeImage.read(Files.newInputStream(shot))) { return img.getPixel(point[0], point[1]); }
    }

    private static void reload(ClientGameTestContext ctx) {
        AtomicReference<CompletableFuture<Void>> future = new AtomicReference<>();
        ctx.runOnClient(mc -> future.set(mc.reloadResourcePacks()));
        ctx.waitFor(mc -> future.get().isDone(), 1200);
        check(!future.get().isCompletedExceptionally(), "resource reload succeeds");
        ctx.waitFor(mc -> mc.getOverlay() == null, 100);
    }

    private static final class Foreign implements ClientTooltipComponent {
        int text, images;
        @Override public int getHeight(Font font) { return 35; }
        @Override public int getWidth(Font font) { return 210; }
        @Override public void extractText(GuiGraphicsExtractor g, Font font, int x, int y) {
            text++;
            g.text(font, "Extra text from another mod", x + 35, y + 4, 0xFF80FFFF, false);
        }
        @Override public void extractImage(Font font, int x, int y, int w, int h, GuiGraphicsExtractor g) {
            images++;
            g.fill(x, y, x + 28, y + 28, 0xFF40A080);
        }
    }

    private static class Sample extends Screen {
        final Foreign foreign = new Foreign();
        final List<ClientTooltipComponent> parts = new ArrayList<>();
        final boolean packed;
        int passedWheels;
        Sample(boolean longer, boolean packed) {
            super(Component.literal("Tooltip test"));
            this.packed = packed;
            parts.add(ClientTooltipComponent.create(Component.literal("Item with custom content").getVisualOrderText()));
            parts.add(foreign);
            if (longer) for (int i = 0; i < 60; i++) parts.add(ClientTooltipComponent.create(Component.literal("Lore line " + i).getVisualOrderText()));
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
            g.tooltip(font, parts, mx, my, DefaultTooltipPositioner.INSTANCE, packed ? STYLE : null);
        }
        @Override public boolean mouseScrolled(double mx, double my, double dx, double dy) {
            passedWheels++;
            return true;
        }
    }

    private static final class PackedSample extends Sample implements TooltipItemSource {
        private final net.minecraft.world.item.ItemStack item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
        PackedSample() {super(true, true);}
        @Override public net.minecraft.world.item.ItemStack tooltipItem() {return item;}
    }
}
