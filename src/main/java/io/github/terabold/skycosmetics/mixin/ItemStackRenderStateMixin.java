package io.github.terabold.skycosmetics.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.terabold.skycosmetics.render.Glints;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every layer a model adds is tagged with the glint style of the stack being
 * resolved (usually none). Optional (require 0): untagged layers keep
 * Minecraft's glint.
 */
@Mixin(ItemStackRenderState.class)
public class ItemStackRenderStateMixin {
    @Shadow
    ItemDisplayContext displayContext;

    @ModifyReturnValue(method = "newLayer", at = @At("RETURN"), require = 0)
    private ItemStackRenderState.LayerRenderState skycosmetics$tag(ItemStackRenderState.LayerRenderState layer) {
        ((Glints.Layer) layer).skycosmetics$glint(Glints.current(), displayContext);
        return layer;
    }
}
