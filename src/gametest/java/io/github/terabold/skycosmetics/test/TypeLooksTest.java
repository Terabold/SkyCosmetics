package io.github.terabold.skycosmetics.test;

import com.mojang.authlib.GameProfile;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Looks;
import io.github.terabold.skycosmetics.gui.StudioScreen;
import io.github.terabold.skycosmetics.items.Mine;
import io.github.terabold.skycosmetics.items.OwnedItems;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.UUID;

/**
 * "Every item of this type" looks show only on your own items: what you carry, what My Items remembers, never an
 * item in an auction, trade or shop menu (one you sold and might buy back included), nothing without a UUID that is
 * not in your inventory, and nothing on another player.
 */
final class TypeLooksTest {
    private static final String TYPE = "SCAM_BOOTS";

    private TypeLooksTest() {}

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        sp.getServer().runCommand("item replace entity @a inventory.2 with " + boots("t-mine-boots"));
        sp.getServer().runCommand("item replace entity @a inventory.3 with " + boots("t-sold-boots"));
        ctx.waitTicks(12); // My Items learns both
        sp.getServer().runCommand("item replace entity @a inventory.3 with minecraft:air"); // sold
        ctx.waitTicks(3);
        ctx.runOnClient(mc -> {
            check(OwnedItems.knows("t-sold-boots"), "My Items remembers the sold boots");
            Looks.put(true, TYPE, new Looks.Look(null, "DYE_AURORA", "&cScam Boots", null, "Every Scam Boots"));
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            ItemStack mine = mc.player.getInventory().getItem(11);
            check(styled(mine), "your own boots wear the look for every Scam Boots");
            check(mine.getStyledHoverName().getString().equals("Scam Boots"), "and its name");
        });
        resetAndUndo(ctx);

        // An auction menu: someone else's boots, the ones you sold, and boots without a UUID.
        ctx.runOnClient(mc -> {
            SimpleContainer box = new SimpleContainer(54);
            box.setItem(10, stack("t-other-boots"));
            box.setItem(12, stack("t-sold-boots"));
            box.setItem(14, stack(null));
            ChestMenu menu = ChestMenu.sixRows(102, mc.player.getInventory(), box);
            mc.player.containerMenu = menu;
            mc.setScreen(new ContainerScreen(menu, mc.player.getInventory(), Component.literal("Auctions Browser")));
        });
        ctx.waitTicks(3);
        ctx.takeScreenshot("skycosmetics-40-auction-no-type-looks");
        ctx.runOnClient(mc -> {
            SimpleContainer box = (SimpleContainer) ((ChestMenu) mc.player.containerMenu).getContainer();
            String[] what = {"someone else's boots", "boots you sold", "boots without a UUID"};
            int[] slots = {10, 12, 14};
            for (int i = 0; i < slots.length; i++) {
                ItemStack s = box.getItem(slots[i]);
                check(!styled(s), "in an auction menu " + what[i] + " keep Hypixel's look");
                check(s.getStyledHoverName().getString().equals("Spring Boots"), "and Hypixel's name: " + what[i]);
            }
            check(styled(mc.player.getInventory().getItem(11)), "your own boots keep the look under the auction menu");
        });
        ctx.runOnClient(mc -> {
            mc.player.containerMenu = mc.player.inventoryMenu;
            mc.setScreen(null);
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            ItemStack sold = OwnedItems.list().stream().filter(o -> o.uuid.equals("t-sold-boots")).findFirst()
                .orElseThrow().stack();
            check(styled(sold), "out of the auction menu, My Items still shows the look on what it remembers");
            // Another player wearing the same type: never.
            RemotePlayer other = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), "Other"));
            ItemStack worn = stack("t-other-worn");
            check(Cosmetics.forEntity(other, EquipmentSlot.FEET, worn) == worn, "another player's boots keep Hypixel's look");
            check(Cosmetics.forEntity(mc.player, EquipmentSlot.FEET, mc.player.getInventory().getItem(11)) != mc.player.getInventory().getItem(11),
                "your own boots on your model wear it");
            System.out.println("[SkyCosmeticsTest] every-type looks only on your own items: OK (Mine version " + Mine.version() + ")");
            Looks.put(true, TYPE, null);
        });
        sp.getServer().runCommand("item replace entity @a inventory.2 with minecraft:air");
        ctx.runOnClient(mc -> {
            OwnedItems.forget("t-mine-boots");
            OwnedItems.forget("t-sold-boots");
        });
        ctx.waitTicks(2);
    }

    /**
     * The studio's removals on one item while a look for every item of its type exists: the summary's [x] drops one
     * part, Reset drops the item's whole look and leaves the type's, and both come back with Undo or Ctrl+Z.
     */
    private static void resetAndUndo(ClientGameTestContext ctx) {
        int[] was = ctx.computeOnClient(mc -> new int[]{mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.options.guiScale().get()});
        StudioLayoutTest.window(ctx, 1920, 1080, 2); // where the preview column shows the summary with its [x]s
        ctx.runOnClient(mc -> Looks.putItem("t-mine-boots", TYPE, Looks.Look.NONE.withDye("DYE_AURORA").withName("&9Mine")));
        StudioLayoutTest.open(ctx, mc -> mc.player.getInventory().getItem(11));
        ctx.runOnClient(mc -> {
            check(StudioLayoutTest.widget(mc, "This item only") != null, "the studio edits this item only");
            check(((StudioScreen) mc.screen).summaryShown(), "the summary is shown");
        });

        // The dye line's [x]: only the dye goes; Undo puts it back.
        int[] dyeX = ctx.computeOnClient(mc -> ((StudioScreen) mc.screen).summaryClearAt(1));
        StudioLayoutTest.click(ctx, dyeX[0], dyeX[1]);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            Looks.Look l = Looks.byUuid("t-mine-boots");
            check(l != null && l.dye() == null && "&9Mine".equals(l.name()), "the summary's [x] removes only the dye");
            var undo = StudioLayoutTest.widget(mc, "Undo");
            check(undo != null && undo.visible, "and offers Undo");
        });
        int[] undoAt = ctx.computeOnClient(mc -> {
            var undo = StudioLayoutTest.widget(mc, "Undo");
            return new int[]{undo.getX() + undo.getWidth() / 2, undo.getY() + undo.getHeight() / 2};
        });
        StudioLayoutTest.click(ctx, undoAt[0], undoAt[1]);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            Looks.Look l = Looks.byUuid("t-mine-boots");
            check(l != null && "DYE_AURORA".equals(l.dye()) && "&9Mine".equals(l.name()), "Undo puts the dye back");
        });

        // Reset: the item's own look goes, the type's stays; Ctrl+Z puts the item's back exactly.
        int[] resetAt = ctx.computeOnClient(mc -> {
            var reset = StudioLayoutTest.widget(mc, "Reset");
            check(reset != null && reset.active, "Reset is there");
            return new int[]{reset.getX() + reset.getWidth() / 2, reset.getY() + reset.getHeight() / 2};
        });
        StudioLayoutTest.click(ctx, resetAt[0], resetAt[1]);
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            check(Looks.byUuid("t-mine-boots") == null, "Reset removes the item's own look");
            check(Looks.byType(TYPE) != null && "DYE_AURORA".equals(Looks.byType(TYPE).dye()), "and leaves the look for every Scam Boots");
            check(((StudioScreen) mc.screen).footer().startsWith("Reset. The look for every"), "and says the type's look still applies");
            mc.screen.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_Z, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL));
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            Looks.Look l = Looks.byUuid("t-mine-boots");
            check(l != null && "DYE_AURORA".equals(l.dye()) && "&9Mine".equals(l.name()), "Ctrl+Z after Reset puts the whole look back");
        });
        ctx.takeScreenshot("skycosmetics-40-studio-reset-undone");
        ctx.setScreen(() -> null);
        ctx.runOnClient(mc -> Looks.put(false, "t-mine-boots", null));
        StudioLayoutTest.window(ctx, was[0], was[1], was[2]);
        ctx.waitTicks(2);
    }

    /** Drawn as a restyled copy (dyed), not as Hypixel's stack. */
    private static boolean styled(ItemStack s) {
        ItemStack shown = Cosmetics.forRender(s, null);
        return shown != s && shown.has(DataComponents.DYED_COLOR);
    }

    private static String boots(String uuid) {
        return "minecraft:leather_boots[minecraft:custom_data={id:\"" + TYPE + "\",uuid:\"" + uuid + "\"},"
            + "minecraft:custom_name={text:\"Spring Boots\",italic:false},minecraft:lore=[\"LEGENDARY BOOTS\"]]";
    }

    private static ItemStack stack(String uuid) {
        CompoundTag t = new CompoundTag();
        t.putString("id", TYPE);
        if (uuid != null) t.putString("uuid", uuid);
        ItemStack s = new ItemStack(Items.LEATHER_BOOTS);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Spring Boots"));
        s.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("LEGENDARY BOOTS"))));
        return s;
    }

    private static void check(boolean ok, String what) {
        Report.check(ok, what);
    }
}
