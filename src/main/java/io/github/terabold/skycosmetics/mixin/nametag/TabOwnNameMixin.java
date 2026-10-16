package io.github.terabold.skycosmetics.mixin.nametag;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.terabold.skycosmetics.nametag.Nametags;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A tab-list name as it is drawn: your own name styled as your nametag has it. */
@Mixin(PlayerTabOverlay.class)
public abstract class TabOwnNameMixin {
    @ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"), require = 0)
    private Component skycosmetics$ownName(Component name) {
        return Nametags.ownName(name); // never throws
    }
}
