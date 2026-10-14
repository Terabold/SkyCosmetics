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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import static io.github.terabold.skycosmetics.test.StudioLayoutTest.check;

/**
 * Slot locking with real input: the key over a slot locks it; a click, a number-key swap into it and Q on it are
 * stopped; a shift-click on a bound slot swaps it with its hotbar slot; the outline mark takes the rarity
 * background's outline.
 */
public class SlotLocksTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            world.getServer().runCommand("item replace entity @a container.9 with minecraft:diamond_sword");
            world.getServer().runCommand("item replace entity @a container.10 with minecraft:golden_apple");
            world.getServer().runCommand("item replace entity @a hotbar.0 with minecraft:stick");
            ctx.runOnClient(mc -> {
                SlotLocks.testLook(SlotLocks.Icon.PADLOCK, true);
                SlotLocks.testLock(10, false);
                SlotLocks.testBind(9, -1);
            });
            ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
            ctx.waitTicks(3);

            // The key over slot 10 locks it.
            double[] at = ctx.computeOnClient(mc -> centre(mc, 10));
            ctx.getInput().setCursorPos(at[0], at[1]);
            ctx.waitTicks(2);
            ctx.getInput().pressKey(GLFW.GLFW_KEY_L);
            ctx.waitTicks(2);
            check(ctx.computeOnClient(mc -> SlotLocks.isLocked(10)), "the lock key over a slot locks it");
            ctx.takeScreenshot("skycosmetics-slot-lock-padlock");

            ctx.runOnClient(mc -> {
                Slot s10 = slot(mc, 10), s9 = slot(mc, 9);
                check(SlotLocks.blocks(s10, 0, ContainerInput.PICKUP), "a click on a locked slot is stopped");
                check(SlotLocks.blocks(s10, 0, ContainerInput.QUICK_MOVE), "a shift-click on a locked slot is stopped");
                check(SlotLocks.blocks(s10, 0, ContainerInput.THROW), "dropping from a locked slot is stopped");
                check(!SlotLocks.blocks(s9, 0, ContainerInput.PICKUP), "other slots keep working");
                SlotLocks.testLock(3, true);
                check(SlotLocks.blocks(s9, 3, ContainerInput.SWAP), "a number key can't swap into a locked hotbar slot");
                SlotLocks.testLock(3, false);
                mc.player.getInventory().setSelectedSlot(0);
                SlotLocks.testLock(0, true);
                check(SlotLocks.blocksDrop(mc.player), "Q on a locked hotbar slot drops nothing");
                SlotLocks.testLock(0, false);
                check(!SlotLocks.blocksDrop(mc.player), "Q works again once unlocked");
            });

            // Bound: shift-click on the inventory end swaps it with the hotbar slot.
            ctx.runOnClient(mc -> SlotLocks.testBind(9, 0));
            double[] s9 = ctx.computeOnClient(mc -> centre(mc, 9));
            ctx.getInput().setCursorPos(s9[0], s9[1]);
            ctx.waitTicks(2);
            ctx.getInput().holdShift();
            ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            ctx.getInput().releaseShift();
            ctx.waitTicks(4);
            check(ctx.computeOnClient(mc -> mc.player.getInventory().getItem(0).is(Items.DIAMOND_SWORD)
                && mc.player.getInventory().getItem(9).is(Items.STICK)), "a shift-click on a bound slot swaps it with its hotbar slot");
            ctx.takeScreenshot("skycosmetics-slot-lock-binding");

            // Outline mark with rarity backgrounds: the lock outline replaces the rarity outline.
            ctx.runOnClient(mc -> {
                var apple = mc.player.getInventory().getItem(10);
                apple.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("EPIC ACCESSORY"))));
                RarityBackgrounds.set(true, Mask.Shape.ROUNDED, Mask.Fill.SOLID, 60, true);
                SlotLocks.testLook(SlotLocks.Icon.OUTLINE, true);
            });
            ctx.waitTicks(3);
            ctx.takeScreenshot("skycosmetics-slot-lock-outline");
        } finally {
            ctx.setScreen(() -> null);
            ctx.runOnClient(mc -> {
                SlotLocks.testLock(10, false);
                SlotLocks.testBind(9, -1);
                SlotLocks.testLook(SlotLocks.Icon.PADLOCK, true);
                RarityBackgrounds.reset();
            });
        }
        System.out.println("[SkyCosmeticsTest] slot locking and binding checks passed");
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
