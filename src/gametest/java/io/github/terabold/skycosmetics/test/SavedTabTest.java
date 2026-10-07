package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.click;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.open;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.widget;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.BOOTS;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.GONE;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.HELM;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.ORB;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.PET;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.SWORD;

/**
 * Saved: looks in sections (Helmets, Armor, Weapons & Tools, Pets, Power Orbs, Every Item of a Type), a row
 * opening to each of its changes with its own ×, the row's big × for the whole look, Undo, and Edit.
 */
final class SavedTabTest {
    private SavedTabTest() {}

    static void run(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Looks.putItem(HELM, "ZEPHYR_HELMET", new Looks.Look("NECRON_DIAMOND_KNIGHT", null, "&6Zephyr", "on", "#FF3B3B",
                2.0f, 1.5f, "Zephyr Helmet"));
            Looks.putItem(BOOTS, "SPRING_BOOTS", new Looks.Look(null, "DYE_AURORA", null, null, "Spring Boots"));
            Looks.putItem(SWORD, "ZEPHYR_SWORD", new Looks.Look(null, null, "&bWind", null, "Zephyr Sword"));
            Looks.putItem(PET, "PET:BEE", new Looks.Look(null, null, null, "off", "Bee"));
            Looks.putItem(ORB, "PLASMAFLUX_POWER_ORB", new Looks.Look("BEACH_BALL_FLUX", null, null, null, "Orb"));
            Looks.putItem(GONE, "ZEPHYR_LEGGINGS", new Looks.Look(null, "#FF8800", null, null, "Zephyr Leggings"));
            Looks.put(true, "SPRING_BOOTS", new Looks.Look(null, "DYE_WARDEN", null, null, "Every Spring Boots"));
        });
        window(ctx, 1920, 1080, 2);
        open(ctx, mc -> mc.player.getInventory().getItem(0));
        ctx.clickScreenButton("Saved");
        ctx.waitTicks(3);

        List<String> rows = rows(ctx);
        System.out.println("[SkyCosmeticsTest] Saved lists " + rows);
        List<String> headers = rows.stream().filter(r -> r.startsWith("# ")).toList();
        check(headers.equals(List.of("# Helmets (1)", "# Armor (2)", "# Weapons & Tools (1)", "# Pets (1)", "# Power Orbs (1)",
            "# Every Item of a Type (1)")), "Saved has one section per kind of item, in order: " + headers);
        int gone = indexOf(rows, "> Zephyr Leggings | ");
        check(gone > rows.indexOf("# Armor (2)") && gone < rows.indexOf("# Weapons & Tools (1)"),
            "a look on an item never seen is sorted by its saved type (Armor): " + rows);
        check(indexOf(rows, "> Every Spring Boots | " + ctx.computeOnClient(mc -> Repo.get().dye("DYE_WARDEN").name)) >= 0,
            "the look for every Spring Boots is listed by its type");
        check(ctx.computeOnClient(mc -> screen(mc).footer()).equals("7 looks"), "the footer counts the looks");

        // A click on the row opens its changes, one per line, each with its own ×.
        int[] helm = buttons(ctx, "I" + HELM);
        click(ctx, helm[0], helm[1]);
        ctx.waitTicks(2);
        rows = rows(ctx);
        int at = indexOf(rows, "v Zephyr Helmet");
        check(at >= 0, "the helmet row is open: " + rows);
        List<String> parts = new ArrayList<>();
        for (int i = at + 1; i < rows.size() && rows.get(i).startsWith("  "); i++) parts.add(rows.get(i).trim());
        System.out.println("[SkyCosmeticsTest] the open helmet look: " + parts);
        String knight = ctx.computeOnClient(mc -> Repo.get().skin("NECRON_DIAMOND_KNIGHT").name);
        check(parts.equals(List.of("Skin: " + knight, "Name: Zephyr", "Glint: On", "Glint Color: #FF3B3B",
            "Glint Speed: 2.0x", "Glint Strength: 1.5x", "[Edit]")), "each change on its own line: " + parts);
        ctx.takeScreenshot("skycosmetics-60-saved-open");

        // Hover: the row under the mouse lights up (screenshot), and its × says what it removes.
        int[] boots = buttons(ctx, "I" + BOOTS);
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(boots[0] * scale, boots[1] * scale);
        ctx.waitTicks(2);
        ctx.takeScreenshot("skycosmetics-61-saved-hover");
        ctx.getInput().setCursorPos(boots[2] * scale, boots[3] * scale);
        ctx.waitTicks(10); // a row's tooltip waits for the mouse to rest
        ctx.takeScreenshot("skycosmetics-61b-saved-remove-hover");
        // Its tooltip shows beside the list, level with the row: never over the rows or buttons around it.
        for (int[] size : new int[][]{{1920, 1080, 2}, {1920, 1080, 3}, {854, 480, 2}}) {
            window(ctx, size[0], size[1], size[2]);
            int[] b = buttons(ctx, "I" + BOOTS);
            double sc = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
            ctx.getInput().setCursorPos(b[0] * sc, b[1] * sc);
            ctx.waitTicks(10);
            ctx.takeScreenshot("skycosmetics-61c-list-tooltip-" + size[0] + "x" + size[1] + "-guiscale-" + size[2]);
        }
        window(ctx, 1920, 1080, 2);
        ctx.getInput().setCursorPos(0, 0);
        ctx.waitTicks(1);

        // One change off: only the glint color goes; Undo puts it back.
        helm = buttons(ctx, "I" + HELM);
        int color = 4 + 2 * parts.indexOf("Glint Color: #FF3B3B");
        click(ctx, helm[color], helm[color + 1]);
        ctx.waitTicks(2);
        Looks.Look l = ctx.computeOnClient(mc -> Looks.byUuid(HELM));
        check(l != null && l.glintColor() == null && "NECRON_DIAMOND_KNIGHT".equals(l.skin()) && "on".equals(l.glint())
            && l.glintSpeed() == 2.0f && l.glintStrength() == 1.5f && "&6Zephyr".equals(l.name()),
            "the part's × removes only the glint color: " + l);
        String foot = ctx.computeOnClient(mc -> screen(mc).footer());
        check(foot.equals("Removed the glint color on Zephyr Helmet"), "the footer says what was removed: " + foot);
        check(!rows(ctx).contains("  Glint Color: #FF3B3B"), "the removed change is gone from the list");
        ctx.clickScreenButton("Undo");
        ctx.waitTicks(2);
        check("#FF3B3B".equals(ctx.computeOnClient(mc -> Looks.byUuid(HELM).glintColor())), "Undo puts the glint color back");
        check(ctx.computeOnClient(mc -> screen(mc).footer()).equals("Put back"), "the footer says it was put back");

        // The row's big ×: the whole look; Undo.
        boots = buttons(ctx, "I" + BOOTS);
        click(ctx, boots[2], boots[3]);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> Looks.byUuid(BOOTS)) == null, "the big × removes the whole look");
        check(ctx.computeOnClient(mc -> screen(mc).footer()).equals("Removed the look on Spring Boots"), "and says so");
        check(rows(ctx).stream().noneMatch(r -> r.startsWith("> Spring Boots")), "its row is gone");
        ctx.getInput().holdControl();
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_Z);
        ctx.getInput().releaseControl();
        ctx.waitTicks(2);
        check("DYE_AURORA".equals(ctx.computeOnClient(mc -> Looks.byUuid(BOOTS).dye())), "Ctrl+Z brings the whole look back");
        check(ctx.computeOnClient(mc -> Looks.itemType(BOOTS)).equals("SPRING_BOOTS"), "with its item type");

        // The type look's × removes the look for every Spring Boots, not the boots' own look.
        int[] type = buttons(ctx, "TSPRING_BOOTS");
        click(ctx, type[2], type[3]);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> Looks.byType("SPRING_BOOTS")) == null
            && ctx.computeOnClient(mc -> Looks.byUuid(BOOTS)) != null, "the type row's × removes only the type look");

        // The search finds a look by what it uses.
        StudioLayoutTest.focusSearch(ctx);
        ctx.getInput().typeChars("aurora");
        ctx.waitTicks(2);
        rows = rows(ctx);
        check(rows.size() == 2 && rows.get(0).equals("# Armor (1)") && rows.get(1).startsWith("> Spring Boots | "),
            "searching 'aurora' finds the boots: " + rows);
        ctx.takeScreenshot("skycosmetics-62-saved-search");
        for (int i = 0; i < 6; i++) ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE);
        ctx.waitTicks(2);

        // Sizes: GUI scales 1, 3 and 4, and a small window (the compact layout).
        window(ctx, 1920, 1080, 1);
        ctx.takeScreenshot("skycosmetics-63-saved-guiscale-1");
        window(ctx, 1920, 1080, 4);
        ctx.takeScreenshot("skycosmetics-63-saved-guiscale-4");
        window(ctx, 1920, 1080, 3);
        ctx.takeScreenshot("skycosmetics-63-saved-guiscale-3");
        window(ctx, 854, 480, 2);
        ctx.takeScreenshot("skycosmetics-63b-saved-small-window");
        ctx.runOnClient(mc -> screen(mc).setSavedOpen("I" + HELM, true));
        ctx.waitTicks(2);
        int[] small = buttons(ctx, "I" + HELM);
        int w = ctx.computeOnClient(mc -> mc.screen.width);
        check(small != null && small[2] < w - 8, "the × fits in a small window");
        ctx.takeScreenshot("skycosmetics-63c-saved-small-window-open");
        window(ctx, 1920, 1080, 2);

        // Edit: the editor opens on the look's item.
        helm = buttons(ctx, "I" + HELM);
        check(helm.length >= 6, "the helmet row is open with its Edit link");
        click(ctx, helm[helm.length - 2], helm[helm.length - 1]);
        ctx.waitTicks(2);
        AbstractWidget skins = ctx.computeOnClient(mc -> widget(mc, "Skins"));
        check(skins != null && !skins.active, "Edit opens the Skins tab");
        check("inv:0".equals(ctx.computeOnClient(mc -> screen(mc).pickedRole())), "on the helmet");
        System.out.println("[SkyCosmeticsTest] Saved: sections, open rows, per-change and whole-look removal, Undo, Edit: OK");
    }

    private static int indexOf(List<String> rows, String start) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).startsWith(start)) return i;
        return -1;
    }

    private static List<String> rows(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> screen(mc).savedRows());
    }

    private static int[] buttons(ClientGameTestContext ctx, String key) {
        int[] b = ctx.computeOnClient(mc -> screen(mc).savedButtons(key));
        check(b != null, "Saved lists " + key);
        return b;
    }

    private static StudioScreen screen(Minecraft mc) {
        return (StudioScreen) mc.screen;
    }
}
