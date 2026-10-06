package io.github.terabold.skycosmetics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.terabold.skycosmetics.Cosmetics;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The item name above the hotbar. Vanilla draws it from {@code getHoverName}, which keeps Hypixel's name
 * for other mods; only this one call shows yours. White underneath, like tooltips, so uncoloured letters
 * do not take the rarity colour.
 */
@Mixin(Gui.class)
public class GuiMixin {
    @WrapOperation(method = "extractSelectedItemName", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/item/ItemStack;getHoverName()Lnet/minecraft/network/chat/Component;"))
    private Component skycosmetics$heldName(ItemStack stack, Operation<Component> original) {
        Component name = Cosmetics.customName(stack);
        return name != null ? Component.empty().withStyle(ChatFormatting.WHITE).append(name) : original.call(stack);
    }
}
