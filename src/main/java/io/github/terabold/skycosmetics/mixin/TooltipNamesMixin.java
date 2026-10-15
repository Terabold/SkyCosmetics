package io.github.terabold.skycosmetics.mixin;

import io.github.terabold.skycosmetics.ChatNames;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** An item tooltip's lines as they are drawn, after every mod built them: your item names in them. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class TooltipNamesMixin {
    @ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"), argsOnly = true, require = 0)
    private List<Component> skycosmetics$names(List<Component> lines) {
        return ChatNames.applyLore(lines); // never throws
    }
}
