package io.github.terabold.skycosmetics.mixin.slots;

import io.github.terabold.skycosmetics.slots.SlotLocks;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Q on a locked hotbar slot: nothing is dropped, so nothing is sent. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerDropMixin {
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void skycosmetics$keepLocked(boolean all, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (SlotLocks.blocksDrop((LocalPlayer) (Object) this)) cir.setReturnValue(false);
        } catch (RuntimeException e) {
            io.github.terabold.skycosmetics.Io.failed("Checking a slot lock", e);
        }
    }
}
