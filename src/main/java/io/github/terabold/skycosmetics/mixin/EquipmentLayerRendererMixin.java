package io.github.terabold.skycosmetics.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.terabold.skycosmetics.Cosmetics;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.render.Glints;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Worn armour glint: the piece drawn here is the render copy from
 * HumanoidMobRendererMixin, which carries its look's glint style.
 * Optional (require 0): if another mod takes this call away, armour keeps
 * Minecraft's glint instead of the game failing to start.
 */
@Mixin(EquipmentLayerRenderer.class)
public class EquipmentLayerRendererMixin {
    @ModifyExpressionValue(
        method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorEntityGlint()Lnet/minecraft/client/renderer/rendertype/RenderType;"),
        require = 0)
    private RenderType skycosmetics$glint(RenderType vanilla, @Local(argsOnly = true) ItemStack stack) {
        try {
            RenderType own = Glints.armour(Cosmetics.glintOf(stack));
            return own != null ? own : vanilla;
        } catch (RuntimeException e) {
            Io.failed("Drawing an armor glint style", e);
            return vanilla;
        }
    }
}
