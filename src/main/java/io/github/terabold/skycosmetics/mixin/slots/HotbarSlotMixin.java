package io.github.terabold.skycosmetics.mixin.slots;

import io.github.terabold.skycosmetics.slots.RarityBackgrounds;
import io.github.terabold.skycosmetics.slots.SlotArea;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The HUD hotbar (and off-hand) slots: a rarity background under the item, and the hotbar area for Head Size. */
@Mixin(Gui.class)
public class HotbarSlotMixin {
    @Inject(method = "extractSlot", at = @At("HEAD"), require = 0)
    private void skycosmetics$enterHotbar(GuiGraphicsExtractor g, int x, int y, DeltaTracker delta, Player player, ItemStack stack,
                                          int seed, CallbackInfo ci) {
        SlotArea.enter(SlotArea.HOTBAR);
        RarityBackgrounds.drawHotbar(g, x, y, stack); // catches its own failures
    }

    @Inject(method = "extractSlot", at = @At("RETURN"), require = 0)
    private void skycosmetics$leaveHotbar(GuiGraphicsExtractor g, int x, int y, DeltaTracker delta, Player player, ItemStack stack,
                                          int seed, CallbackInfo ci) {
        SlotArea.leave();
    }
}
