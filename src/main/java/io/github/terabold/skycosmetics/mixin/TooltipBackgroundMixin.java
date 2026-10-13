package io.github.terabold.skycosmetics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.tooltip.TooltipArtwork;
import io.github.terabold.skycosmetics.tooltip.TooltipSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TooltipRenderUtil.class)
public abstract class TooltipBackgroundMixin {
    @Shadow private static Identifier getBackgroundSprite(Identifier style) { throw new AssertionError(); }
    @Shadow private static Identifier getFrameSprite(Identifier style) { throw new AssertionError(); }

    @WrapOperation(method = "extractTooltipBackground", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private static void skycosmetics$fill(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
                                          Operation<Void> original, @Local(argsOnly = true) Identifier style) {
        if (TooltipSettings.enabled && TooltipSettings.customBackground) {
            try { TooltipArtwork.draw(g, getFrameSprite(style), x, y, w, h, true, false); return; }
            catch (RuntimeException e) { Io.failed("Drawing a tooltip background", e); }
        }
        original.call(g, pipeline, sprite, x, y, w, h);
    }

    @WrapOperation(method = "extractTooltipBackground", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private static void skycosmetics$border(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
                                            Operation<Void> original, @Local(argsOnly = true) Identifier style) {
        if (TooltipSettings.enabled && (TooltipSettings.customBackground || TooltipSettings.customBorder)) g.nextStratum();
        if (TooltipSettings.enabled && TooltipSettings.customBorder) {
            try { TooltipArtwork.draw(g, getBackgroundSprite(style), x, y, w, h, false, true); return; }
            catch (RuntimeException e) { Io.failed("Drawing a tooltip border", e); }
        }
        original.call(g, pipeline, sprite, x, y, w, h);
    }
}
