package io.github.terabold.skycosmetics.mixin.slots;

import io.github.terabold.skycosmetics.slots.SlotLocks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Slot locking in menus: a click on a locked slot (or a number-key swap into one) stops here, before the menu sends
 * anything; and the lock marks and bindings are drawn. Never throws into the menu.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class SlotLockScreenMixin {
    @Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void skycosmetics$guard(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
        try {
            if (SlotLocks.stopClick(slot, button, input)) ci.cancel();
        } catch (RuntimeException e) {
            io.github.terabold.skycosmetics.Io.failed("Checking a slot lock", e);
        }
    }

    @Inject(method = "extractSlot", require = 0, at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"))
    private void skycosmetics$lockMark(GuiGraphicsExtractor g, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        SlotLocks.drawIcon(g, slot); // catches its own failures
    }

    @Inject(method = "extractContents", at = @At("TAIL"), require = 0)
    private void skycosmetics$bindings(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        SlotLocks.drawBindings(g, (AbstractContainerScreen<?>) (Object) this, mouseX, mouseY);
    }
}
