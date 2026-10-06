package io.github.terabold.skycosmetics.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.terabold.skycosmetics.Cosmetics;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Worn armour for players and every humanoid mob is copied into the render
 * state here; the armour layer then tints leather from that copy's dye. One
 * wrap covers helmet, chestplate, leggings and boots.
 */
@Mixin(HumanoidMobRenderer.class)
public class HumanoidMobRendererMixin {
    @WrapOperation(
        method = "getEquipmentIfRenderable",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getItemBySlot(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack skycosmetics$armour(LivingEntity entity, EquipmentSlot slot, Operation<ItemStack> original) {
        return Cosmetics.forEntity(entity, slot, original.call(entity, slot));
    }

    /**
     * Vanilla stores a copy of the piece; it must stay marked as our copy so the armour layer finds its glint.
     * Optional (require 0): without it worn armour keeps Minecraft's glint.
     */
    @ModifyExpressionValue(
        method = "getEquipmentIfRenderable",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;copy()Lnet/minecraft/world/item/ItemStack;"),
        require = 0)
    private static ItemStack skycosmetics$keepGlint(ItemStack copy, @Local ItemStack worn) {
        return Cosmetics.copied(worn, copy);
    }
}
