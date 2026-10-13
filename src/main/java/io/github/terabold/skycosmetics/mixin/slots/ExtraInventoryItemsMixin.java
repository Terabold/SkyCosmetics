package io.github.terabold.skycosmetics.mixin.slots;

import io.github.terabold.skycosmetics.slots.RarityBackgrounds;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The common item/fakeItem path also covers third-party equipment previews. */
@Mixin(GuiGraphicsExtractor.class)
abstract class ExtraInventoryItemsMixin {
    @Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), require = 0)
    private void skycosmetics$equipment(LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        RarityBackgrounds.drawExtraInventoryItem((GuiGraphicsExtractor)(Object)this, x, y, stack);
    }
}
