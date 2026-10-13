package io.github.terabold.skycosmetics.test;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.gui.hub.ToggleSwitch;
import io.github.terabold.skycosmetics.gui.ui.Anim;
import io.github.terabold.skycosmetics.gui.ui.Shapes;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.render.Chroma;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;

/** Reproduces the disabled track's alpha seam on the GPU, and checks compact help stays searchable. */
public class MenuPolishTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext ctx) {
        int accent = ctx.computeOnClient(mc -> Theme.accent());
        int[] size = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        try (var world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            ctx.runOnClient(mc -> Theme.setAccent(0xFFF87171));
            for (int scale : new int[]{2, 3, 4}) {
                window(ctx, 1920, 1080, scale);
                ctx.setScreen(Tracks::new);
                ctx.getInput().setCursorPos(0, 0);
                ctx.waitTicks(8);
                try (var input = Files.newInputStream(ctx.takeScreenshot("skycosmetics-130-disabled-tracks-" + scale));
                     NativeImage image = NativeImage.read(input)) {
                    // The center ends at x=108, where the right cap begins. Both must blend only once.
                    int left = image.getPixel(107 * scale, 42 * scale), right = image.getPixel(108 * scale, 42 * scale);
                    check(distance(left, right) <= 4, "translucent gradient has no cap seam at scale " + scale);
                    int middle = image.getPixel(70 * scale, 42 * scale);
                    int expected = Theme.mix(Theme.BODY, Theme.mix(0xFFF87171, 0xFFFF9080, .5f), .4f);
                    check(distance(middle, expected) <= 4, "gradient opacity is interpolated, not accumulated");
                } catch (IOException e) { throw new RuntimeException(e); }
            }
            window(ctx, 1280, 720, 2);
            settingsInventory(ctx);
            ctx.setScreen(() -> new SettingsScreen(null, "zoom"));
            ctx.waitTicks(5);
            ctx.runOnClient(mc -> {
                SettingsScreen s = (SettingsScreen) mc.screen;
                check(s.rowText("initial").size() == 1, "obvious sliders have a compact label");
                check(s.tabs().stream().allMatch(t -> t.getHeight() == 22), "sidebar uses single-line sections");
                s.search().setValue("logarithmic");
                check(s.rowIds().contains("initial"), "hover help still participates in search");
                check(s.rowText("initial").size() > 1, "search reveals the matching explanation");
                s.search().setValue("");
            });
            ctx.takeScreenshot("skycosmetics-131-compact-zoom");
            ctx.setScreen(() -> new SettingsScreen(null, "tooltips"));
            ctx.waitTicks(5);
            ctx.takeScreenshot("skycosmetics-132-compact-tooltips");
            var palette = ctx.computeOnClient(mc -> Chroma.write());
            try {
                for (int width : new int[]{1280, 854}) {
                    window(ctx, width, 720, 2);
                    for (String id : ctx.computeOnClient(mc -> Hub.sections().stream().map(s -> s.id()).toList())) {
                        ctx.setScreen(() -> new SettingsScreen(null, id)); ctx.waitTicks(3);
                        ctx.runOnClient(mc -> compactLayout((SettingsScreen)mc.screen));
                        ctx.takeScreenshot("skycosmetics-ui-" + id + "-" + width);
                    }
                }
                window(ctx,1280,720,2);
                ctx.runOnClient(mc -> {Chroma.custom = true;Chroma.count = 2;Chroma.changed();});
                ctx.setScreen(() -> new SettingsScreen(null,"chroma"));ctx.waitTicks(3);
                ctx.runOnClient(mc -> {
                    var screen = (SettingsScreen)mc.screen;
                    check(screen.widget("color0") != null && screen.widget("color1") != null && screen.widget("color2") == null && screen.widget("saturation") == null,
                        "custom chroma shows only the chosen palette colors");
                    compactLayout(screen);
                });
                ctx.takeScreenshot("skycosmetics-ui-chroma-custom");
            } finally {ctx.runOnClient(mc -> Chroma.read(palette));}
        } finally {
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> Theme.setAccent(accent));
            ctx.getInput().resizeWindow(size[0], size[1]);
            ctx.runOnClient(mc -> { mc.options.guiScale().set(0); mc.resizeGui(); });
        }
        System.out.println("[SkyCosmeticsTest] menu alpha seam and compact help checks passed");
    }

    private static int distance(int a, int b) {
        return Math.max(Math.abs((a >> 16 & 255) - (b >> 16 & 255)),
            Math.max(Math.abs((a >> 8 & 255) - (b >> 8 & 255)), Math.abs((a & 255) - (b & 255))));
    }

    private static void settingsInventory(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            StringBuilder report = new StringBuilder("# Current settings inventory\n\nGenerated from the running client registry. Values are test-client values, not the live instance config. Conditional rows are included.\n\n");
            for (var section : Hub.sections()) {
                report.append("## ").append(section.name().getString()).append("\n\n| Setting | Control | Choices / current value | Help |\n|---|---|---|---|\n");
                for (var option : section.rows().apply(null)) {
                    var control = option.control();
                    if (control instanceof io.github.terabold.skycosmetics.hub.Control.Header) continue;
                    String value = switch (control) {
                        case io.github.terabold.skycosmetics.hub.Control.Toggle c -> Boolean.toString(c.get().getAsBoolean());
                        case io.github.terabold.skycosmetics.hub.Control.Slider c -> c.label().apply(c.get().getAsDouble()).getString();
                        case io.github.terabold.skycosmetics.hub.Control.Color c -> String.format("#%08X%s", c.get().getAsInt(), c.alpha()?" with opacity":"");
                        case io.github.terabold.skycosmetics.hub.Control.Choice<?> c -> choiceLabels(c);
                        case io.github.terabold.skycosmetics.hub.Control.Action c -> c.label().getString();
                        case io.github.terabold.skycosmetics.hub.Control.Key c -> c.mapping().get().getTranslatedKeyMessage().getString();
                        default -> "Preview or custom editor";
                    };
                    report.append("| ").append(clean(option.title().getString())).append(" (`").append(option.id()).append("`) | ")
                        .append(control.getClass().getSimpleName()).append(" | ").append(clean(value)).append(" | ").append(clean(option.help().getString())).append(" |\n");
                }
                report.append("\n");
            }
            try {Files.writeString(mc.gameDirectory.toPath().resolve("test-settings-inventory.md"),report);}
            catch(IOException e){throw new AssertionError("settings inventory",e);}
        });
    }
    private static <T> String choiceLabels(io.github.terabold.skycosmetics.hub.Control.Choice<T> choice) {
        return choice.values().stream().map(v -> choice.label().apply(v).getString()).collect(java.util.stream.Collectors.joining(", "));
    }
    private static String clean(String value) {return value.replace("|","/").replace("\n"," ");}

    private static void compactLayout(SettingsScreen screen) {
        int[] pane = screen.pane();
        var widgets = screen.rowIds().stream().map(screen::widget).filter(java.util.Objects::nonNull).toList();
        for (var a : widgets) {
            check(a.getX() >= pane[0] && a.getRight() <= pane[0] + pane[2], "setting fits its pane: " + a.getMessage().getString());
            if (a instanceof ToggleSwitch) check(a.getWidth() == ToggleSwitch.W, "paired switches retain their compact size");
            for (var b : widgets) if (a != b) check(!(a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom()),
                "compact settings controls do not overlap");
        }
    }

    private static final class Tracks extends Screen {
        Tracks() { super(Component.literal("Disabled tracks")); }
        @Override protected void init() {
            for (int i = 0; i < 4; i++) {
                ToggleSwitch toggle = new ToggleSwitch(Component.literal("Disabled on"), () -> true, b -> {});
                toggle.active = false;
                toggle.setPosition(150, 30 + i * 30);
                addRenderableWidget(toggle);
            }
        }
        @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}
        @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
            Anim.frame();
            g.fill(0, 0, width, height, Theme.BODY);
            Shapes.roundGradient(g, 30, 30, 90, 24, 12, 0x66F87171, 0x66FF9080);
            super.extractRenderState(g, mx, my, delta);
        }
    }
}
