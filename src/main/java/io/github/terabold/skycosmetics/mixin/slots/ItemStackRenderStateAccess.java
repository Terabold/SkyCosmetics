package io.github.terabold.skycosmetics.mixin.slots;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Head Size reads the layers a GUI item was just built from. */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccess {
    @Accessor("activeLayerCount")
    int skycosmetics$activeLayerCount();

    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] skycosmetics$layers();
}
