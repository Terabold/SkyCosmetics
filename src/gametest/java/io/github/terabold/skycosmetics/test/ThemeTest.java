package io.github.terabold.skycosmetics.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.gui.GeneralSection;
import io.github.terabold.skycosmetics.gui.InventoryButton;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.gui.hub.ActionButton;
import io.github.terabold.skycosmetics.gui.hub.ColorPresets;
import io.github.terabold.skycosmetics.gui.hub.ColorSwatch;
import io.github.terabold.skycosmetics.gui.hub.SectionTab;
import io.github.terabold.skycosmetics.gui.ui.Theme;
import io.github.terabold.skycosmetics.gui.ui.Tips;
import io.github.terabold.skycosmetics.items.OwnedItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.EquipmentSlot;
import org.joml.Vector2ic;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.open;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;

/**
 * The accent color: General comes first in the sidebar with Accent Color, the presets and Reset; a real click on a
 * preset recolors the settings and the studio at once and is saved as "accent" in settings.json; a value that isn't
 * a color keeps the accent; Reset goes back to cyan. The studio in gold at the common sizes, with nothing vanilla
 * left on any tab, and the inventory brush under the mouse.
 */
public class ThemeTest implements FabricClientGameTest {
    private static final String CHEST = "t-theme-chest", SWORD = "t-theme-sword";
    private static final int GOLD = 0xFFFBBF24, BLUE = 0xFF60A5FA;

    /**
     * Tooltips keep clear of the area they belong to: right of it level with the hovered row, else left, above or
     * below; where nothing fits whole, the side with the most room, kept on screen.
     */
    private static void tooltipPlacement() {
        // A list from x 10 to 100 on a 400 x 300 screen; a 80 x 20 tooltip for the row at y 50.
        Vector2ic right = Tips.beside(10, 10, 100, 200, 10, 50).positionTooltip(400, 300, 0, 0, 80, 20);
        check(right.x() == 107 && right.y() == 50, "right of the list, level with the row: " + right);
        Vector2ic left = Tips.beside(300, 10, 395, 200, 300, 50).positionTooltip(400, 300, 0, 0, 80, 20);
        check(left.x() == 300 - 7 - 80 && left.y() == 50, "left of a list at the right edge: " + left);
        Vector2ic above = Tips.beside(10, 100, 100, 110, 30, 100, Tips.Side.ABOVE).positionTooltip(400, 300, 0, 0, 80, 20);
        check(above.x() == 30 && above.y() == 100 - 7 - 20, "above when asked: " + above);
        Vector2ic low = Tips.beside(10, 10, 100, 200, 10, 290).positionTooltip(400, 300, 0, 0, 80, 20);
        check(low.y() + 20 <= 300 - 5, "kept on screen at the bottom: " + low);
        Vector2ic wide = Tips.beside(100, 10, 300, 200, 100, 50).positionTooltip(400, 300, 0, 0, 150, 20);
        check(wide.x() + 150 <= 400 - 5 && wide.x() >= 5, "too wide for either side: on screen anyway: " + wide);
        Tips.Hover h = new Tips.Hover();
        check(!h.settled("row") && !h.settled("row") && h.settled("row", 0) && !h.settled(null), "a hover waits, then settles");
        System.out.println("[SkyCosmeticsTest] tooltip placement checks passed");
    }

    @Override
    public void runTest(ClientGameTestContext ctx) {
        tooltipPlacement();
        ctx.waitFor(mc -> !Repo.get().skins.isEmpty(), 20 * 120);
        int before = ctx.computeOnClient(mc -> Theme.accent());
        int[] size = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("item replace entity @a armor.chest with minecraft:leather_chestplate[minecraft:custom_data="
                + "{id:\"THEME_TUNIC\",uuid:\"" + CHEST + "\"},minecraft:custom_name={text:\"Theme Tunic\",color:\"gold\","
                + "italic:false},minecraft:lore=[\"LEGENDARY CHESTPLATE\"]]");
            sp.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:diamond_sword[minecraft:custom_data="
                + "{id:\"THEME_SWORD\",uuid:\"" + SWORD + "\"},minecraft:lore=[\"LEGENDARY SWORD\"]]");
            ctx.runOnClient(mc -> {
                mc.player.getInventory().setSelectedSlot(0);
                Theme.setAccent(Theme.DEFAULT_ACCENT);
            });
            ctx.waitTicks(5);

            defaults(ctx);
            presets(ctx);
            stored(ctx);
            studio(ctx);
            brush(ctx);
            reset(ctx);
        } finally {
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> {
                Theme.setAccent(before);
                Settings.save();
                OwnedItems.forget(CHEST);
                OwnedItems.forget(SWORD);
            });
            ctx.getInput().resizeWindow(size[0], size[1]);
            ctx.runOnClient(mc -> {
                mc.options.guiScale().set(0);
                mc.resizeGui();
            });
        }
        System.out.println("[SkyCosmeticsTest] theme checks passed");
    }

    /** Cyan to start with; General is first in the sidebar, and Reset waits for a change. */
    private static void defaults(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            check(Theme.accent() == 0xFF22D3EE && Theme.ACCENT == 0xFF22D3EE, "the accent starts cyan");
            check(Theme.ON_ACCENT != 0xFFFFFFFF, "text on the light cyan gradient is dark");
        });
        window(ctx, 1920, 1080, 2);
        settings(ctx);
        ctx.runOnClient(mc -> {
            SettingsScreen s = (SettingsScreen) mc.screen;
            SectionTab first = s.tabs().getFirst();
            check(first.section().id().equals(GeneralSection.ID), "General is first in the sidebar");
            check(s.widget("accent") instanceof ColorSwatch c && c.value() == 0xFF22D3EE, "the accent swatch shows cyan");
            check(s.widget("accentPresets") instanceof ColorPresets, "the presets are pills");
            check(s.widget("resetAccent") instanceof ActionButton b && !b.active, "Reset is grayed out at the default");
        });
        ctx.takeScreenshot("skycosmetics-70-theme-general-cyan");
    }

    /** A real click on Gold recolors everything at once; Reset wakes up; settings.json has it. */
    private static void presets(ClientGameTestContext ctx) {
        clickPreset(ctx, "Gold");
        ctx.runOnClient(mc -> {
            check(Theme.accent() == GOLD, "Gold is the accent: " + Integer.toHexString(Theme.accent()));
            check(Theme.ACCENT == GOLD && Theme.GRADIENT_START != 0xFF11BFD9, "the derived colors followed");
            SettingsScreen s = (SettingsScreen) mc.screen;
            check(s.widget("accent") instanceof ColorSwatch c && c.value() == GOLD, "the swatch shows gold");
            check(s.widget("resetAccent").active, "Reset can be pressed now");
        });
        ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-71-theme-general-gold");
        ctx.waitFor(mc -> read().contains("\"#FBBF24\""), 20 * 5); // settings.json stores the accent

        // The search finds the presets by color name, and its highlights take the new accent.
        ctx.runOnClient(mc -> ((SettingsScreen) mc.screen).search().setValue("blue"));
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).rowIds().contains("accentPresets")),
            "a search for a color finds the presets");
        clickPreset(ctx, "Blue");
        check(ctx.computeOnClient(mc -> Theme.accent() == BLUE && Theme.ON_ACCENT == 0xFFFFFFFF),
            "Blue from the search results, with white text on its gradient");
        ctx.waitTicks(2);
        ctx.takeScreenshot("skycosmetics-72-theme-search-blue");
        ctx.runOnClient(mc -> ((SettingsScreen) mc.screen).search().setValue(""));
        ctx.waitTicks(2);
        clickPreset(ctx, "Gold");

        window(ctx, 1920, 1080, 4);
        settings(ctx);
        ctx.takeScreenshot("skycosmetics-73-theme-general-guiscale-4");
        window(ctx, 854, 480, 0);
        settings(ctx);
        ctx.takeScreenshot("skycosmetics-73-theme-general-small-window");
    }

    /** settings.json: a value that isn't a color keeps the accent; a color is read. */
    private static void stored(ClientGameTestContext ctx) {
        ctx.waitFor(mc -> read().contains("\"#FBBF24\""), 20 * 5); // nothing still waiting to be written
        JsonObject o = JsonParser.parseString(read()).getAsJsonObject();
        o.addProperty("accent", "#12ZZ45");
        write(o.toString());
        ctx.runOnClient(mc -> {
            Settings.load();
            check(Theme.accent() == GOLD, "a bad accent in settings.json keeps the accent");
        });
        o.addProperty("accent", "60a5fa");
        write(o.toString());
        ctx.runOnClient(mc -> {
            Settings.load();
            check(Theme.accent() == BLUE, "an accent without # is read too");
            Theme.setAccent(GOLD);
            Settings.save();
        });
    }

    /** The studio in gold: every tab only has themed widgets; screenshots at the common sizes. */
    private static void studio(ClientGameTestContext ctx) {
        int[][] windows = {{1920, 1080, 2}, {1920, 1080, 3}, {1920, 1080, 4}, {854, 480, 0}};
        for (int[] w : windows) {
            window(ctx, w[0], w[1], w[2]);
            open(ctx, mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST));
            String size = w[0] + "x" + w[1] + (w[2] > 0 ? "-guiscale-" + w[2] : "");
            for (String tab : new String[]{"Skins", "Dyes", "Name & Glint", "Saved"}) {
                if (!ctx.tryClickScreenButton(tab) && tab.equals("Name & Glint")) ctx.clickScreenButton("Name");
                ctx.waitTicks(2);
                ctx.runOnClient(mc -> themedOnly(mc, size + " " + tab));
                if (tab.equals("Dyes") || tab.equals("Name & Glint")) {
                    ctx.takeScreenshot("skycosmetics-74-theme-gold-studio-" + size + "-" + (tab.equals("Dyes") ? "dyes" : "name-glint"));
                }
            }
        }
        // The custom dye pop-up in gold.
        window(ctx, 1920, 1080, 2);
        open(ctx, mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST));
        ctx.clickScreenButton("Dyes");
        ctx.clickScreenButton("Custom Dye…");
        ctx.waitTicks(4);
        check(ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).popup() != null), "the custom dye pop-up opened");
        ctx.takeScreenshot("skycosmetics-75-theme-gold-custom-dye");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(2);
    }

    /** The inventory brush under the mouse takes the accent. */
    private static void brush(ClientGameTestContext ctx) {
        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        ctx.waitForScreen(InventoryScreen.class);
        int[] at = ctx.computeOnClient(mc -> {
            for (GuiEventListener l : mc.screen.children()) {
                if (l instanceof InventoryButton b && b.visible) return new int[]{b.getX() + 5, b.getY() + 5};
            }
            return null;
        });
        check(at != null, "the inventory has the brush");
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(at[0] * scale, at[1] * scale);
        ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-76-theme-gold-brush-hover");
        ctx.getInput().setCursorPos(0, 0);
        ctx.setScreen(() -> null);
    }

    /** Reset puts cyan back, and settings.json with it. */
    private static void reset(ClientGameTestContext ctx) {
        window(ctx, 1920, 1080, 2);
        settings(ctx);
        ctx.runOnClient(mc -> ((SettingsScreen) mc.screen).scrollTo("resetAccent"));
        ctx.waitTicks(1);
        int[] at = ctx.computeOnClient(mc -> {
            AbstractWidget b = ((SettingsScreen) mc.screen).widget("resetAccent");
            return new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        });
        clickAt(ctx, at[0], at[1]);
        ctx.runOnClient(mc -> {
            check(Theme.accent() == Theme.DEFAULT_ACCENT, "Reset goes back to cyan");
            check(!((SettingsScreen) mc.screen).widget("resetAccent").active, "and grays itself out");
        });
        ctx.waitFor(mc -> read().contains("\"#22D3EE\""), 20 * 5); // settings.json has cyan again
    }

    // ------------------------------------------------------------ helpers ---

    /** Nothing on the studio's screen is a vanilla widget: every one is SkyCosmetics' own, drawn in the theme. */
    private static void themedOnly(Minecraft mc, String where) {
        check(mc.screen instanceof StudioScreen, where + ": the studio is open");
        for (GuiEventListener l : mc.screen.children()) {
            check(l.getClass().getName().startsWith("io.github.terabold.skycosmetics."),
                where + ": a vanilla widget is left: " + l.getClass().getName());
        }
    }

    private static void settings(ClientGameTestContext ctx) {
        ctx.setScreen(() -> new SettingsScreen(null, GeneralSection.ID));
        ctx.waitForScreen(SettingsScreen.class);
        ctx.getInput().setCursorPos(0, 0);
        ctx.runOnClient(mc -> mc.getToastManager().clear());
        ctx.waitTicks(3);
    }

    private static void clickPreset(ClientGameTestContext ctx, String name) {
        ctx.runOnClient(mc -> ((SettingsScreen) mc.screen).scrollTo("accentPresets"));
        ctx.waitTicks(1);
        int[] at = ctx.computeOnClient(mc -> ((ColorPresets) ((SettingsScreen) mc.screen).widget("accentPresets")).centerOf(name));
        check(at != null, "a preset named " + name);
        clickAt(ctx, at[0], at[1]);
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTicks(1);
    }

    private static void clickAt(ClientGameTestContext ctx, int x, int y) {
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(x * scale, y * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("skycosmetics/settings.json");
    }

    private static String read() {
        try {
            return Files.readString(file(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void write(String text) {
        try {
            Files.writeString(file(), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
