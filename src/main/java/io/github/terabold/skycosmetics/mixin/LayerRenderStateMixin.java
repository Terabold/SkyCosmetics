package io.github.terabold.skycosmetics.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.render.Glints;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * A layer whose stack has a glint style submits its glint with the style's
 * render type and hands NONE to the item submit, so vanilla's purple glint is
 * not drawn under it. The foil read patched (ordinal 1) is the item-model
 * branch's; the special-model branch (shields, tridents) keeps vanilla's glint.
 * It is read as an argument of submitItem, which Fabric's renderer API
 * redirects: a mesh it adds to the layer (Continuity's emissive overlays) gets
 * the same NONE, so that part has no glint. Optional (require 0): if another
 * mod replaces this method, items keep Minecraft's glint instead of the game
 * failing to start.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public class LayerRenderStateMixin implements Glints.Layer {
    @Shadow
    @Final
    private List<BakedQuad> quads;

    @Unique
    private Glints.Style skycosmetics$glint;
    @Unique
    private ItemDisplayContext skycosmetics$context;

    @Override
    public void skycosmetics$glint(Glints.Style style, ItemDisplayContext context) {
        skycosmetics$glint = style;
        skycosmetics$context = context;
    }

    @ModifyExpressionValue(method = "submit", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, ordinal = 1,
        target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState$LayerRenderState;foilType:Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;"),
        require = 0)
    private ItemStackRenderState.FoilType skycosmetics$glint(ItemStackRenderState.FoilType foil,
                                                           @Local(argsOnly = true) PoseStack pose,
                                                           @Local(argsOnly = true) SubmitNodeCollector out) {
        if (skycosmetics$glint == null || foil == ItemStackRenderState.FoilType.NONE) return foil;
        try {
            return Glints.submit(pose, out, skycosmetics$glint, quads, foil, skycosmetics$context)
                ? ItemStackRenderState.FoilType.NONE : foil;
        } catch (RuntimeException e) {
            Io.failed("Drawing an item's glint style", e);
            return foil;
        }
    }
}
