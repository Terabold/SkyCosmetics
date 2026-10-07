package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.compat.OtherLooks;
import io.github.terabold.skycosmetics.compat.OtherLooks.Kind;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.test.fake.FakeSkyOcean;
import io.github.terabold.skycosmetics.test.fake.FakeSkyblocker;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.click;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.open;
import static io.github.terabold.skycosmetics.test.StudioLayoutTest.window;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.BOOTS;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.CHEST;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.HELM;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.ORB;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.SWORD;
import static io.github.terabold.skycosmetics.test.StudioTabsClientTest.UNSEEN;

/**
 * Other mods' looks: fake Skyblocker and SkyOcean mods built like the real ones list every change, per item, in
 * Other Mods and as chips in the editor ("Dyed by Skyblocker" on dyed Spring Boots). A removal goes through the
 * mod's own config and save, Undo puts it back, "Move Here" makes it a SkyCosmetics look, a mod that refuses keeps
 * its change, and a mod known only from its file (fixtures in the run's config folder) is listed read-only.
 */
final class OtherModsTest {
    private static final Path NO_FILE = Path.of("no-such-file.json");

    private OtherModsTest() {}

    static void run(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            String knight = Repo.get().skin("NECRON_DIAMOND_KNIGHT").textures[0];
            FakeSkyblocker.reset(c -> {
                var g = c.general;
                g.customDyeColors.put(BOOTS, 0x33CC77);
                g.customItemNames.put(BOOTS, Component.literal("Bouncy Boots").withStyle(ChatFormatting.GOLD));
                g.customArmorTrims.put(BOOTS, new FakeSkyblocker.ArmorTrimId(id("minecraft", "gold"), id("minecraft", "sentry")));
                g.customGlint.put(SWORD, false);
                g.customItemModel.put(SWORD, id("minecraft", "stone_sword"));
                g.customHelmetTextures.put(HELM, knight);
                g.customAnimatedDyes.put(UNSEEN, new FakeSkyblocker.AnimatedDye(List.of(new FakeSkyblocker.Keyframe(0xFF0000, 0),
                    new FakeSkyblocker.Keyframe(0x0000FF, 1)), true, 0, 2));
            });
            FakeSkyOcean.reset();
            FakeSkyOcean.of(new FakeSkyOcean.UuidKey(UUID.fromString(CHEST)))
                .with(FakeSkyOcean.COLOR, new FakeSkyOcean.StaticItemColor(0x8844FF))
                .with(FakeSkyOcean.NAME, Component.literal("Storm Coat").withStyle(ChatFormatting.AQUA))
                .with(FakeSkyOcean.TRIM, new FakeSkyOcean.ArmorTrim(id("minecraft", "diamond"), id("minecraft", "eye")));
            FakeSkyOcean.of(new FakeSkyOcean.UuidKey(UUID.fromString(ORB)))
                .with(FakeSkyOcean.COLOR, new FakeSkyOcean.SkyBlockDye("dye_aurora"));
            FakeSkyOcean.of(new FakeSkyOcean.IdKey("item:zephyr_sword"))
                .with(FakeSkyOcean.SKIN, new FakeSkyOcean.SkyblockSkin("NECRON_DIAMOND_KNIGHT"))
                .with(FakeSkyOcean.GLINT, true);
            OtherLooks.use(List.of(OtherLooks.skyblocker(FakeSkyblocker.class.getName(), NO_FILE),
                OtherLooks.skyOcean(FakeSkyOcean.class.getName(), NO_FILE)));
            // The real case: boots dyed in SkyCosmetics and in Skyblocker, which draws over it.
            Looks.putItem(BOOTS, "SPRING_BOOTS", new Looks.Look(null, "DYE_AURORA", null, null, "Spring Boots"));
        });

        chips(ctx);
        list(ctx);
        readOnly(ctx);
    }

    /** The editor on the boots: a chip per change, a click removes it there (Undo), a right-click moves it here. */
    private static void chips(ClientGameTestContext ctx) {
        window(ctx, 1920, 1080, 2);
        open(ctx, mc -> mc.player.getInventory().getItem(1));
        Map<String, int[]> chips = ctx.computeOnClient(mc -> screen(mc).chips());
        System.out.println("[SkyCosmeticsTest] chips on the boots: " + chips.keySet());
        check(chips.keySet().equals(java.util.Set.of("Dyed by Skyblocker", "Renamed by Skyblocker", "Trim by Skyblocker")),
            "the boots show Skyblocker's dye, name and trim as chips: " + chips.keySet());
        ctx.takeScreenshot("skycosmetics-64-other-mod-chips");
        int[] dye = chips.get("Dyed by Skyblocker");
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(dye[0] * scale, dye[1] * scale);
        ctx.waitTicks(10); // tooltips wait for the mouse to rest
        ctx.takeScreenshot("skycosmetics-64b-other-mod-chip-tooltip");

        click(ctx, dye[0], dye[1]);
        ctx.waitTicks(2);
        check(!ctx.computeOnClient(mc -> FakeSkyblocker.get().general.customDyeColors.containsKey(BOOTS)),
            "a click on the chip removes Skyblocker's dye in Skyblocker's live config");
        check(FakeSkyblocker.saves == 1, "through Skyblocker's own update, which saves it: " + FakeSkyblocker.saves);
        check(ctx.computeOnClient(mc -> screen(mc).footer()).equals("Removed Skyblocker's dye"), "the footer says so");
        check(!ctx.computeOnClient(mc -> screen(mc).chips()).containsKey("Dyed by Skyblocker"), "its chip is gone");
        ctx.clickScreenButton("Undo");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> FakeSkyblocker.get().general.customDyeColors.getInt(BOOTS)) == 0x33CC77
            && FakeSkyblocker.saves == 2, "Undo puts the dye back in Skyblocker, saved again");
        check(ctx.computeOnClient(mc -> screen(mc).chips()).containsKey("Dyed by Skyblocker"), "and its chip");

        // Right-click: the name moves into SkyCosmetics, styles and all, and leaves Skyblocker.
        int[] name = ctx.computeOnClient(mc -> screen(mc).chips()).get("Renamed by Skyblocker");
        ctx.getInput().setCursorPos(name[0] * scale, name[1] * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        ctx.waitTicks(2);
        Looks.Look l = ctx.computeOnClient(mc -> Looks.byUuid(BOOTS));
        check(l != null && "&6Bouncy Boots".equals(l.name()) && "DYE_AURORA".equals(l.dye()),
            "a right-click moves the name into the boots' own look, keeping its dye: " + l);
        check(!ctx.computeOnClient(mc -> FakeSkyblocker.get().general.customItemNames.containsKey(BOOTS)), "and out of Skyblocker");
        check(ctx.computeOnClient(mc -> screen(mc).footer()).equals("Moved the name to SkyCosmetics"), "the footer says so");
        String shown = ctx.computeOnClient(mc -> mc.player.getInventory().getItem(1).getStyledHoverName().getString());
        check(shown.equals("Bouncy Boots"), "the boots show the moved name: " + shown);
        ctx.clickScreenButton("Undo");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> FakeSkyblocker.get().general.customItemNames.containsKey(BOOTS))
            && ctx.computeOnClient(mc -> Looks.byUuid(BOOTS).name()) == null, "Undo moves it back to Skyblocker");
    }

    /** Other Mods: sections, items by UUID through My Items, each change with its mod; ×, Move Here, a refusal. */
    private static void list(ClientGameTestContext ctx) {
        if (!ctx.tryClickScreenButton("Other Mods")) ctx.clickScreenButton("Mods");
        ctx.waitTicks(3);
        List<String> rows = rows(ctx);
        System.out.println("[SkyCosmeticsTest] Other Mods lists " + rows);
        List<String> headers = rows.stream().filter(r -> r.startsWith("# ")).toList();
        check(headers.equals(List.of("# Helmets (1)", "# Armor (2)", "# Weapons & Tools (1)", "# Power Orbs (1)",
            "# Every Item of a Type (1)", "# Items Not Seen Yet (1)")), "Other Mods groups per item, in Saved's sections: " + headers);
        String knight = ctx.computeOnClient(mc -> Repo.get().skin("NECRON_DIAMOND_KNIGHT").name);
        String aurora = ctx.computeOnClient(mc -> Repo.get().dye("DYE_AURORA").name);
        for (String want : new String[]{"Zephyr Helmet [HELMETS]", "  Skyblocker skin: " + knight + " [move]",
            "Spring Boots [ARMOR]", "  Skyblocker dye: #33CC77 [move]", "  Skyblocker name: Bouncy Boots [move]",
            "  Skyblocker trim: Sentry · Gold", "Zephyr Chestplate [ARMOR]", "  SkyOcean dye: #8844FF [move]",
            "  SkyOcean name: Storm Coat [move]", "  SkyOcean trim: Eye · Diamond", "Zephyr Sword [WEAPONS]",
            "  Skyblocker glint: Off [move]", "  Skyblocker model: Stone Sword", "Plasmaflux Power Orb [ORBS]",
            "  SkyOcean dye: " + aurora + " [move]", "Every Zephyr Sword [TYPES]", "  SkyOcean skin: " + knight + " [move]",
            "  SkyOcean glint: On [move]", "Unknown item [UNSEEN]", "  Skyblocker dye: Animated · 2 colors [move]"}) {
            check(rows.contains(want), "Other Mods lists '" + want + "': " + rows);
        }
        ctx.waitFor(mc -> screen(mc).footer().equals("13 changes · 7 items"), 120); // once the last message is old
        ctx.takeScreenshot("skycosmetics-65-other-mods");

        // ×: SkyOcean's color comes off its item data, and SkyOcean is asked to save; Undo.
        int[] b = buttons(ctx, CHEST, "SkyOcean", Kind.DYE);
        click(ctx, b[0], b[1]);
        ctx.waitTicks(2);
        FakeSkyOcean.Data chest = FakeSkyOcean.of(new FakeSkyOcean.UuidKey(UUID.fromString(CHEST)));
        check(!chest.getData().containsKey(FakeSkyOcean.COLOR) && chest.getData().containsKey(FakeSkyOcean.NAME)
            && FakeSkyOcean.saves == 1, "× removes only SkyOcean's color, through SkyOcean, which saves");
        String foot = ctx.computeOnClient(mc -> screen(mc).footer());
        check(foot.equals("Removed SkyOcean's dye on Zephyr Chestplate"), "the footer says what was removed: " + foot);
        ctx.clickScreenButton("Undo");
        ctx.waitTicks(2);
        check(chest.getData().containsKey(FakeSkyOcean.COLOR) && FakeSkyOcean.saves == 2, "Undo puts SkyOcean's color back");

        // Move Here: Skyblocker's glint-off becomes the sword's own look.
        b = buttons(ctx, SWORD, "Skyblocker", Kind.GLINT);
        click(ctx, b[2], b[3]);
        ctx.waitTicks(2);
        check("off".equals(ctx.computeOnClient(mc -> Looks.byUuid(SWORD) == null ? null : Looks.byUuid(SWORD).glint()))
            && !FakeSkyblocker.get().general.customGlint.containsKey(SWORD), "Move Here makes the glint a SkyCosmetics look");

        // A mod that refuses (its update throws) keeps its change; the footer says where to change it.
        FakeSkyblocker.failNext = true;
        b = buttons(ctx, SWORD, "Skyblocker", Kind.MODEL);
        click(ctx, b[0], b[1]);
        ctx.waitTicks(2);
        check(FakeSkyblocker.get().general.customItemModel.containsKey(SWORD), "a failing removal changes nothing");
        foot = ctx.computeOnClient(mc -> screen(mc).footer());
        check(foot.equals("Skyblocker kept it: change it in its own settings"), "and says so: " + foot);

        // The search finds a mod, a kind of change or a value.
        StudioLayoutTest.focusSearch(ctx);
        ctx.getInput().typeChars("skyocean");
        ctx.waitTicks(2);
        rows = rows(ctx);
        check(rows.stream().filter(r -> r.startsWith("  ")).allMatch(r -> r.contains("SkyOcean"))
            && rows.stream().noneMatch(r -> r.startsWith("Zephyr Helmet")), "searching 'skyocean' keeps SkyOcean's items: " + rows);
        for (int i = 0; i < 8; i++) ctx.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
        ctx.waitTicks(2);

        window(ctx, 1920, 1080, 4);
        ctx.takeScreenshot("skycosmetics-65b-other-mods-guiscale-4");
        window(ctx, 1920, 1080, 3);
        ctx.takeScreenshot("skycosmetics-65b-other-mods-guiscale-3");
        window(ctx, 854, 480, 2);
        ctx.takeScreenshot("skycosmetics-65c-other-mods-small-window");
        open(ctx, mc -> mc.player.getInventory().getItem(1));
        ctx.takeScreenshot("skycosmetics-65d-chips-small-window");
        window(ctx, 854, 720, 2); // narrow but tall: the chips sit under My Items
        check(ctx.computeOnClient(mc -> screen(mc).chips()).size() >= 2, "a narrow window still shows the chips");
        ctx.takeScreenshot("skycosmetics-65e-chips-narrow-window");
        window(ctx, 1920, 1080, 2);
    }

    /** Mods whose classes this build doesn't know: their files are read, and the changes listed read-only. */
    private static void readOnly(ClientGameTestContext ctx) {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("skycosmetics-test");
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("skyblocker.json"), """
                {"general": {
                  "customDyeColors": {"%1$s": 3394679},
                  "customItemNames": {"%2$s": {"text": "Gale", "color": "aqua", "italic": false}},
                  "customArmorTrims": {"%1$s": {"material": "minecraft:gold", "pattern": "minecraft:sentry"}},
                  "customGlint": {"%3$s": true},
                  "customItemModel": {"%2$s": "minecraft:stone_sword"},
                  "customAnimatedDyes": {"%4$s": {"keyframes": [{"color": 16711680, "time": 0.0}, {"color": 255, "time": 1.0}],
                    "cycleBack": true, "delay": 0.0, "duration": 2.0}}
                }}""".formatted(BOOTS, SWORD, HELM, UNSEEN), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("custom_items.json"), """
                {"@skyocean:version": 0, "@skyocean:data": [
                  {"key": {"uuid": "%1$s", "type": "UUID"}, "data": {"skyocean:color": {"type": "static", "colorCode": 8930559}}},
                  {"key": {"key": "item:zephyr_sword", "type": "ID"}, "data": {"skyocean:glint": true}}
                ]}""".formatted(CHEST), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ctx.runOnClient(mc -> OtherLooks.use(List.of(
            OtherLooks.skyblocker("io.github.terabold.skycosmetics.test.fake.NoSuchManager", dir.resolve("skyblocker.json")),
            OtherLooks.skyOcean("io.github.terabold.skycosmetics.test.fake.NoSuchItems", dir.resolve("custom_items.json")))));
        open(ctx, mc -> mc.player.getInventory().getItem(1));
        if (!ctx.tryClickScreenButton("Other Mods")) ctx.clickScreenButton("Mods");
        ctx.waitTicks(3);
        List<String> rows = rows(ctx);
        System.out.println("[SkyCosmeticsTest] Other Mods from files: " + rows);
        for (String want : new String[]{"  Skyblocker dye: #33CC77 (read-only)", "  Skyblocker name: Gale (read-only)",
            "  Skyblocker trim: Sentry · Gold (read-only)", "  Skyblocker glint: On (read-only)",
            "  Skyblocker model: Stone Sword (read-only)", "  Skyblocker dye: Animated · 2 colors (read-only)",
            "  SkyOcean dye: #8844FF (read-only)", "  SkyOcean glint: On (read-only)"}) {
            check(rows.contains(want), "read from the file: '" + want + "': " + rows);
        }
        ctx.takeScreenshot("skycosmetics-66-other-mods-read-only");
        Map<String, int[]> chips = ctx.computeOnClient(mc -> screen(mc).chips());
        check(chips.containsKey("Dyed by Skyblocker"), "a read-only change still shows its chip: " + chips.keySet());
        int[] dye = chips.get("Dyed by Skyblocker");
        click(ctx, dye[0], dye[1]);
        ctx.waitTicks(2);
        check(rows(ctx).contains("  Skyblocker dye: #33CC77 (read-only)"), "a click on a read-only chip changes nothing");
        try {
            Files.deleteIfExists(dir.resolve("skyblocker.json"));
            Files.deleteIfExists(dir.resolve("custom_items.json"));
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ctx.runOnClient(mc -> OtherLooks.use(List.of()));
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> screen(mc).chips()).isEmpty(), "without other mods there are no chips");
        System.out.println("[SkyCosmeticsTest] Other Mods: listing, chips, removal through the mod, Undo, Move Here, read-only: OK");
    }

    private static Identifier id(String ns, String path) {
        return Identifier.fromNamespaceAndPath(ns, path);
    }

    private static List<String> rows(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> screen(mc).otherRows());
    }

    private static int[] buttons(ClientGameTestContext ctx, String item, String mod, Kind kind) {
        int[] b = ctx.computeOnClient(mc -> screen(mc).otherButtons(item, mod, kind));
        check(b != null, "Other Mods lists " + mod + "'s " + kind + " on " + item);
        return b;
    }

    private static StudioScreen screen(Minecraft mc) {
        return (StudioScreen) mc.screen;
    }
}
