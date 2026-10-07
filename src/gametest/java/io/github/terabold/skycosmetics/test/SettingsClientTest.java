package io.github.terabold.skycosmetics.test;

import com.mojang.blaze3d.platform.InputConstants;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.Settings;
import io.github.terabold.skycosmetics.gui.SettingsScreen;
import io.github.terabold.skycosmetics.gui.hub.CardButton;
import io.github.terabold.skycosmetics.gui.hub.KeyBindButton;
import io.github.terabold.skycosmetics.gui.hub.ToggleSwitch;
import io.github.terabold.skycosmetics.items.OwnedItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Settings and data: the settings window at every GUI scale and in a small window (on screen, every control inside
 * the pane and only themed widgets, the list scrolls), the open-key box (bind, Esc, Backspace, conflicts, side mouse
 * button), custom names against getHoverName and Cosmetics.originalName, and which menu titles My Items
 * learns from. Labels are read from the lang file, so rewording one never breaks a check.
 */
public class SettingsClientTest implements FabricClientGameTest {
    private static final String OPEN = "key.skycosmetics.open";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        checkMenuTitles();
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            sp.getServer().runCommand("time set noon");
            layout(ctx);
            modMenu(ctx);
            keyButton(ctx);
            names(ctx, sp);
            menus(ctx);
        }
    }

    /** Hypixel's titles for menus of your own things, and look-alikes that must not count. */
    private static void checkMenuTitles() {
        Map<String, String> own = Map.ofEntries(
            Map.entry("(1/3) Armor Sets", "Wardrobe"), Map.entry("Armor Sets", "Wardrobe"),
            Map.entry("Wardrobe (2/3)", "Wardrobe"), Map.entry("(2/2) Equipment Sets", "Equipment wardrobe"),
            Map.entry("(1/2) Loadouts", "Loadouts"), Map.entry("Stats & Equipment", "Equipment"),
            Map.entry("Pets", "Pets"), Map.entry("(1/3) Pets", "Pets"), Map.entry("Pets (2/3)", "Pets"),
            Map.entry("Pets: \"drag\"", "Pets"), Map.entry("Ender Chest (1/9)", "Ender Chest"),
            Map.entry("Ender Chest ✦ (3/9)", "Ender Chest"), Map.entry("Ender Chest", "Ender Chest"),
            Map.entry("Jumbo Backpack (Slot #3)", "Backpack"), Map.entry("Greater Backpack ✦ (Slot #18)", "Backpack"),
            Map.entry("§6Jumbo Backpack§r (Slot #2)", "Backpack"), Map.entry("Personal Vault", "Personal Vault"));
        own.forEach((title, source) -> check(source.equals(OwnedItems.menuSource(title)),
            "'" + title + "' is " + source + ", got " + OwnedItems.menuSource(title)));
        for (String title : List.of("Auctions Browser", "Auction View", "BIN Auction View", "Auctions: \"necron\"",
            "Bazaar ➜ Mining", "Coop Bazaar Orders", "You                  Steve", "Offer Pets", "Pet Sitter",
            "Large Chest", "Chest", "Crafting", "SkyBlock Menu", "Steve's Profile", "Backpack Shop", "Rift Storage",
            "Ender Chest Shop", "Wardrobe Shop", "Manage Auctions", "Create BIN Auction", "Storage")) {
            check(OwnedItems.menuSource(title) == null, "'" + title + "' is not your own menu");
        }
        System.out.println("[SkyCosmeticsTest] My Items menu titles checked");
    }

    /** Every GUI scale and a small window: the window fits, controls sit inside the pane, the list scrolls. */
    private static void layout(ClientGameTestContext ctx) {
        ctx.setScreen(() -> new SettingsScreen(null));
        ctx.waitTicks(3);
        int[] size = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight()});
        ctx.getInput().resizeWindow(1920, 1080); // GUI scale 4 needs at least 1280x960
        for (int scale = 1; scale <= 4; scale++) {
            setScale(ctx, scale);
            ctx.takeScreenshot("skycosmetics-20-settings-1920x1080-guiscale-" + scale);
            ctx.runOnClient(SettingsClientTest::checkLayout);
        }
        ctx.getInput().resizeWindow(854, 480);
        for (int scale : new int[]{1, 2}) {
            setScale(ctx, scale);
            ctx.takeScreenshot("skycosmetics-20-settings-854x480-guiscale-" + scale);
            ctx.runOnClient(SettingsClientTest::checkLayout);
        }
        // The list no longer fits: the wheel scrolls it down to the last setting.
        check(ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).maxScroll()) > 0, "the list scrolls in a small window");
        int[] pane = ctx.computeOnClient(mc -> ((SettingsScreen) mc.screen).pane());
        double scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
        ctx.getInput().setCursorPos((pane[0] + pane[2] / 2.0) * scale, (pane[1] + pane[3] / 2.0) * scale);
        ctx.waitTicks(1);
        ctx.getInput().scroll(-50);
        ctx.waitTicks(10);
        check(ctx.computeOnClient(mc -> {
            SettingsScreen s = (SettingsScreen) mc.screen;
            return s.scrollAmount() == s.maxScroll();
        }), "the wheel scrolls to the end");
        ctx.takeScreenshot("skycosmetics-20-settings-854x480-scrolled");
        ctx.runOnClient(SettingsClientTest::checkLayout);
        ctx.getInput().resizeWindow(size[0], size[1]);
        setScale(ctx, 0);
        ctx.setScreen(() -> null);
    }

    /**
     * Mod Menu's Configure button and /skycosmetics open the same settings screen, on the section viewed last,
     * and Done returns to whatever opened it. Mod Menu is on the dev runtime unless the build ran with -PnoModMenu.
     */
    private static void modMenu(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            Screen made = SettingsScreen.create(null);
            check(made instanceof SettingsScreen s && s.parent() == null, "the factory opens the settings with no parent");
        });
        if (!FabricLoader.getInstance().isModLoaded("modmenu")) {
            System.out.println("[SkyCosmeticsTest] Mod Menu not loaded: entrypoint check skipped");
            return;
        }
        ctx.runOnClient(mc -> {
            Screen mods = ModMenuApi.createModsScreen(null);
            boolean found = false;
            for (var c : FabricLoader.getInstance().getEntrypointContainers("modmenu", ModMenuApi.class)) {
                if (!c.getProvider().getMetadata().getId().equals("skycosmetics")) continue;
                Screen s = c.getEntrypoint().getModConfigScreenFactory().create(mods);
                check(s instanceof SettingsScreen settings && settings.parent() == mods, "Configure opens the settings over Mod Menu");
                found = true;
            }
            check(found, "SkyCosmetics has a modmenu entrypoint");
            mc.setScreen(mods);
        });
        ctx.waitTicks(5);
        ctx.takeScreenshot("skycosmetics-24-mod-menu-list");
        ctx.setScreen(() -> null);
        System.out.println("[SkyCosmeticsTest] Mod Menu checks passed");
    }

    private static void checkLayout(Minecraft mc) {
        check(mc.screen instanceof SettingsScreen, "settings still open");
        SettingsScreen s = (SettingsScreen) mc.screen;
        int[] w = s.window(), pane = s.pane();
        check(w[0] >= 0 && w[1] >= 0 && w[0] + w[2] <= s.width && w[1] + w[3] <= s.height,
            "the window is on screen (" + s.width + "x" + s.height + ")");
        check(pane[0] > w[0] && pane[0] + pane[2] <= w[0] + w[2] && pane[1] + pane[3] <= w[1] + w[3], "the pane is in the window");
        int switches = 0;
        for (String id : s.rowIds()) {
            AbstractWidget c = s.widget(id);
            if (c == null) continue;
            check(c.getX() >= pane[0] && c.getX() + c.getWidth() <= pane[0] + pane[2], "control inside the pane: " + id);
            if (c instanceof ToggleSwitch) switches++;
            for (String line : s.rowText(id)) {
                check(mc.font.width(line) <= pane[2], "text wrapped to the pane: " + line);
            }
        }
        check(s.widget("openStudio") instanceof CardButton, "Open Studio is a card");
        check(s.widget("openKey") instanceof KeyBindButton, "the open key is a key box");
        check(switches >= 5, "the studio's settings are switches: " + switches);
        for (GuiEventListener l : s.children()) {
            check(l.getClass().getName().startsWith("io.github.terabold.skycosmetics."), "only themed widgets: " + l.getClass().getName());
        }
    }

    /** Click the key button, press a key: it binds and saves; Esc cancels; conflicts show; Backspace unbinds. */
    private static void keyButton(ClientGameTestContext ctx) {
        String before = ctx.computeOnClient(mc -> KeyMapping.get(OPEN).saveString());
        ctx.runOnClient(mc -> bindDirect(InputConstants.UNKNOWN));
        ctx.setScreen(() -> new SettingsScreen(null));
        ctx.waitTicks(3);

        clickButton(ctx, notBound(ctx));
        check(button(ctx, listening(ctx)) != null, "the button waits for a key");
        ctx.takeScreenshot("skycosmetics-21-settings-press-a-key");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_J);
        ctx.waitTicks(2);
        check(key(ctx).equals("key.keyboard.j") && button(ctx, "J") != null, "J is the open key: " + key(ctx));
        check(ctx.computeOnClient(mc -> {
            try {
                return Files.readString(mc.gameDirectory.toPath().resolve("options.txt")).contains("key_" + OPEN + ":key.keyboard.j");
            } catch (Exception e) {
                return false;
            }
        }), "the key is saved to options.txt");

        clickButton(ctx, "J");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> mc.screen instanceof SettingsScreen), "Esc while waiting does not close the screen");
        check(key(ctx).equals("key.keyboard.j") && button(ctx, "J") != null, "Esc keeps the old key");

        // E is also the inventory key: the screen takes the press (the inventory never sees it) and warns.
        clickButton(ctx, "J");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_E);
        ctx.waitTicks(2);
        check(key(ctx).equals("key.keyboard.e"), "E is the open key");
        check(!ctx.computeOnClient(mc -> mc.options.keyInventory.consumeClick()), "the inventory key did not fire");
        String clash = ctx.computeOnClient(mc -> I18n.get("skycosmetics.option.openKey.conflict", "|").split("\\|")[0]);
        String warning = ctx.computeOnClient(mc -> String.join(" ", ((SettingsScreen) mc.screen).rowText("openKey")));
        System.out.println("[SkyCosmeticsTest] conflict: " + warning.replace('\n', ' '));
        check(warning.contains(clash) && warning.contains("Inventory"), "the clash with the inventory key is shown");
        ctx.takeScreenshot("skycosmetics-22-settings-key-conflict");

        clickButton(ctx, "E");
        ctx.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
        ctx.waitTicks(2);
        check(ctx.computeOnClient(mc -> KeyMapping.get(OPEN).isUnbound()) && button(ctx, notBound(ctx)) != null, "Backspace unbinds");
        check(ctx.computeOnClient(mc -> !String.join(" ", ((SettingsScreen) mc.screen).rowText("openKey")).contains(clash)),
            "no clash when unbound");

        clickButton(ctx, notBound(ctx));
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_4);
        ctx.waitTicks(2);
        check(key(ctx).equals("key.mouse.4"), "a side mouse button binds: " + key(ctx));
        clickButton(ctx, ctx.computeOnClient(mc -> KeyMapping.get(OPEN).getTranslatedKeyMessage().getString()));
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(2);
        check(key(ctx).equals("key.mouse.4") && button(ctx, listening(ctx)) == null, "a left click cancels");

        ctx.runOnClient(mc -> {
            bindDirect(InputConstants.getKey(before));
            mc.options.save();
        });
        ctx.setScreen(() -> null);
        System.out.println("[SkyCosmeticsTest] open-key button checks passed");
    }

    /** Tooltips show your name; getHoverName and Cosmetics.originalName always keep Hypixel's, for other mods too. */
    private static void names(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("item replace entity @a hotbar.6 with minecraft:diamond_sword[minecraft:custom_data="
            + "{id:\"HYPERION\",uuid:\"t-shared\"},minecraft:custom_name=\"Hyperion\"]");
        sp.getServer().runCommand("item replace entity @a hotbar.7 with minecraft:player_head[minecraft:custom_data={id:\"PET\","
            + "petInfo:'{\"type\":\"GOLDEN_DRAGON\",\"uuid\":\"t-shared-pet\"}'},minecraft:custom_name=\"[Lvl 100] Golden Dragon\"]");
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            Looks.put(false, "t-shared", new Looks.Look(null, null, "&6Sharp &lBlade", null, "shared name"));
            Looks.put(false, "t-shared-pet", new Looks.Look(null, null, "&dDraggy", null, "pet name"));
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            ItemStack sword = mc.player.getInventory().getItem(6), pet = mc.player.getInventory().getItem(7);
            try {
                check(sword.getHoverName().getString().equals("Hyperion"), "other mods read Hypixel's name");
                check(sword.getStyledHoverName().getString().equals("Sharp Blade"), "tooltips show your name");
                check(Cosmetics.originalName(sword).getString().equals("Hyperion"), "SkyCosmetics reads Hypixel's name");
                check(pet.getHoverName().getString().equals("[Lvl 100] Golden Dragon"), "pets keep Hypixel's name");
                check(pet.getStyledHoverName().getString().equals("Draggy"), "the pet's tooltip shows your name");
            } finally {
                Looks.put(false, "t-shared", null);
                Looks.put(false, "t-shared-pet", null);
            }
        });
        System.out.println("[SkyCosmeticsTest] name checks passed");
    }

    /** My Items learns from a menu titled like Hypixel's wardrobe, never from one titled like the auction house. */
    private static void menus(ClientGameTestContext ctx) {
        openMenu(ctx, "(1/3) Armor Sets", "t-wardrobe-helm");
        ctx.waitTicks(25);
        ctx.takeScreenshot("skycosmetics-23-fake-wardrobe");
        ctx.setScreen(() -> null);
        openMenu(ctx, "Auctions Browser", "t-auction-helm");
        ctx.waitTicks(25);
        ctx.setScreen(() -> null);
        ctx.runOnClient(mc -> {
            OwnedItems.Owned learned = OwnedItems.list().stream().filter(o -> o.uuid.equals("t-wardrobe-helm")).findFirst().orElse(null);
            check(learned != null && "Wardrobe".equals(learned.source) && learned.category == OwnedItems.Category.HELMET,
                "the wardrobe helmet is in My Items, from the Wardrobe");
            check(OwnedItems.list().stream().noneMatch(o -> o.uuid.equals("t-auction-helm")), "auction items never get in");
        });

        // Stored Items off: a wardrobe teaches nothing and My Items hides what it knew; on brings it back.
        ctx.runOnClient(mc -> Settings.storedItems = false);
        try {
            openMenu(ctx, "(1/3) Armor Sets", "t-wardrobe-off");
            ctx.waitTicks(25);
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> {
                check(!OwnedItems.knows("t-wardrobe-off"), "Stored Items off: the wardrobe teaches nothing");
                check(OwnedItems.list().isEmpty(), "Stored Items off: My Items lists nothing stored");
            });
        } finally {
            ctx.runOnClient(mc -> {
                Settings.storedItems = true;
                OwnedItems.refresh();
            });
        }
        ctx.runOnClient(mc -> {
            check(OwnedItems.list().stream().anyMatch(o -> o.uuid.equals("t-wardrobe-helm")), "Stored Items on: it is back");
            OwnedItems.forget("t-wardrobe-helm");
        });
        System.out.println("[SkyCosmeticsTest] My Items menu checks passed");
    }

    private static void openMenu(ClientGameTestContext ctx, String title, String uuid) {
        ctx.runOnClient(mc -> {
            ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
            CompoundTag tag = new CompoundTag();
            tag.putString("id", "NECRON_HELMET");
            tag.putString("uuid", uuid);
            helmet.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            helmet.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("LEGENDARY DUNGEON HELMET"))));
            SimpleContainer box = new SimpleContainer(27);
            box.setItem(13, helmet);
            mc.setScreen(new ContainerScreen(ChestMenu.threeRows(100, mc.player.getInventory(), box),
                mc.player.getInventory(), Component.literal(title)));
        });
    }

    // ------------------------------------------------------------ helpers ---

    private static void bindDirect(InputConstants.Key key) {
        KeyMapping.get(OPEN).setKey(key);
        KeyMapping.resetMapping();
    }

    /** Vanilla's name for an unbound key ("Not Bound"). */
    private static String notBound(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> I18n.get("key.keyboard.unknown"));
    }

    private static String listening(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> I18n.get("skycosmetics.option.openKey.listening"));
    }

    private static String key(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> KeyMapping.get(OPEN).saveString());
    }

    private static void setScale(ClientGameTestContext ctx, int scale) {
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(scale);
            mc.resizeGui();
        });
        ctx.waitTicks(3);
    }

    /** A real mouse click in the middle of the button with this label. */
    private static void clickButton(ClientGameTestContext ctx, String label) {
        double[] at = ctx.computeOnClient(mc -> {
            Button b = button(mc.screen, label);
            check(b != null, "button '" + label + "'");
            double scale = mc.getWindow().getGuiScale();
            return new double[]{(b.getX() + b.getWidth() / 2.0) * scale, (b.getY() + b.getHeight() / 2.0) * scale};
        });
        ctx.getInput().setCursorPos(at[0], at[1]);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        ctx.waitTicks(1);
    }

    private static Button button(ClientGameTestContext ctx, String label) {
        return ctx.computeOnClient(mc -> button(mc.screen, label));
    }

    private static Button button(Screen s, String label) {
        for (AbstractWidget w : widgets(s)) {
            if (w instanceof Button b && b.getMessage().getString().equals(label)) return b;
        }
        return null;
    }

    /** Every widget, inside the scrolling list too, in layout order. */
    private static List<AbstractWidget> widgets(GuiEventListener root) {
        List<AbstractWidget> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(GuiEventListener l, List<AbstractWidget> out) {
        if (l instanceof AbstractWidget w) out.add(w);
        if (l instanceof ContainerEventHandler c) for (GuiEventListener child : c.children()) collect(child, out);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[SkyCosmeticsTest] failed: " + what);
    }
}
