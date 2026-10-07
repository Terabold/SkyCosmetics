package io.github.terabold.skycosmetics.mixin.slots;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.slots.HeadSize;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Head Size, once per top-level item after all its layers are built (bundle contents are built inside). Optional:
 * if another mod replaces the method, heads keep their size.
 */
@Mixin(ItemModelResolver.class)
public class HeadSizeMixin {
    @Inject(method = "updateForTopItem", at = @At("TAIL"), require = 0)
    private void skycosmetics$headSize(ItemStackRenderState output, ItemStack stack, ItemDisplayContext context, Level level,
                                       ItemOwner owner, int seed, CallbackInfo ci) {
        try {
            HeadSize.apply(output, stack, context);
        } catch (RuntimeException e) {
            Io.failed("Resizing a head icon", e);
        }
    }
}
