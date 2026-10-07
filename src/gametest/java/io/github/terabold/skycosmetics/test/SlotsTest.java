package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.hub.Hub;
import io.github.terabold.skycosmetics.slots.HeadSize;
import io.github.terabold.skycosmetics.slots.ItemFacts;
import io.github.terabold.skycosmetics.slots.Mask;
import io.github.terabold.skycosmetics.slots.Rarity;
import io.github.terabold.skycosmetics.slots.RarityBackgrounds;
import io.github.terabold.skycosmetics.slots.RaritySwatches;
import io.github.terabold.skycosmetics.slots.StylePicker;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rarity Backgrounds and Head Size: rarity lines Hypixel writes (and look-alikes that must not count), backgrounds in
 * a menu, the inventory and the hotbar in every shape and fill, the Wardrobe exclusions, the cost of a full menu
 * with the feature on and off, head sizes measured from the item states vanilla builds, and both settings sections.
 * Every setting is put back at the end.
 */
public class SlotsTest implements FabricClientGameTest {
    private static final String TAG = "[SkyCosmeticsTest] ";
    private static final String AUCTIONS = "Auctions Browser", WARDROBE = "(1/3) Armor Sets";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            check(Hub.find(RarityBackgrounds.SECTION) != null && Hub.find("headSize") != null, "both sections are in the settings");
            checkRarityLines();
        });
        // From the title screen (Mod Menu) items can't be made yet: both sections still open, with every row.
        for (String section : List.of(RarityBackgrounds.SECTION, "headSize")) {
            ctx.setScreen(() -> new SettingsScreen(null, section));
            ctx.waitTicks(3);
            ctx.runOnClient(mc -> check(mc.screen instanceof SettingsScreen s && s.rowIds().contains(section + ".preview"),
                section + " opens before any world, preview included"));
            ctx.takeScreenshot("skycosmetics-69-settings-" + section + "-title-screen");
        }
        ctx.setScreen(() -> null);
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            sp.getServer().runCommand("time set noon");
            sp.getServer().runCommand("weather clear");
            ctx.runOnClient(mc -> checkFacts()); // item stacks need a world: components are bound then
            int[] size = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
            ctx.getInput().resizeWindow(1920, 1080);
            try {
                fillInventory(sp, ctx);
                menuBackgrounds(ctx);
                exclusions(ctx);
                hotbar(ctx);
                performance(ctx);
                headSize(ctx);
                settings(ctx);
            } finally {
                ctx.setScreen(() -> null);
                ctx.runOnClient(mc -> {
                    RarityBackgrounds.reset();
                    HeadSize.reset();
                    Settings.save();
                });
                ctx.getInput().resizeWindow(size[0], size[1]);
                setScale(ctx, 0);
            }
        }
    }

    // ------------------------------------------------------------ rarity data ---

    private static void checkRarityLines() {
        Map<String, Rarity> rarity = new LinkedHashMap<>();
        rarity.put("COMMON", Rarity.COMMON);
        rarity.put("§a§lUNCOMMON REFORGE STONE", Rarity.UNCOMMON);
        rarity.put("§9§lRARE BOW", Rarity.RARE);
        rarity.put("EPIC PET ITEM", Rarity.EPIC);
        rarity.put("§6§lLEGENDARY DUNGEON HELMET", Rarity.LEGENDARY);
        rarity.put("§d§lMYTHIC ACCESSORY", Rarity.MYTHIC);
        rarity.put("§b§lDIVINE", Rarity.DIVINE);
        rarity.put("SUPREME DUNGEON SWORD", Rarity.DIVINE);
        rarity.put("§c§lSPECIAL", Rarity.SPECIAL);
        rarity.put("§c§lVERY SPECIAL HATCESSORY", Rarity.VERY_SPECIAL);
        rarity.put("§4§lADMIN", Rarity.ADMIN);
        rarity.put("§6§lSHINY LEGENDARY DUNGEON SWORD", Rarity.LEGENDARY);
        rarity.put("§d§l§ka§r §d§lMYTHIC DUNGEON CHESTPLATE §d§l§ka", Rarity.MYTHIC);
        rarity.put("a SHINY MYTHIC a", Rarity.MYTHIC);
        rarity.forEach((line, want) -> {
            ItemFacts.Line got = ItemFacts.rarityLine(List.of(Component.literal(line)));
            check(got != null && got.rarity() == want, "'" + line + "' is " + want + ", got " + got);
            check(got.recombobulated() == (line.startsWith("§d§l§ka") || line.startsWith("a ")), "recombobulated: " + line);
        });
        for (String line : List.of("Legendary", "Rarity: COMMON", "Click to view!", "COMMONER", "", "a", "LEGENDARY dungeon",
            "§7Seller: §bSomeone", "VERY", "RARELY SEEN")) {
            check(ItemFacts.rarityLine(List.of(Component.literal(line))) == null, "'" + line + "' is no rarity line");
        }
        // The auction house adds lines under the item's own lore: the rarity line is still found.
        ItemFacts.Line auction = ItemFacts.rarityLine(List.of(Component.literal("§7Ability: Wither Impact"),
            Component.literal("§6§lLEGENDARY DUNGEON SWORD"), Component.empty(), Component.literal("§7Seller: §bSomeone"),
            Component.literal("§7Buy it now: §65,000,000 coins")));
        check(auction != null && auction.rarity() == Rarity.LEGENDARY, "auction lines under the rarity line");
        System.out.println(TAG + "rarity lines checked: " + rarity.size() + " read, 10 rejected");
    }

    /** Pets by tier (one up with a Tier Boost), recombobulated by data, and the per-stack cache. */
    private static void checkFacts() {
        ItemStack boosted = stack(Items.PLAYER_HEAD, "PET", "{\"type\":\"BEE\",\"tier\":\"EPIC\",\"heldItem\":\"PET_ITEM_TIER_BOOST\"}", "§5§lEPIC");
        check(ItemFacts.of(boosted).rarity() == Rarity.LEGENDARY && ItemFacts.of(boosted).pet(), "Tier Boost: an Epic pet is Legendary");
        ItemStack mythic = stack(Items.PLAYER_HEAD, "PET", "{\"type\":\"ENDER_DRAGON\",\"tier\":\"MYTHIC\",\"heldItem\":\"PET_ITEM_TIER_BOOST\"}");
        check(ItemFacts.of(mythic).rarity() == Rarity.MYTHIC, "a pet never goes above Mythic, even without a rarity line");
        ItemStack broken = stack(Items.PLAYER_HEAD, "PET", "not json", "§6§lLEGENDARY");
        check(ItemFacts.of(broken).rarity() == Rarity.LEGENDARY, "bad petInfo falls back to the rarity line");

        ItemStack upgraded = stack(Items.DIAMOND_SWORD, "HYPERION", null, "§6§lLEGENDARY DUNGEON SWORD");
        CompoundTag t = upgraded.get(DataComponents.CUSTOM_DATA).copyTag();
        t.putInt("rarity_upgrades", 1);
        upgraded.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        check(ItemFacts.of(upgraded).recombobulated() && ItemFacts.of(upgraded).skyblock(), "rarity_upgrades marks recombobulated");
        ItemStack button = stack(Items.GRAY_STAINED_GLASS_PANE, null, null, "§9§lRARE");
        check(ItemFacts.of(button).rarity() == Rarity.RARE && !ItemFacts.of(button).skyblock(), "a menu button is no SkyBlock item");

        long before = ItemFacts.reads();
        ItemStack s = stack(Items.BOW, "BOW", null, "§5§lEPIC BOW");
        ItemFacts first = ItemFacts.of(s);
        for (int i = 0; i < 100; i++) check(ItemFacts.of(s) == first, "the facts are kept on the stack");
        check(ItemFacts.reads() - before == 1, "read once for 101 lookups: " + (ItemFacts.reads() - before));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("§d§lMYTHIC BOW"))));
        check(ItemFacts.of(s).rarity() == Rarity.MYTHIC && ItemFacts.reads() - before == 2, "new lore is read again");
        System.out.println(TAG + "item facts checked");
    }

    // -------------------------------------------------------------- the world ---

    private static void fillInventory(TestSingleplayerContext sp, ClientGameTestContext ctx) {
        String[] commands = {
            "hotbar.0 with minecraft:diamond_sword[minecraft:custom_data={id:\"T_SWORD\"},minecraft:lore=[\"LEGENDARY DUNGEON SWORD\"]]",
            "hotbar.1 with minecraft:player_head[minecraft:custom_data={id:\"PET\",petInfo:'{\"type\":\"BEE\",\"tier\":\"EPIC\",\"heldItem\":\"PET_ITEM_TIER_BOOST\"}'},minecraft:lore=[\"EPIC\"]]",
            "hotbar.2 with minecraft:bow[minecraft:custom_data={id:\"T_BOW\"},minecraft:lore=[\"EPIC BOW\"]]",
            "hotbar.3 with minecraft:iron_pickaxe[minecraft:custom_data={id:\"T_PICK\"},minecraft:lore=[\"UNCOMMON PICKAXE\"]]",
            "hotbar.4 with minecraft:emerald[minecraft:custom_data={id:\"T_EMERALD\"},minecraft:lore=[\"COMMON\"]]",
            "hotbar.5 with minecraft:nether_star[minecraft:custom_data={id:\"T_STAR\"},minecraft:lore=[\"a MYTHIC a\"]]",
            "hotbar.6 with minecraft:player_head[minecraft:custom_data={id:\"T_RELIC\"},minecraft:lore=[\"LEGENDARY ACCESSORY\"]]",
            "hotbar.7 with minecraft:apple",
            "hotbar.8 with minecraft:player_head[minecraft:lore=[\"RARE\"]]",
            "armor.head with minecraft:player_head[minecraft:custom_data={id:\"T_HELMET\"},minecraft:lore=[\"LEGENDARY DUNGEON HELMET\"]]",
            "armor.chest with minecraft:leather_chestplate[minecraft:custom_data={id:\"T_CHEST\"},minecraft:lore=[\"a LEGENDARY DUNGEON CHESTPLATE a\"]]",
            "armor.legs with minecraft:leather_leggings[minecraft:custom_data={id:\"T_LEGS\"},minecraft:lore=[\"RARE DUNGEON LEGGINGS\"]]",
            "armor.feet with minecraft:leather_boots[minecraft:custom_data={id:\"T_BOOTS\"},minecraft:lore=[\"EPIC DUNGEON BOOTS\"]]",
            "weapon.offhand with minecraft:totem_of_undying[minecraft:custom_data={id:\"T_TOTEM\"},minecraft:lore=[\"DIVINE\"]]",
            "inventory.0 with minecraft:diamond[minecraft:custom_data={id:\"T_DIAMOND\"},minecraft:lore=[\"RARE\"]]",
            "inventory.1 with minecraft:heart_of_the_sea[minecraft:custom_data={id:\"T_HEART\"},minecraft:lore=[\"DIVINE\"]]",
            "inventory.2 with minecraft:cake[minecraft:custom_data={id:\"T_CAKE\"},minecraft:lore=[\"SPECIAL\"]]",
            "inventory.3 with minecraft:firework_rocket[minecraft:custom_data={id:\"T_ROCKET\"},minecraft:lore=[\"VERY SPECIAL\"]]",
            "inventory.4 with minecraft:golden_apple[minecraft:custom_data={id:\"T_APPLE\"},minecraft:lore=[\"SHINY LEGENDARY\"]]",
            "inventory.5 with minecraft:player_head[minecraft:custom_data={id:\"PET\",petInfo:'{\"type\":\"ENDER_DRAGON\",\"tier\":\"MYTHIC\"}'}]",
            "inventory.6 with minecraft:command_block[minecraft:custom_data={id:\"T_ADMIN\"},minecraft:lore=[\"ADMIN\"]]",
            "inventory.7 with minecraft:skeleton_skull[minecraft:custom_data={id:\"T_SKULL\"},minecraft:lore=[\"EPIC\"]]",
            "inventory.8 with minecraft:golden_hoe[minecraft:custom_data={id:\"T_HOE\"},minecraft:lore=[\"UNCOMMON\"]]",
        };
        for (String c : commands) sp.getServer().runCommand("item replace entity @a " + c);
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> {
            mc.player.getInventory().setSelectedSlot(0);
            check(ItemFacts.of(mc.player.getInventory().getItem(0)).rarity() == Rarity.LEGENDARY, "the sword arrived with its lore");
            check(ItemFacts.of(mc.player.getInventory().getItem(1)).rarity() == Rarity.LEGENDARY, "the boosted pet arrived");
        });
    }

    /** A 6-row menu titled like the auction house: every rarity, pets, a recombobulated item, buttons, heads. */
    private static Screen menu(Minecraft mc, String title) {
        Item[] items = {Items.IRON_SWORD, Items.EMERALD, Items.DIAMOND_PICKAXE, Items.BOW, Items.GOLDEN_CHESTPLATE,
            Items.NETHER_STAR, Items.HEART_OF_THE_SEA, Items.CAKE, Items.FIREWORK_ROCKET, Items.ENCHANTED_BOOK, Items.COMMAND_BLOCK};
        String[] lines = {"§f§lCOMMON SWORD", "§a§lUNCOMMON", "§9§lRARE PICKAXE", "§5§lEPIC BOW", "§6§lLEGENDARY CHESTPLATE",
            "§d§lMYTHIC", "§b§lDIVINE", "§c§lSPECIAL", "§c§lVERY SPECIAL", "§4§lULTIMATE", "§4§lADMIN"};
        SimpleContainer box = new SimpleContainer(54);
        for (int i = 0; i < items.length; i++) box.setItem(i, stack(items[i], "T_ITEM_" + i, null, lines[i]));
        box.setItem(11, stack(Items.DIAMOND_CHESTPLATE, "T_RECOMB", null, "§d§l§ka§r §d§lMYTHIC DUNGEON CHESTPLATE §d§l§ka"));
        box.setItem(12, stack(Items.PLAYER_HEAD, "PET", "{\"type\":\"BEE\",\"tier\":\"EPIC\",\"heldItem\":\"PET_ITEM_TIER_BOOST\"}", "§5§lEPIC"));
        box.setItem(13, stack(Items.PLAYER_HEAD, "PET", "{\"type\":\"ENDER_DRAGON\",\"tier\":\"MYTHIC\"}", "§d§lMYTHIC"));
        box.setItem(14, stack(Items.DIAMOND_SWORD, "T_AUCTION", null, "§6§lLEGENDARY DUNGEON SWORD", "",
            "§7Seller: §bSomeone", "§7Buy it now: §65,000,000 coins"));
        box.setItem(15, stack(Items.GRAY_STAINED_GLASS_PANE, null, null, "§9§lRARE"));
        box.setItem(16, stack(Items.PLAYER_HEAD, "T_RELIC", null, "§6§lLEGENDARY ACCESSORY"));
        box.setItem(17, new ItemStack(Items.APPLE));
        box.setItem(18, stack(Items.GOLDEN_APPLE, "T_SHINY", null, "§6§lSHINY LEGENDARY"));
        box.setItem(19, stack(Items.GOLD_INGOT, "T_SUPREME", null, "§b§lSUPREME"));
        box.setItem(20, stack(Items.PLAYER_HEAD, null, null, "§aClick to open"));
        box.setItem(21, stack(Items.SKELETON_SKULL, "T_SKULL", null, "§5§lEPIC"));
        for (int i = 22; i < 54; i++) {
            int r = i % items.length;
            box.setItem(i, i % 5 == 0 ? stack(Items.PLAYER_HEAD, "T_HEAD_" + i, null, lines[r])
                : stack(items[r], "T_FILL_" + i, null, lines[r]));
        }
        return new ContainerScreen(ChestMenu.sixRows(100, mc.player.getInventory(), box), mc.player.getInventory(),
            Component.literal(title));
    }

    /** The menu and the inventory in every shape and fill, at GUI scale 2 and 3. */
    private static void menuBackgrounds(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            RarityBackgrounds.reset();
            RarityBackgrounds.set(true, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 55, false);
            RarityBackgrounds.where(true, true, true, true, RarityBackgrounds.OwnMenus.ALL, true);
        });
        ctx.setScreen(() -> menu(Minecraft.getInstance(), AUCTIONS));
        for (int scale : new int[]{2, 3}) {
            setScale(ctx, scale);
            ctx.takeScreenshot("skycosmetics-60-rarity-menu-rounded-guiscale-" + scale);
        }
        Object[][] styles = {
            {Mask.Shape.SQUARE, Mask.Fill.SOLID, 45, false}, {Mask.Shape.INSET, Mask.Fill.VERTICAL, 80, false},
            {Mask.Shape.CIRCLE, Mask.Fill.RADIAL, 85, true}, {Mask.Shape.DIAMOND, Mask.Fill.DIAGONAL, 75, false},
            {Mask.Shape.FRAME, Mask.Fill.SOLID, 100, false}, {Mask.Shape.CORNER, Mask.Fill.HORIZONTAL, 90, false},
            {Mask.Shape.GLOW, Mask.Fill.SOLID, 90, false}, {Mask.Shape.ROUNDED, Mask.Fill.BEVELED, 85, false},
            {Mask.Shape.SQUARE, Mask.Fill.EDGES, 90, true},
        };
        setScale(ctx, 3);
        for (Object[] st : styles) {
            ctx.runOnClient(mc -> RarityBackgrounds.set(true, (Mask.Shape) st[0], (Mask.Fill) st[1], (int) st[2], (boolean) st[3]));
            ctx.waitTicks(2);
            ctx.takeScreenshot("skycosmetics-61-rarity-menu-" + ((Mask.Shape) st[0]).name().toLowerCase()
                + "-" + ((Mask.Fill) st[1]).name().toLowerCase() + "-guiscale-3");
        }
        ctx.runOnClient(mc -> RarityBackgrounds.set(true, Mask.Shape.CIRCLE, Mask.Fill.RADIAL, 85, true));
        setScale(ctx, 2);
        ctx.takeScreenshot("skycosmetics-61-rarity-menu-circle-radial-guiscale-2");

        // Counted from one frame's extraction: every item with a rarity line gets exactly one background.
        int expected = ctx.computeOnClient(mc -> {
            int n = 0;
            for (Slot s : ((AbstractContainerScreen<?>) mc.screen).getMenu().slots) {
                if (s.hasItem() && ItemFacts.of(s.getItem()).rarity() != null) n++;
            }
            return n;
        });
        check(drawnInFrame(ctx) == expected, "one background per item with a rarity: " + expected);
        ctx.runOnClient(mc -> RarityBackgrounds.which(false, false, true));
        int fewer = ctx.computeOnClient(mc -> {
            int n = 0;
            for (Slot s : ((AbstractContainerScreen<?>) mc.screen).getMenu().slots) {
                ItemFacts f = s.hasItem() ? ItemFacts.of(s.getItem()) : null;
                if (f != null && f.rarity() != null && f.rarity() != Rarity.COMMON && !f.pet() && f.skyblock()) n++;
            }
            return n;
        });
        check(fewer < expected && drawnInFrame(ctx) == fewer, "no commons, pets or buttons: " + fewer + " of " + expected);
        ctx.takeScreenshot("skycosmetics-62-rarity-menu-skyblock-only-no-common-no-pets");
        ctx.runOnClient(mc -> RarityBackgrounds.which(true, true, false));

        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        ctx.runOnClient(mc -> RarityBackgrounds.set(true, Mask.Shape.ROUNDED, Mask.Fill.VERTICAL, 70, false));
        for (int scale : new int[]{2, 3}) {
            setScale(ctx, scale);
            ctx.takeScreenshot("skycosmetics-63-rarity-inventory-guiscale-" + scale);
        }
        ctx.runOnClient(mc -> RarityBackgrounds.where(true, true, false, true, RarityBackgrounds.OwnMenus.ALL, true));
        int noArmor = drawnInFrame(ctx);
        ctx.runOnClient(mc -> RarityBackgrounds.where(true, true, true, true, RarityBackgrounds.OwnMenus.ALL, true));
        int withArmor = drawnInFrame(ctx);
        check(withArmor == noArmor + 5, "the four armor slots and the off-hand have their own switch: " + noArmor + " -> " + withArmor);
        ctx.getInput().resizeWindow(854, 480);
        setScale(ctx, 2);
        ctx.takeScreenshot("skycosmetics-63-rarity-inventory-854x480-guiscale-2");
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.setScreen(() -> null);
        System.out.println(TAG + "rarity backgrounds in menus checked (" + expected + " in the test menu)");
    }

    /** Wardrobe, Equipment and Loadouts: all items, no heads, or nothing; other menus never follow that rule. */
    private static void exclusions(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> RarityBackgrounds.set(true, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 60, false));
        setScale(ctx, 3);
        Map<RarityBackgrounds.OwnMenus, Integer> counts = new LinkedHashMap<>();
        for (RarityBackgrounds.OwnMenus rule : RarityBackgrounds.OwnMenus.values()) {
            ctx.runOnClient(mc -> RarityBackgrounds.where(false, true, false, false, rule, false));
            ctx.setScreen(() -> wardrobe(Minecraft.getInstance()));
            counts.put(rule, drawnInFrame(ctx));
            ctx.runOnClient(mc -> RarityBackgrounds.where(true, true, true, false, rule, false));
            ctx.waitTicks(2);
            ctx.takeScreenshot("skycosmetics-64-rarity-wardrobe-" + rule.name().toLowerCase().replace('_', '-'));
        }
        System.out.println(TAG + "wardrobe backgrounds by rule: " + counts);
        check(counts.get(RarityBackgrounds.OwnMenus.ALL) == 36, "Wardrobe, all items: 9 helmets + 27 armor pieces");
        check(counts.get(RarityBackgrounds.OwnMenus.NO_HEADS) == 27, "Wardrobe, no heads: the 27 armor pieces");
        check(counts.get(RarityBackgrounds.OwnMenus.NONE) == 0, "Wardrobe, nothing");
        ctx.runOnClient(mc -> RarityBackgrounds.where(false, true, false, false, RarityBackgrounds.OwnMenus.NONE, false));
        ctx.setScreen(() -> menu(Minecraft.getInstance(), AUCTIONS));
        check(drawnInFrame(ctx) > 40, "the rule leaves other menus alone");
        ctx.runOnClient(mc -> RarityBackgrounds.where(true, false, true, true, RarityBackgrounds.OwnMenus.ALL, false));
        int own = ctx.computeOnClient(mc -> {
            int n = 0;
            for (Slot s : ((AbstractContainerScreen<?>) mc.screen).getMenu().slots) {
                if (s.container == mc.player.getInventory() && s.hasItem() && ItemFacts.of(s.getItem()).rarity() != null) n++;
            }
            return n;
        });
        check(drawnInFrame(ctx) == own, "Menus off: only your own rows in a menu (" + own + ")");
        ctx.setScreen(() -> null);
        ctx.runOnClient(mc -> RarityBackgrounds.where(true, true, true, true, RarityBackgrounds.OwnMenus.ALL, false));
        System.out.println(TAG + "menu exclusions checked");
    }

    private static Screen wardrobe(Minecraft mc) {
        SimpleContainer box = new SimpleContainer(54);
        Item[] armor = {Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
        String[] tiers = {"§6§lLEGENDARY DUNGEON ", "§d§lMYTHIC DUNGEON ", "§5§lEPIC "};
        for (int c = 0; c < 9; c++) {
            box.setItem(c, stack(Items.PLAYER_HEAD, "T_WARDROBE_HELMET", null, tiers[c % 3] + "HELMET"));
            for (int r = 0; r < 3; r++) box.setItem(9 * (r + 1) + c, stack(armor[r], "T_WARDROBE_" + r, null, tiers[c % 3] + "ARMOR"));
            box.setItem(36 + c, stack(Items.LIME_DYE, null, null, "§7Click to equip"));
        }
        return new ContainerScreen(ChestMenu.sixRows(101, mc.player.getInventory(), box), mc.player.getInventory(),
            Component.literal(WARDROBE));
    }

    /** The HUD hotbar draws backgrounds only with its switch on. */
    private static void hotbar(ClientGameTestContext ctx) {
        ctx.setScreen(() -> null);
        ctx.runOnClient(mc -> RarityBackgrounds.set(true, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 60, false));
        for (int scale : new int[]{2, 3}) {
            setScale(ctx, scale);
            ctx.takeScreenshot("skycosmetics-65-rarity-hotbar-guiscale-" + scale);
        }
        long before = ctx.computeOnClient(mc -> RarityBackgrounds.drawn());
        ctx.waitTicks(5);
        check(ctx.computeOnClient(mc -> RarityBackgrounds.drawn()) > before, "the hotbar draws backgrounds");
        ctx.runOnClient(mc -> RarityBackgrounds.where(true, true, true, false, RarityBackgrounds.OwnMenus.ALL, false));
        ctx.waitTicks(2);
        before = ctx.computeOnClient(mc -> RarityBackgrounds.drawn());
        ctx.waitTicks(5);
        check(ctx.computeOnClient(mc -> RarityBackgrounds.drawn()) == before, "Hotbar off: none");
        ctx.runOnClient(mc -> RarityBackgrounds.where(true, true, true, true, RarityBackgrounds.OwnMenus.ALL, false));
        System.out.println(TAG + "hotbar backgrounds checked");
    }

    /**
     * A full 6-row menu, extracted many times with the features off and on: the difference is what they cost per
     * frame. Nothing is read again while the menu stays the same.
     */
    private static void performance(ClientGameTestContext ctx) {
        setScale(ctx, 3);
        ctx.setScreen(() -> menu(Minecraft.getInstance(), AUCTIONS));
        ctx.runOnClient(mc -> {
            Screen s = mc.screen;
            RarityBackgrounds.set(true, Mask.Shape.CIRCLE, Mask.Fill.RADIAL, 80, true);
            HeadSize.setPercent(100);
            time(mc, s, 200); // warm up
            long[] off = new long[3], on = new long[3], heads = new long[3];
            long reads = 0;
            for (int round = 0; round < 3; round++) {
                RarityBackgrounds.set(false, Mask.Shape.CIRCLE, Mask.Fill.RADIAL, 80, true);
                HeadSize.setPercent(100);
                off[round] = time(mc, s, 300);
                RarityBackgrounds.set(true, Mask.Shape.CIRCLE, Mask.Fill.RADIAL, 80, true);
                long r0 = ItemFacts.reads();
                on[round] = time(mc, s, 300);
                reads += ItemFacts.reads() - r0;
                HeadSize.setPercent(125);
                heads[round] = time(mc, s, 300);
            }
            long offUs = median(off) / 1000, onUs = median(on) / 1000, headsUs = median(heads) / 1000;
            System.out.println(TAG + "full 6-row menu + inventory, median CPU per frame: off " + offUs + " us, rarity backgrounds "
                + onUs + " us (+" + (onUs - offUs) + "), + head size 125% " + headsUs + " us (+" + (headsUs - onUs) + ")");
            check(reads == 0, "drawing the same menu reads no item again: " + reads);
            check(onUs - offUs < 1000, "rarity backgrounds cost under 1 ms per frame: +" + (onUs - offUs) + " us");
            check(headsUs - onUs < 1000, "head size costs under 1 ms per frame: +" + (headsUs - onUs) + " us");
            RarityBackgrounds.set(true, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 55, false);
            HeadSize.setPercent(100);
        });
        ctx.setScreen(() -> null);
    }

    /** Median nanoseconds of one extraction of the screen, over {@code frames} runs. */
    private static long time(Minecraft mc, Screen s, int frames) {
        GuiRenderState state = new GuiRenderState();
        long[] t = new long[frames];
        for (int i = 0; i < frames; i++) {
            state.reset();
            GuiGraphicsExtractor g = new GuiGraphicsExtractor(mc, state, -1, -1);
            long t0 = System.nanoTime();
            s.extractRenderState(g, -1, -1, 0);
            t[i] = System.nanoTime() - t0;
        }
        return median(t);
    }

    private static long median(long[] v) {
        long[] c = v.clone();
        Arrays.sort(c);
        return c[c.length / 2];
    }

    // -------------------------------------------------------------- head size ---

    /** Head sizes measured from the item states vanilla builds for one frame, then screenshots. */
    private static void headSize(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            RarityBackgrounds.set(false, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 55, false);
            HeadSize.reset();
        });
        setScale(ctx, 3);
        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        // Menu slot ids: 5 helmet (SkyBlock head), 37 pet head, 36 sword, 44 head without a SkyBlock id.
        Map<Integer, ItemBox> base = items(ctx);
        check(!base.get(5).keyed && !base.get(37).keyed && base.get(5).oversized == null, "100%: heads are untouched");
        for (int p : new int[]{75, 150}) {
            ctx.runOnClient(mc -> HeadSize.setPercent(p));
            Map<Integer, ItemBox> now = items(ctx);
            for (int slot : new int[]{5, 37, 44}) {
                double ratio = now.get(slot).w / base.get(slot).w;
                check(Math.abs(ratio - p / 100.0) < 0.03, p + "%: head in slot " + slot + " is " + ratio + "x");
                check(now.get(slot).keyed, p + "%: the size is in the head's atlas key");
                check(Math.abs(now.get(slot).cy - base.get(slot).cy) < 0.05 * base.get(slot).w, p + "%: the head grows in place");
            }
            check(Math.abs(now.get(36).w - base.get(36).w) < 1e-6 && !now.get(36).keyed, p + "%: the sword is untouched");
            check(p > 100 == (now.get(37).oversized != null), p + "%: drawn on its own canvas only when bigger");
            ctx.takeScreenshot("skycosmetics-66-head-size-inventory-" + p + "-guiscale-3");
        }
        ctx.runOnClient(mc -> HeadSize.where(true, true, true, false, true));
        Map<Integer, ItemBox> only = items(ctx);
        check(only.get(37).keyed && !only.get(44).keyed, "SkyBlock items only: a head without a SkyBlock id keeps its size");
        ctx.runOnClient(mc -> HeadSize.where(false, true, true, false, false));
        check(!items(ctx).get(37).keyed, "Inventory off: inventory heads keep their size");
        ctx.runOnClient(mc -> HeadSize.where(true, true, true, false, false));
        setScale(ctx, 2);
        ctx.takeScreenshot("skycosmetics-66-head-size-inventory-150-guiscale-2");

        // In a menu: the menu's heads follow Menus, your own rows follow Inventory.
        ctx.setScreen(() -> menu(Minecraft.getInstance(), AUCTIONS));
        setScale(ctx, 3);
        ctx.runOnClient(mc -> RarityBackgrounds.set(true, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 50, false));
        ctx.takeScreenshot("skycosmetics-67-head-size-menu-150-guiscale-3");
        ctx.runOnClient(mc -> HeadSize.where(true, false, true, false, false));
        Map<Integer, ItemBox> menuOff = items(ctx);
        // Slot 12 is a pet head in the menu; slot 82 is your hotbar's pet head (54 menu + 27 inventory + 1).
        check(!menuOff.get(12).keyed && menuOff.get(82).keyed, "Menus off: menu heads keep their size, yours grow");
        ctx.runOnClient(mc -> HeadSize.where(true, true, true, false, false));
        ctx.runOnClient(mc -> HeadSize.setPercent(50));
        ctx.takeScreenshot("skycosmetics-67-head-size-menu-50-guiscale-3");
        ctx.runOnClient(mc -> HeadSize.setPercent(150));
        ctx.setScreen(() -> null);
        ctx.waitTicks(2);
        ctx.takeScreenshot("skycosmetics-68-head-size-hotbar-150-guiscale-3");
        ctx.runOnClient(mc -> {
            HeadSize.reset();
            RarityBackgrounds.set(false, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 55, false);
        });
        System.out.println(TAG + "head size checked");
    }

    /** Size of one GUI item as vanilla built it: model box width and center, oversized canvas, our key. */
    private record ItemBox(double w, double cy, Object oversized, boolean keyed) {}

    /** Every slot's item as built in one frame, by menu slot id. */
    private static Map<Integer, ItemBox> items(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            AbstractContainerScreen<?> s = (AbstractContainerScreen<?>) mc.screen;
            GuiRenderState state = new GuiRenderState();
            s.extractRenderState(new GuiGraphicsExtractor(mc, state, -1, -1), -1, -1, 0);
            List<GuiItemRenderState> drawn = new ArrayList<>();
            state.forEachItem(drawn::add);
            Map<Integer, ItemBox> out = new LinkedHashMap<>();
            List<Slot> slots = s.getMenu().slots;
            for (int i = 0; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                if (!slot.hasItem()) continue;
                for (GuiItemRenderState d : drawn) {
                    if (d.x() != slot.x || d.y() != slot.y) continue;
                    AABB box = d.itemStackRenderState().getModelBoundingBox();
                    boolean keyed = false;
                    if (d.itemStackRenderState().getModelIdentity() instanceof Iterable<?> it) {
                        for (Object o : it) keyed |= HeadSize.isKey(o);
                    }
                    out.put(i, new ItemBox(box.getXsize(), (box.minY + box.maxY) / 2, d.oversizedItemBounds(), keyed));
                }
            }
            return out;
        });
    }

    // --------------------------------------------------------------- settings ---

    private static void settings(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            RarityBackgrounds.reset();
            RarityBackgrounds.set(true, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 55, false);
        });
        ctx.setScreen(() -> new SettingsScreen(null, RarityBackgrounds.SECTION));
        for (int scale : new int[]{2, 3}) {
            setScale(ctx, scale);
            ctx.takeScreenshot("skycosmetics-69-settings-rarity-1920x1080-guiscale-" + scale);
        }
        setScale(ctx, 2);
        // A real click on the Circle preview picks it.
        clickCenter(ctx, () -> picker(Minecraft.getInstance().screen, Mask.Shape.class).centerOf(Mask.Shape.CIRCLE),
            GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(ctx.computeOnClient(mc -> RarityBackgrounds.style().shape() == Mask.Shape.CIRCLE), "clicking Circle picks it");
        clickCenter(ctx, () -> picker(Minecraft.getInstance().screen, Mask.Fill.class).centerOf(Mask.Fill.RADIAL),
            GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(ctx.computeOnClient(mc -> RarityBackgrounds.style().fill() == Mask.Fill.RADIAL), "clicking Radial Glow picks it");
        ctx.takeScreenshot("skycosmetics-69-settings-rarity-circle-radial");

        // The swatches: a click opens the color pop-up; right-click puts a changed color back.
        scrollTo(ctx, RaritySwatches.class);
        ctx.takeScreenshot("skycosmetics-69-settings-rarity-colors");
        clickCenter(ctx, () -> swatches(Minecraft.getInstance().screen).centerOf(Rarity.LEGENDARY), GLFW.GLFW_MOUSE_BUTTON_LEFT);
        check(ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).popup() != null), "a swatch opens the color pop-up");
        ctx.takeScreenshot("skycosmetics-69-settings-rarity-color-popup");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen s && s.popup() == null), "Esc closes the pop-up only");
        // Editing a color while Soft shows starts Custom from Soft: a real click in the pop-up's color square.
        ctx.runOnClient(mc -> RarityBackgrounds.colors(RarityBackgrounds.Colors.SOFT));
        clickCenter(ctx, () -> swatches(Minecraft.getInstance().screen).centerOf(Rarity.LEGENDARY), GLFW.GLFW_MOUSE_BUTTON_LEFT);
        clickCenter(ctx, () -> {
            var picker = ((SettingsScreen) Minecraft.getInstance().screen).popup().picker();
            return new int[]{picker.x() + 4, picker.y() + 4};
        }, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(2);
        int softEpic = 0xC78BF0;
        ctx.runOnClient(mc -> {
            check(RarityBackgrounds.colorSet() == RarityBackgrounds.Colors.CUSTOM, "an edited color switches to Custom");
            check(RarityBackgrounds.color(Rarity.EPIC) == softEpic, "Custom starts from the set shown (Soft)");
            check(RarityBackgrounds.color(Rarity.LEGENDARY) != 0xFFC56E, "the edited color applied: "
                + Integer.toHexString(RarityBackgrounds.color(Rarity.LEGENDARY)));
        });
        ctx.takeScreenshot("skycosmetics-69-settings-rarity-colors-custom");
        clickCenter(ctx, () -> swatches(Minecraft.getInstance().screen).centerOf(Rarity.EPIC), GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        ctx.runOnClient(mc -> {
            check(mc.screen instanceof SettingsScreen s && s.popup() == null, "right-click opens nothing");
            check(RarityBackgrounds.color(Rarity.EPIC) == Rarity.EPIC.hypixel, "right-click resets a color to Hypixel's");
        });
        ctx.setScreen(() -> new SettingsScreen(null, RarityBackgrounds.SECTION));
        ctx.getInput().resizeWindow(854, 480);
        setScale(ctx, 2);
        ctx.takeScreenshot("skycosmetics-69-settings-rarity-854x480-guiscale-2");
        scrollTo(ctx, null);
        ctx.takeScreenshot("skycosmetics-69-settings-rarity-854x480-scrolled");
        ctx.getInput().resizeWindow(1920, 1080);

        ctx.runOnClient(mc -> HeadSize.setPercent(150));
        ctx.setScreen(() -> new SettingsScreen(null, "headSize"));
        for (int scale : new int[]{2, 3}) {
            setScale(ctx, scale);
            ctx.takeScreenshot("skycosmetics-69-settings-head-size-guiscale-" + scale);
        }
        ctx.runOnClient(mc -> HeadSize.setPercent(100));
        ctx.waitTicks(2);
        ctx.takeScreenshot("skycosmetics-69-settings-head-size-default");
        ctx.setScreen(() -> null);
        System.out.println(TAG + "slot settings checked");
    }

    @SuppressWarnings("unchecked")
    private static <T> StylePicker<T> picker(Screen s, Class<T> kind) {
        for (AbstractWidget w : widgets(s)) {
            if (w instanceof StylePicker<?> p && kind.isInstance(p.selected())) return (StylePicker<T>) p;
        }
        throw new AssertionError(TAG + "failed: no " + kind.getSimpleName() + " picker");
    }

    private static RaritySwatches swatches(Screen s) {
        for (AbstractWidget w : widgets(s)) if (w instanceof RaritySwatches r) return r;
        throw new AssertionError(TAG + "failed: no swatches");
    }

    /** Scrolls the settings list so a widget of this type (or, for null, the end) is in view. */
    private static void scrollTo(ClientGameTestContext ctx, Class<?> type) {
        ctx.runOnClient(mc -> {
            AbstractScrollArea area = null;
            for (GuiEventListener l : mc.screen.children()) if (l instanceof AbstractScrollArea a) area = a;
            check(area != null, "the settings list scrolls");
            if (type == null) {
                area.setScrollAmount(area.maxScrollAmount());
                return;
            }
            for (AbstractWidget w : widgets(mc.screen)) {
                if (!type.isInstance(w)) continue;
                area.setScrollAmount(Math.max(0, area.scrollAmount() + w.getY() - area.getY() - 40));
                return;
            }
            throw new AssertionError(TAG + "failed: no " + type.getSimpleName());
        });
        ctx.waitTicks(2);
    }

    /** A real mouse click at a point in GUI coordinates. */
    private static void clickCenter(ClientGameTestContext ctx, java.util.function.Supplier<int[]> at, int button) {
        double[] p = ctx.computeOnClient(mc -> {
            int[] c = at.get();
            double scale = mc.getWindow().getGuiScale();
            return new double[]{(c[0] + 0.5) * scale, (c[1] + 0.5) * scale};
        });
        ctx.getInput().setCursorPos(p[0], p[1]);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(button);
        ctx.waitTicks(2);
    }

    // ---------------------------------------------------------------- helpers ---

    /** How many backgrounds one extraction of the open screen draws. */
    private static int drawnInFrame(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            long before = RarityBackgrounds.drawn();
            mc.screen.extractRenderState(new GuiGraphicsExtractor(mc, new GuiRenderState(), -1, -1), -1, -1, 0);
            return (int) (RarityBackgrounds.drawn() - before);
        });
    }

    private static ItemStack stack(Item item, String id, String petInfo, String... lore) {
        ItemStack s = new ItemStack(item);
        if (id != null) {
            CompoundTag t = new CompoundTag();
            t.putString("id", id);
            if (petInfo != null) t.putString("petInfo", petInfo);
            s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        }
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) lines.add(Component.literal(l));
            s.set(DataComponents.LORE, new ItemLore(lines));
        }
        return s;
    }

    private static void setScale(ClientGameTestContext ctx, int scale) {
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(scale);
            mc.resizeGui();
        });
        ctx.waitTicks(3);
    }

    private static List<AbstractWidget> widgets(GuiEventListener root) {
        List<AbstractWidget> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(GuiEventListener l, List<AbstractWidget> out) {
        if (l instanceof AbstractWidget w && !(l instanceof AbstractScrollArea)) out.add(w);
        if (l instanceof ContainerEventHandler c) for (GuiEventListener child : c.children()) collect(child, out);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(TAG + "failed: " + what);
    }
}
