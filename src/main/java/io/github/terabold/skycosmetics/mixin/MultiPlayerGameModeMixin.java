package io.github.terabold.skycosmetics.mixin;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.pet.PetTracker;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link PetTracker} see which pet a click in the Pets menu is on. Every
 * container click goes through here (your mouse, number keys, and mods that
 * click slots for you, such as Odin's pet keybinds), so none is missed. Read
 * only: the click is never changed, cancelled or sent again.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void skycosmetics$containerInput(int containerId, int slot, int button, ContainerInput input, Player player,
                                           CallbackInfo ci) {
        try {
            PetTracker.containerInput(containerId, slot, button, input);
        } catch (RuntimeException e) {
            Io.failed("Reading a click in the Pets menu", e);
        }
    }
}
