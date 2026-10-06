package io.github.terabold.skycosmetics.mixin.slots;

import io.github.terabold.skycosmetics.slots.RarityBackgrounds;
import io.github.terabold.skycosmetics.slots.SlotArea;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menu slots: rarity backgrounds go down once per frame, right before the hovered slot's highlight, so the
 * highlight and every item, count and other mod's overlay stay on top. Around each slot (and the item on the
 * cursor) the slot area is set for Head Size. All optional: a menu that changed draws as vanilla does.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSlotsMixin {
    @Inject(method = "extractContents", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;extractSlotHighlightBack(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
    private void skycosmetics$rarityBackgrounds(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        RarityBackgrounds.drawSlots(g, (AbstractContainerScreen<?>) (Object) this); // catches its own failures
    }

    @Inject(method = "extractSlot", at = @At("HEAD"), require = 0)
    private void skycosmetics$enterSlot(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        SlotArea.enterSlot(slot);
    }

    @Inject(method = "extractSlot", at = @At("RETURN"), require = 0)
    private void skycosmetics$leaveSlot(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        SlotArea.leaveSlot();
    }

    @Inject(method = {"extractCarriedItem", "extractSnapbackItem"}, at = @At("HEAD"), require = 0)
    private void skycosmetics$enterCarried(CallbackInfo ci) {
        SlotArea.enter(SlotArea.INVENTORY);
    }

    @Inject(method = {"extractCarriedItem", "extractSnapbackItem"}, at = @At("RETURN"), require = 0)
    private void skycosmetics$leaveCarried(CallbackInfo ci) {
        SlotArea.leave();
    }
}
