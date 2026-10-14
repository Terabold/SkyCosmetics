package io.github.terabold.skycosmetics.test;

import io.github.terabold.skycosmetics.mixin.AbstractContainerScreenAccessor;
import io.github.terabold.skycosmetics.slots.Mask;
import io.github.terabold.skycosmetics.slots.RarityBackgrounds;
import io.github.terabold.skycosmetics.slots.SlotLocks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.util.Arrays;
import java.util.List;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;

/**
 * Slot locking with real input: the key over a slot locks it; a click on it, a number-key swap into it, a double-click
 * gathering from it and Q on it are stopped; bindings are made by holding the lock key and letting go over the other
 * slot, or by pressing the bind key on both; a shift-click (or a plain click, when chosen) on a bound slot swaps it with
 * its hotbar slot; a click kept from the menu while the key is down doesn't act on its release either; the outline mark
 * takes the rarity background's outline.
 */
public class SlotLocksTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            world.getServer().runCommand("item replace entity @a container.9 with minecraft:diamond_sword");
            world.getServer().runCommand("item replace entity @a container.10 with minecraft:golden_apple");
            world.getServer().runCommand("item replace entity @a container.15 with minecraft:apple");
            world.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:stick");
            ctx.runOnClient(mc -> {
                SlotLocks.testLook(SlotLocks.Icon.PADLOCK, true);
                SlotLocks.testBinding(SlotLocks.Swap.SHIFT_CLICK, false, false);
                for (int i : new int[]{9, 10, 11, 12, 13}) { SlotLocks.testLock(i, false); SlotLocks.testBind(i, -1); }
            });
            ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
            ctx.waitTicks(3);

            // The key over slot 10 locks it.
            hover(ctx, 10);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_L);
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> SlotLocks.isLocked(10)), "the lock key over a slot locks it");
            ctx.takeScreenshot("skycosmetics-slot-lock-padlock");

            // A real click on it picks nothing up.
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> mc.player.containerMenu.getCarried().isEmpty()
                && mc.player.getInventory().getItem(10).is(Items.GOLDEN_APPLE)), "a click on a locked slot picks nothing up");

            ctx.runOnClient(mc -> {
                Slot s10 = slot(mc, 10), s9 = slot(mc, 9), s15 = slot(mc, 15);
                check(SlotLocks.blocks(s10, 0, ContainerInput.QUICK_MOVE), "a shift-click on a locked slot is stopped");
                check(SlotLocks.blocks(s10, 0, ContainerInput.THROW), "dropping from a locked slot is stopped");
                check(!SlotLocks.blocks(s9, 0, ContainerInput.PICKUP), "other slots keep working");
                SlotLocks.testLock(3, true);
                check(SlotLocks.blocks(s9, 3, ContainerInput.SWAP), "a number key can't swap into a locked hotbar slot");
                SlotLocks.testLock(3, false);
                // A double-click with golden apples on the cursor would gather the locked ones too.
                mc.player.containerMenu.setCarried(new ItemStack(Items.GOLDEN_APPLE));
                check(SlotLocks.blocks(s15, 0, ContainerInput.PICKUP_ALL), "a double-click can't gather from a locked slot");
                mc.player.containerMenu.setCarried(new ItemStack(Items.STICK));
                check(!SlotLocks.blocks(s15, 0, ContainerInput.PICKUP_ALL), "a double-click of something else still works");
                mc.player.containerMenu.setCarried(ItemStack.EMPTY);
            });

            // Bound: shift-click on the inventory end swaps it with the hotbar slot.
            ctx.runOnClient(mc -> SlotLocks.testBind(9, 0));
            hover(ctx, 9);
            ctx.getInput().holdShift();
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.getInput().releaseShift();
            ctx.waitTicks(4);
            check(ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND_SWORD)
                && mc.player.getInventory().getItem(9).is(Items.STICK)), "a shift-click on a bound slot swaps it with its hotbar slot");
            ctx.takeScreenshot("skycosmetics-slot-lock-binding");

            // Swapping with a plain click, when chosen.
            ctx.runOnClient(mc -> SlotLocks.testBinding(SlotLocks.Swap.CLICK, false, false));
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.waitTicks(4);
            check(ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0).is(Items.STICK)
                && mc.player.getInventory().getItem(9).is(Items.DIAMOND_SWORD)), "a plain click swaps when that's chosen");
            ctx.runOnClient(mc -> SlotLocks.testBinding(SlotLocks.Swap.SHIFT_CLICK, false, false));

            // Hold the lock key on one slot and let go over the other.
            hover(ctx, 11);
            ctx.getInput().holdKey(GLFW.GLFW_KEY_L);
            ctx.waitTicks(1);
            hover(ctx, 1);
            ctx.takeScreenshot("skycosmetics-slot-lock-dragging");
            ctx.getInput().releaseKey(GLFW.GLFW_KEY_L);
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> Arrays.equals(SlotLocks.testPartner(11), new int[]{11, 1})), "hold and drag binds the two slots");

            // The bind key: press on one slot, then on the other; twice on the same slot removes it.
            ctx.runOnClient(mc -> SlotLocks.testBindKey(GLFW.GLFW_KEY_B));
            hover(ctx, 12);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_B);
            hover(ctx, 2);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_B);
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> Arrays.equals(SlotLocks.testPartner(12), new int[]{12, 2})), "two presses of the bind key bind the slots");
            hover(ctx, 12);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_B);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_B);
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> SlotLocks.testPartner(12) == null), "pressing the bind key twice on a slot removes its binding");

            // One hotbar slot, several bindings, only when chosen.
            hover(ctx, 13);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_B);
            hover(ctx, 1);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_B);
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> SlotLocks.testPartner(11) == null && SlotLocks.testPartner(13) != null),
                "binding a hotbar slot again replaces its old binding");
            ctx.runOnClient(mc -> { SlotLocks.testBinding(SlotLocks.Swap.SHIFT_CLICK, true, false); SlotLocks.testBind(11, 1); });
            check(ctx.computeOnClient(mc -> SlotLocks.testPartner(11) != null && SlotLocks.testPartner(13) != null),
                "with several per hotbar slot, both bindings stay");

            // A click kept from the menu while the key is down doesn't act on its release either.
            hover(ctx, 15);
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.waitTicks(2);
            hover(ctx, 16);
            ctx.getInput().holdKey(GLFW.GLFW_KEY_L);
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.getInput().releaseKey(GLFW.GLFW_KEY_L);
            ctx.waitTicks(3);
            check(ctx.computeOnClient(mc -> mc.player.containerMenu.getCarried().is(Items.APPLE)
                && mc.player.getInventory().getItem(16).isEmpty()), "a click while the lock key is down does nothing, press or release");
            hover(ctx, 15);
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.waitTicks(2);

            // Outline mark with rarity backgrounds: the lock outline replaces the rarity outline.
            ctx.runOnClient(mc -> {
                var apple = mc.player.getInventory().getItem(10);
                apple.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("EPIC ACCESSORY"))));
                RarityBackgrounds.set(true, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 60, true);
                SlotLocks.testLook(SlotLocks.Icon.OUTLINE, true);
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("skycosmetics-slot-lock-outline");

            // Q on a locked hotbar slot, for real.
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> { mc.player.getInventory().setSelectedSlot(0); SlotLocks.testLock(0, true); });
            ctx.waitTicks(2);
            ctx.getInput().pressKey(options -> options.keyDrop);
            ctx.waitTicks(4);
            check(ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0).is(Items.STICK)), "Q on a locked hotbar slot drops nothing");
            ctx.runOnClient(mc -> { SlotLocks.testLock(0, false); SlotLocks.testBind(9, -1); });
            check(!ctx.computeOnClient(mc -> SlotLocks.blocksDrop(mc.player)), "Q works again once unlocked and unbound");
        } finally {
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> {
                for (int i : new int[]{0, 9, 10, 11, 12, 13}) { SlotLocks.testLock(i, false); SlotLocks.testBind(i, -1); }
                SlotLocks.testBinding(SlotLocks.Swap.SHIFT_CLICK, false, false);
                SlotLocks.testBindKey(GLFW.GLFW_KEY_UNKNOWN);
                SlotLocks.testLook(SlotLocks.Icon.PADLOCK, true);
                RarityBackgrounds.reset();
            });
        }
        System.out.println("[SkyCosmeticsTest] slot locking and binding checks passed");
    }

    private static void hover(ClientGameTestContext ctx, int index) {
        double[] at = ctx.computeOnClient(mc -> centre(mc, index));
        ctx.getInput().setCursorPos(at[0], at[1]);
        ctx.waitTicks(2);
    }

    private static Slot slot(Minecraft mc, int index) {
        for (Slot s : mc.player.inventoryMenu.slots) if (s.container == mc.player.getInventory() && s.getContainerSlot() == index) return s;
        throw new AssertionError("no slot " + index);
    }

    private static double[] centre(Minecraft mc, int index) {
        var a = (AbstractContainerScreenAccessor) mc.screen;
        Slot s = slot(mc, index);
        double scale = mc.getWindow().getGuiScale();
        return new double[]{(a.skycosmetics$leftPos() + s.x + 8) * scale, (a.skycosmetics$topPos() + s.y + 8) * scale};
    }
}
