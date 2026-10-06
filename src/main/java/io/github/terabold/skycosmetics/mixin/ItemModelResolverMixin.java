package io.github.terabold.skycosmetics.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.render.Glints;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Every item model in the game - GUI slots, first and third person hands,
 * dropped items, item frames, non-skull hats - is built by appendItemLayers.
 * Swapping the stack here is the one hook that covers all of them. The layers
 * built meanwhile are tagged with the copy's glint style (Glints), and
 * the outer style comes back afterwards even if a model throws (a bundle
 * builds its selected item inside its own call).
 */
@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {
    @WrapMethod(method = "appendItemLayers")
    private void skycosmetics$swap(ItemStackRenderState output, ItemStack stack, ItemDisplayContext context, Level level,
                                 ItemOwner owner, int seed, Operation<Void> original) {
        ItemStack shown = Cosmetics.forRender(stack, owner); // never throws: a failure draws the stack as sent
        Glints.Style outer = Glints.enter(output, Cosmetics.glintOf(shown));
        try {
            original.call(output, shown, context, level, owner, seed);
        } finally {
            Glints.leave(outer);
        }
    }
}
