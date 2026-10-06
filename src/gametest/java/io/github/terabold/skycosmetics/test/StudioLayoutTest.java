package io.github.terabold.skycosmetics.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.data.Catalog;
import io.github.terabold.skycosmetics.data.Repo;
import io.github.terabold.skycosmetics.data.SkinEntry;
import io.github.terabold.skycosmetics.gui.ColorPopup;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.pet.PetTracker;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * The 1.3 studio layout: the preview column (full tooltip, built once per
 * look), My Items order with the held item, the pick following its item, the
 * Settings button and its key tip, the footer, Skins filters that follow the
 * picked item, the custom dye pop-up (both modes, live apply, every way of
 * closing it), and every tab at the common window sizes and GUI scales.
 */
final class StudioLayoutTest {
    private StudioLayoutTest() {}

    /** The Skins filters' lists: helmet skins, power orb skins, nothing else in either. */
    static void checkData() {
        Catalog c = Repo.get();
        check(!c.orbSkinTab.isEmpty() && c.orbSkinTab.stream().allMatch(e -> e.id.contains("_FLUX")),
            "Orb lists power orb skins only: " + c.orbSkinTab.size());
        check(c.orbSkinTab.contains(c.skin("BEACH_BALL_FLUX")), "Beach Ball is an orb skin");
        check(c.helmetSkinTab.stream().noneMatch(e -> e.id.contains("_FLUX")), "Helmet has no orb skins");
        check(c.helmetSkinTab.contains(c.skin("NECRON_DIAMOND_KNIGHT")) && c.helmetSkinTab.contains(c.skin("WITHER_GOGGLES_CELESTIAL")),
            "Helmet has helmet skins");
        check(c.helmetSkinTab.contains(c.skin("NECRON_DIAMOND_KNIGHT_AURORA")), "Helmet keeps the color variants");
        for (String other : new String[]{"AWAKENED_EYE_BACKPACK", "APOTHEJERRY_PERSONALITY", "6_ANNIVERSARY_BARN_SKIN"}) {
            SkinEntry e = c.skin(other);
            check(e == null || !c.helmetSkinTab.contains(e) && c.headTab.contains(e), other + " is only under All");
        }
        check(Catalog.filter(c.helmetSkinTab, "animated").stream().allMatch(e -> e.animated() || e.missingFrames),
            "searching 'animated' finds animated skins");
        System.out.println("[SkyCosmeticsTest] skins: helmet=" + c.helmetSkinTab.size() + " orb=" + c.orbSkinTab.size()
            + " pet=" + c.petSkinTab.size() + " all=" + c.headTab.size());
    }

    /** The studio K opened on the worn helmet: Settings button, Helmet skins, and the item list order. */
    static void checkOpenedOnHelmet(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            StudioScreen s = (StudioScreen) mc.screen;
            AbstractWidget settings = widget(mc, "Settings");
            check(settings != null && settings.getHeight() >= 20 && settings.getX() < s.width / 2,
                "a 20 px 'Settings' button in the left column");
            check(widget(mc, "⚙") == null, "the gear icon is gone");
            check(s.skinFilterLabel().equals("Helmet"), "a helmet opens the Helmet skins: " + s.skinFilterLabel());
            mc.getToastManager().clear();
            List<String> rows = s.listedRows();
            System.out.println("[SkyCosmeticsTest] studio from K lists " + rows);
            check(rows.indexOf("# Hovered") == 0 && rows.get(1).equals("focus"), "the hovered item comes first");
            check(!rows.contains("worn:HEAD"), "the picked helmet is not listed again under Wearing");
            int held = rows.indexOf("# Held");
            check(held > rows.indexOf("# Wearing") && rows.get(held + 1).equals("inv:0")
                && rows.get(held + 2).equals("# Inventory") && Collections.frequency(rows, "inv:0") == 1,
                "Held lists the main hand once, between Wearing/Pet and Inventory");
        });
        // The search's (i): a short, organised tooltip.
        int[] info = ctx.computeOnClient(mc -> {
            AbstractWidget w = widget(mc, "i");
            return new int[]{w.getX() + 4, w.getY() + 4};
        });
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(info[0] * scale, info[1] * scale);
        ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-05b-studio-items-help");
        ctx.getInput().setCursorPos(0, 0);
        ctx.clickScreenButton("Settings");
        ctx.waitForScreen(SettingsScreen.class);
        ctx.runOnClient(mc -> mc.screen.onClose());
        ctx.waitForScreen(StudioScreen.class);
        ctx.waitTicks(2);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("item replace entity @a hotbar.6 with minecraft:player_head[minecraft:custom_data="
            + "{id:\"RADIANT_POWER_ORB\",uuid:\"t-orb6\"},minecraft:lore=[\"\",\"UNCOMMON DEPLOYABLE\"]]");
        StringBuilder lore = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            lore.append("{text:\"Lore line ").append(i).append(": a long Hypixel item description\",color:\"gray\",italic:false},");
        }
        lore.append("{text:\"LEGENDARY SWORD\",color:\"gold\",bold:true,italic:false}");
        sp.getServer().runCommand("item replace entity @a hotbar.7 with minecraft:golden_sword[minecraft:custom_data="
            + "{id:\"LONG_LORE_SWORD\",uuid:\"t-long\"},minecraft:lore=[" + lore + "]]");
        // A pet of another type than PetWorldTest's, so its chat summon still has one Golden Dragon to match.
        sp.getServer().runCommand("item replace entity @a hotbar.8 with minecraft:player_head[minecraft:custom_data="
            + "{id:\"PET\",petInfo:'{\"type\":\"BLUE_WHALE\",\"tier\":\"LEGENDARY\",\"uuid\":\"t-whale\"}'}]");
        sp.getServer().runCommand("item replace entity @a inventory.0 with minecraft:player_head[minecraft:custom_data="
            + "{id:\"PET\",petInfo:'{\"type\":\"BAT\",\"tier\":\"RARE\",\"uuid\":\"t-bat\"}'}]");
        sp.getServer().runCommand("item replace entity @a inventory.1 with minecraft:leather_chestplate[minecraft:custom_data="
            + "{id:\"TYPICAL_CHESTPLATE\",uuid:\"t-typical\"},minecraft:custom_name={text:\"Ancient Necron's Chestplate\","
            + "color:\"light_purple\",italic:false},minecraft:lore=[" + typicalLore() + "]]");
        ctx.waitTicks(3);

        itemOrder(ctx);
        followsItem(ctx);
        gridCost(ctx);
        previewFits(ctx);
        window(ctx, 1280, 720, 2); // 640 x 360 scaled: the preview column at GUI scale 2
        preview(ctx);
        skinFilters(ctx);
        footer(ctx);
        keyTip(ctx);
        dyePopup(ctx);
        windowSizes(ctx);

        window(ctx, 854, 480, 0);
        ctx.setScreen(() -> null);
        sp.getServer().runCommand("item replace entity @a hotbar.6 with minecraft:air");
        sp.getServer().runCommand("item replace entity @a hotbar.7 with minecraft:air");
        sp.getServer().runCommand("item replace entity @a hotbar.8 with minecraft:air");
        sp.getServer().runCommand("item replace entity @a inventory.0 with minecraft:air");
        sp.getServer().runCommand("item replace entity @a inventory.1 with minecraft:air");
        ctx.waitTicks(2);
    }

    /** About what Hypixel shows on a dungeon chestplate: stats, enchants, a set bonus, the rarity line. */
    private static String typicalLore() {
        String[][] lines = {{"Gear Score: 662", "dark_gray"}, {"Health: +240", "gray"}, {"Defense: +250", "gray"},
            {"Strength: +40", "gray"}, {"Crit Damage: +30%", "gray"}, {"Intelligence: +30", "gray"}, {"", "gray"},
            {"Growth V, Protection V, Rejuvenate V", "blue"}, {"", "gray"}, {"Full Set Bonus: Witherborn", "gold"},
            {"Spawns a wither minion every", "gray"}, {"30 seconds up to a maximum", "gray"},
            {"1 wither. Your withers will", "gray"}, {"travel to and explode on", "gray"}, {"nearby enemies.", "gray"},
            {"", "gray"}, {"Reduces the damage you take", "gray"}, {"from withers by 10%.", "gray"}, {"", "gray"},
            {"This item can be reforged!", "dark_gray"}, {"LEGENDARY DUNGEON CHESTPLATE", "gold"}};
        StringBuilder b = new StringBuilder();
        for (String[] l : lines) {
            if (!b.isEmpty()) b.append(',');
            b.append("{text:\"").append(l[0]).append("\",color:\"").append(l[1]).append("\",italic:false}");
        }
        return b.toString();
    }

    /**
     * At 1920x1080, GUI scale 3 (a common setup): smaller crisp text and a 3x icon, so a typical SkyBlock tooltip
     * fits without scrolling, with headroom between it and the model. A pet floats beside the model; nothing
     * else does.
     */
    private static void previewFits(ClientGameTestContext ctx) {
        window(ctx, 1920, 1080, 3);
        open(ctx, mc -> mc.player.getInventory().getItem(10));
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            StudioScreen s = (StudioScreen) mc.screen;
            int[] lines = s.preview().linesShown();
            System.out.println("[SkyCosmeticsTest] typical tooltip at GUI 3: " + lines[1] + " of " + lines[0] + " lines, icon "
                + s.preview().iconSize() + " px, text x" + s.preview().textScale() + ", model " + java.util.Arrays.toString(s.modelBox()));
            check(Math.abs(s.preview().textScale() - 2 / 3f) < 1e-4 && s.preview().iconSize() == 48,
                "GUI 3: text one step smaller, icon 3x");
            check(lines[0] >= 20 && lines[1] == lines[0], "a typical tooltip fits without scrolling: " + lines[1] + "/" + lines[0]);
            check(s.modelBox() != null && s.modelBox()[2] >= 24, "the model keeps a usable size: " + java.util.Arrays.toString(s.modelBox()));
            check(s.petBox() == null, "no pet beside the model for a chestplate");
        });
        ctx.takeScreenshot("skycosmetics-21b-preview-typical-guiscale-3");

        open(ctx, mc -> mc.player.getInventory().getItem(1)); // the Golden Dragon, with a pet skin for every one
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            StudioScreen s = (StudioScreen) mc.screen;
            int[] pet = s.petBox(), model = s.modelBox();
            check(pet != null && model != null && pet[2] >= 32 && pet[1] >= model[0] && pet[1] + pet[2] <= model[1],
                "a picked pet floats beside the model, inside its box: " + java.util.Arrays.toString(pet));
        });
        ctx.takeScreenshot("skycosmetics-21c-preview-pet-guiscale-3");
        window(ctx, 1280, 720, 2);
    }

    /** Wearing, Pet, Held, Inventory; an item is listed once, under the first section it belongs to. */
    private static void itemOrder(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            PetTracker.debugSetCurrent(mc.player.getInventory().getItem(8));
            mc.player.getInventory().setSelectedSlot(0);
        });
        open(ctx, mc -> ItemStack.EMPTY);
        ctx.runOnClient(mc -> {
            List<String> rows = ((StudioScreen) mc.screen).listedRows();
            System.out.println("[SkyCosmeticsTest] My Items: " + rows);
            List<String> headers = rows.stream().filter(r -> r.startsWith("# ")).toList();
            check(headers.subList(0, 4).equals(List.of("# Wearing", "# Pet", "# Held", "# Inventory")),
                "sections in order Wearing, Pet, Held, Inventory: " + headers);
            int held = rows.indexOf("# Held");
            check(rows.get(held + 1).equals("inv:0") && rows.get(held + 2).equals("# Inventory"), "Held is one row");
            check(Collections.frequency(rows, "inv:0") == 1, "the held item is not listed twice");
            check(rows.contains("pet") && !rows.contains("inv:8"), "the summoned pet is not listed again in Inventory");
        });
        // An item hovered in a menu that is also in your hand: listed once, under Hovered, so no Held section.
        ctx.setScreen(() -> new StudioScreen(new InventoryScreen(Minecraft.getInstance().player),
            Minecraft.getInstance().player.getInventory().getItem(0).copy()));
        ctx.waitForScreen(StudioScreen.class);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            List<String> rows = ((StudioScreen) mc.screen).listedRows();
            check(rows.getFirst().equals("# Hovered") && !rows.contains("# Held") && !rows.contains("inv:0"),
                "the hovered held item is listed once, first: " + rows);
        });
        // Opened without a menu (the open key with no screen, /skycosmetics): no Hovered section; the item is
        // picked where it is listed.
        open(ctx, mc -> mc.player.getInventory().getItem(0).copy());
        ctx.runOnClient(mc -> {
            StudioScreen s = (StudioScreen) mc.screen;
            List<String> rows = s.listedRows();
            check(!rows.contains("# Hovered") && "inv:0".equals(s.pickedRole()),
                "without a menu the held item is picked under Held: " + s.pickedRole() + " " + rows);
            PetTracker.debugSetCurrent(ItemStack.EMPTY);
        });
    }

    /**
     * The pick follows the item, not its row: when Autopet swaps the pet, a name still being typed goes to
     * the pet it was typed for, and the studio keeps editing that pet where it is listed now.
     */
    private static void followsItem(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> PetTracker.debugSetCurrent(mc.player.getInventory().getItem(8)));
        open(ctx, mc -> ItemStack.EMPTY);
        ctx.runOnClient(mc -> ((StudioScreen) mc.screen).pick("pet"));
        if (!ctx.tryClickScreenButton("Name & Glint")) ctx.clickScreenButton("Name");
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            ((StudioScreen) mc.screen).nameBox().setValue("&bBig Whale"); // saved once typing pauses
            PetTracker.debugSetCurrent(mc.player.getInventory().getItem(9)); // Autopet: the Bat
        });
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {
            StudioScreen s = (StudioScreen) mc.screen;
            Looks.Look whale = Looks.byUuid("t-whale"), bat = Looks.byUuid("t-bat");
            check(whale != null && "&bBig Whale".equals(whale.name()), "the typed name went to the whale: " + whale);
            check(bat == null || bat.name() == null, "the bat summoned meanwhile got nothing: " + bat);
            check("inv:8".equals(s.pickedRole()) && s.nameBox().getValue().equals("&bBig Whale"),
                "the pick follows the whale to its inventory row: " + s.pickedRole());
            Looks.put(false, "t-whale", null);
            PetTracker.debugSetCurrent(ItemStack.EMPTY);
        });
    }

    /**
     * What the skins grid costs at 1920x1080, GUI scale 3, on the Helmet skins (most of them animated): heads drawn
     * and texture checks per frame, and how many textures it asks for while it sits still, then while it scrolls.
     */
    private static void gridCost(ClientGameTestContext ctx) {
        window(ctx, 1920, 1080, 3);
        open(ctx, mc -> mc.player.getItemBySlot(EquipmentSlot.HEAD));
        ctx.waitTicks(1);
        int[] first = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).gridCost());
        ctx.waitTicks(100);
        int[] still = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).gridCost());
        int[] at = ctx.computeOnClient(mc -> new int[]{mc.screen.width / 2, mc.screen.height / 2});
        ctx.getInput().setCursorPos(at[0] * 3.0, at[1] * 3.0);
        for (int i = 0; i < 20; i++) {
            ctx.getInput().scroll(-1);
            ctx.waitTicks(1);
        }
        int[] scrolled = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).gridCost());
        ctx.waitTicks(100);
        int[] after = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).gridCost());
        System.out.println("[SkyCosmeticsTest] grid cost: " + still[0] + " heads and " + still[1]
            + " texture checks per frame; textures asked: " + first[2] + " at once, " + still[2] + " after 5 s still, "
            + scrolled[2] + " after scrolling 20 rows in 1 s, " + after[2] + " 5 s later");
        // 3 animation frames per tick at most (and the first frames of the cards in view): 754 before 1.3.1.
        check(still[2] - first[2] <= 400, "the grid asks for textures on a budget: " + (still[2] - first[2]) + " in 5 s");
        ctx.getInput().setCursorPos(0, 0);

        // Card names at 1920x1080, GUI scale 3: wrapped on words, hardly ever cut.
        int[] cut = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).labelsCut());
        System.out.println("[SkyCosmeticsTest] card names cut at GUI 3: " + cut[0] + " of " + cut[1]);
        check(cut[1] > 40 && cut[0] * 20 <= cut[1], "at most 1 in 20 card names is cut: " + cut[0] + "/" + cut[1]);
        ctx.takeScreenshot("skycosmetics-23b-skins-names-guiscale-3");
        window(ctx, 1920, 1080, 2);
        ctx.waitTicks(20);
        int[] cut2 = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).labelsCut());
        System.out.println("[SkyCosmeticsTest] card names cut at 1080p GUI 2: " + cut2[0] + " of " + cut2[1]);
        ctx.takeScreenshot("skycosmetics-23c-skins-names-1080p-guiscale-2");
    }

    /** The full tooltip with the styled custom name, built once per look, updated live, scrolling when long. */
    private static void preview(ClientGameTestContext ctx) {
        Looks.Look chest = ctx.computeOnClient(mc -> Looks.byUuid("t-chest"));
        open(ctx, mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST));
        ctx.waitTicks(5);
        int builds = ctx.computeOnClient(mc -> {
            Component title = ((StudioScreen) mc.screen).preview().title();
            check(title != null && title.getString().equals("Golden Tunic"), "preview shows the custom name: " + title);
            check(hasColour(title, ChatFormatting.GOLD), "the preview name keeps its colors");
            return ((StudioScreen) mc.screen).preview().builds();
        });
        ctx.waitTicks(10);
        check(ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).preview().builds()) == builds,
            "the preview tooltip is not rebuilt every frame");
        ctx.takeScreenshot("skycosmetics-20-preview-guiscale-2");

        ctx.runOnClient(mc -> Looks.put(false, "t-chest", chest.withName("&bBlue &oTunic")));
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            Component title = ((StudioScreen) mc.screen).preview().title();
            check(title.getString().equals("Blue Tunic") && hasColour(title, ChatFormatting.AQUA),
                "the preview follows a name change: " + title.getString());
            check(((StudioScreen) mc.screen).preview().builds() == builds + 1, "one rebuild per look change");
            Looks.put(false, "t-chest", chest);
        });

        window(ctx, 1920, 1080, 2);
        ctx.waitTicks(3);
        int[] size = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(),
            mc.screen.width, mc.screen.height});
        System.out.println("[SkyCosmeticsTest] wide window " + size[0] + "x" + size[1] + " = " + size[2] + "x" + size[3] + " scaled");
        ctx.takeScreenshot("skycosmetics-21-preview-wide");

        window(ctx, 1280, 720, 2);
        open(ctx, mc -> mc.player.getInventory().getItem(7));
        ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-22-preview-long-lore");
        int[] at = ctx.computeOnClient(mc -> new int[]{mc.screen.width - 60, 90});
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(at[0] * scale, at[1] * scale);
        ctx.waitTicks(1);
        ctx.getInput().scroll(-1);
        ctx.waitTicks(2);
        int scrolled = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).preview().scroll());
        check(scrolled == 3, "the wheel scrolls a long tooltip by three lines: " + scrolled);
        ctx.takeScreenshot("skycosmetics-22b-preview-long-lore-scrolled");
    }

    /** The default filter follows the item; every filter, for a helmet and for a pet. */
    private static void skinFilters(ClientGameTestContext ctx) {
        open(ctx, mc -> mc.player.getInventory().getItem(6));
        check(filter(ctx).equals("Orb"), "a power orb opens the Orb skins: " + filter(ctx));
        open(ctx, mc -> mc.player.getInventory().getItem(0).copy());
        check(filter(ctx).equals("All"), "a sword opens every head: " + filter(ctx));

        String[][] items = {{"helmet", "Helmet"}, {"pet", "Pet"}};
        for (String[] item : items) {
            boolean pet = item[0].equals("pet");
            open(ctx, mc -> pet ? mc.player.getInventory().getItem(1) : mc.player.getItemBySlot(EquipmentSlot.HEAD));
            check(filter(ctx).equals(item[1]), "a " + item[0] + " opens the " + item[1] + " skins: " + filter(ctx));
            for (String f : new String[]{"Helmet", "Pet", "Orb", "All", "Paste"}) {
                if (!filter(ctx).equals(f)) ctx.clickScreenButton(f);
                ctx.waitTicks(f.equals("Paste") ? 3 : 25);
                check(filter(ctx).equals(f), "filter " + f);
                ctx.takeScreenshot("skycosmetics-23-skins-" + item[0] + "-" + f.toLowerCase());
            }
        }
    }

    /** One footer line: a message replaces the count for a few seconds, then the count comes back. */
    private static void footer(ClientGameTestContext ctx) {
        open(ctx, mc -> mc.player.getItemBySlot(EquipmentSlot.HEAD));
        ctx.waitTicks(2);
        String count = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).footer());
        check(count.endsWith(" skins"), "the footer counts the skins: " + count);
        ctx.clickScreenButton("Paste");
        ctx.waitTicks(2);
        ctx.getInput().typeChars("not a texture");
        ctx.clickScreenButton("Apply");
        ctx.waitTicks(2);
        String message = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).footer());
        check(message.equals("Not a skin texture: paste a Value, URL or hash"), "the message takes the footer: " + message);
        ctx.clickScreenButton("Helmet");
        ctx.waitTicks(90);
        String back = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).footer());
        check(back.endsWith(" skins"), "after a few seconds the count is back: " + back);
    }

    /** While the open key is unbound, Settings is outlined with a tip until it is clicked. */
    private static void keyTip(ClientGameTestContext ctx) {
        InputConstants.Key bound = ctx.computeOnClient(mc -> {
            KeyMapping open = KeyMapping.get("key.skycosmetics.open");
            InputConstants.Key k = KeyMappingHelper.getBoundKeyOf(open);
            open.setKey(InputConstants.UNKNOWN);
            KeyMapping.resetMapping();
            Settings.keyTipShown = false;
            return k;
        });
        open(ctx, mc -> mc.player.getItemBySlot(EquipmentSlot.HEAD));
        check(ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).keyTipShown()), "an unbound key outlines Settings");
        ctx.takeScreenshot("skycosmetics-20b-settings-key-tip");
        ctx.clickScreenButton("Settings");
        ctx.waitForScreen(SettingsScreen.class);
        ctx.runOnClient(mc -> mc.screen.onClose());
        ctx.waitForScreen(StudioScreen.class);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            check(Settings.keyTipShown && !((StudioScreen) mc.screen).keyTipShown(), "clicking Settings ends the tip");
            KeyMapping.get("key.skycosmetics.open").setKey(bound);
            KeyMapping.resetMapping();
        });
    }

    /** "Custom Dye…" opens one pop-up with both modes; it applies live and closes every way it should. */
    private static void dyePopup(ClientGameTestContext ctx) {
        Looks.Look chest = ctx.computeOnClient(mc -> Looks.byUuid("t-chest"));
        open(ctx, mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST));
        check(ctx.computeOnClient(mc -> widget(mc, "Custom color") == null && widget(mc, "Custom animated") == null
            && widget(mc, "Hypixel dyes") == null), "no dye mode buttons any more");
        ctx.clickScreenButton("Custom Dye…");
        ctx.waitTicks(3);
        ColorPopup p = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).popup());
        check(p != null && p.isAnimated(), "the pop-up opens on Animated for the inherited Aurora dye");
        ctx.takeScreenshot("skycosmetics-24-custom-dye-animated");

        int[] single = p.toggleAt(false);
        click(ctx, single[0], single[1]);
        ctx.waitTicks(5);
        check(!p.isAnimated(), "the switch goes to Single color");
        String applied = ctx.computeOnClient(mc -> Looks.byUuid("t-chest").dye());
        check(applied != null && applied.startsWith("#") && applied.equals(p.value()), "switching applies live: " + applied);
        click(ctx, p.picker().x() + 30, p.picker().y() + 12);
        ctx.waitTicks(5);
        String picked = ctx.computeOnClient(mc -> Looks.byUuid("t-chest").dye());
        check(!picked.equals(applied) && picked.equals(p.value()), "a click in the color square applies live: " + picked);
        ctx.takeScreenshot("skycosmetics-24b-custom-dye-single");

        // Resizing keeps it open and inside the window.
        window(ctx, 854, 480, 0);
        ctx.runOnClient(mc -> {
            ColorPopup q = ((StudioScreen) mc.screen).popup();
            check(q == p && q.x() >= 0 && q.y() >= 0 && q.x() + q.width() <= mc.screen.width
                && q.y() + q.height() <= mc.screen.height, "the pop-up survives a resize, inside the window");
        });
        ctx.takeScreenshot("skycosmetics-24c-custom-dye-small-window");
        window(ctx, 1280, 720, 2);

        // A click outside closes it and does nothing else: the tab button under the click is not pressed.
        int[] saved = ctx.computeOnClient(mc -> {
            AbstractWidget w = widget(mc, "Saved");
            return new int[]{w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2};
        });
        click(ctx, saved[0], saved[1]);
        ctx.runOnClient(mc -> {
            check(((StudioScreen) mc.screen).popup() == null, "a click outside closes the pop-up");
            check(widget(mc, "Saved").active, "the click outside did not switch to Saved");
        });

        // Esc closes the pop-up, not the studio.
        ctx.clickScreenButton("Custom Dye…");
        ctx.waitTicks(2);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> check(mc.screen instanceof StudioScreen s && s.popup() == null, "Esc closes only the pop-up"));

        // Its x button closes it too.
        ctx.clickScreenButton("Custom Dye…");
        ctx.waitTicks(2);
        ColorPopup x = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).popup());
        click(ctx, x.x() + x.width() - 12, x.y() + 12);
        check(ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).popup() == null), "x closes the pop-up");

        // Changing the tab drops it: no hidden editor keeps taking input.
        ctx.clickScreenButton("Custom Dye…");
        ctx.waitTicks(2);
        ctx.clickScreenButton("Saved");
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).popup() == null), "a tab change drops the pop-up");
        ctx.runOnClient(mc -> Looks.put(false, "t-chest", chest));
    }

    /**
     * Every tab at 1920x1080, 1366x768 and 854x480 and every GUI scale the window allows: every widget inside
     * the window, none overlapping, and room for at least five items in the list. In short narrow windows the
     * summary moves into the icon's tooltip, and its old place takes no clicks.
     */
    private static void windowSizes(ClientGameTestContext ctx) {
        Looks.Look chest = ctx.computeOnClient(mc -> Looks.byUuid("t-chest"));
        open(ctx, mc -> mc.player.getItemBySlot(EquipmentSlot.CHEST));
        int[] clearName = ctx.computeOnClient(mc -> {
            StudioScreen s = (StudioScreen) mc.screen;
            check(s.summaryShown(), "the preview column shows the summary");
            return s.summaryClearAt(2);
        });
        int[][] windows = {{1920, 1080}, {1366, 768}, {854, 480}};
        for (int[] w : windows) {
            for (int scale = 1; scale <= 4; scale++) {
                window(ctx, w[0], w[1], scale);
                if (ctx.computeOnClient(mc -> mc.getWindow().getGuiScale()) != scale) continue; // too big for this window
                String size = w[0] + "x" + w[1] + "-guiscale-" + scale;
                for (String tab : new String[]{"Skins", "Name", "Saved", "Dyes"}) {
                    if (!ctx.tryClickScreenButton(tab) && tab.equals("Name")) ctx.tryClickScreenButton("Name & Glint");
                    ctx.waitTicks(2);
                    ctx.runOnClient(mc -> layout(mc, size + " " + tab));
                }
                ctx.runOnClient(mc -> {
                    StudioScreen s = (StudioScreen) mc.screen;
                    check(s.listRoom() >= 100, size + ": room for five items in the list: " + s.listRoom());
                });
                ctx.takeScreenshot("skycosmetics-25-studio-" + size);
            }
        }

        // 427 x 240: the summary is in the icon's tooltip, and a click where it used to be clears nothing.
        window(ctx, 854, 480, 2);
        ctx.runOnClient(mc -> {
            StudioScreen s = (StudioScreen) mc.screen;
            check(!s.summaryShown(), "a short narrow window shows no summary under the list");
            s.mouseClicked(new MouseButtonEvent(clearName[0], clearName[1], new MouseButtonInfo(0, 0)), false);
            check("&6Golden &lTunic".equals(Looks.byUuid("t-chest").name()), "the old summary place clears nothing");
        });
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        int[] icon = ctx.computeOnClient(mc -> new int[]{8 + 4 + 10, mc.screen.height - 8 - 44 + 10});
        ctx.getInput().setCursorPos(icon[0] * scale, icon[1] * scale);
        ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-26-compact-summary-tooltip");
        ctx.getInput().setCursorPos(0, 0);
        ctx.runOnClient(mc -> Looks.put(false, "t-chest", chest));
    }

    /** Every visible widget inside the window, and no two overlapping. */
    static void layout(Minecraft mc, String size) {
        List<AbstractWidget> ws = new ArrayList<>();
        for (var c : mc.screen.children()) if (c instanceof AbstractWidget w && w.visible) ws.add(w);
        for (AbstractWidget a : ws) {
            check(a.getX() >= 0 && a.getY() >= 0 && a.getRight() <= mc.screen.width && a.getBottom() <= mc.screen.height,
                size + ": '" + a.getMessage().getString() + "' is inside the window");
            for (AbstractWidget b : ws) {
                boolean overlap = a != b && a.getX() < b.getRight() && b.getX() < a.getRight()
                    && a.getY() < b.getBottom() && b.getY() < a.getBottom();
                check(!overlap, size + ": '" + a.getMessage().getString() + "' overlaps '" + b.getMessage().getString() + "'");
            }
        }
    }

    // ------------------------------------------------------------ helpers ---

    /** A studio picked on {@code focus}, with the mouse parked in a corner and no toasts over it. */
    static void open(ClientGameTestContext ctx, Function<Minecraft, ItemStack> focus) {
        ctx.setScreen(() -> new StudioScreen(null, focus.apply(Minecraft.getInstance())));
        ctx.waitForScreen(StudioScreen.class);
        ctx.getInput().setCursorPos(0, 0);
        ctx.runOnClient(mc -> mc.getToastManager().clear());
        ctx.waitTicks(3);
    }

    private static String filter(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).skinFilterLabel());
    }

    static void window(ClientGameTestContext ctx, int w, int h, int scale) {
        ctx.getInput().resizeWindow(w, h);
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(scale);
            mc.resizeGui();
        });
        ctx.waitTicks(3);
    }

    static void click(ClientGameTestContext ctx, int x, int y) {
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos(x * scale, y * scale);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
    }

    static AbstractWidget widget(Minecraft mc, String label) {
        for (var c : mc.screen.children()) {
            if (c instanceof AbstractWidget w && w.getMessage().getString().equals(label)) return w;
        }
        return null;
    }

    static boolean hasColour(Component c, ChatFormatting f) {
        TextColor want = TextColor.fromLegacyFormat(f);
        return c.toFlatList().stream().anyMatch(part -> want.equals(part.getStyle().getColor()));
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] failed: " + what);
    }
}
