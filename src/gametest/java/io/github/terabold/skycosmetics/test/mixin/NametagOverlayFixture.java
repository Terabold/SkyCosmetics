package io.github.terabold.skycosmetics.test.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Mimics another mod forcing shadows and disabling native backgrounds in both label passes. */
@Mixin(NameTagFeatureRenderer.class)
abstract class NametagOverlayFixture {
    @WrapOperation(method = "renderTranslucent", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"))
    private void fixture$otherMod(Font font, Component text, float x, float y, int color, boolean shadow,
        Matrix4fc pose, MultiBufferSource buffers, Font.DisplayMode mode, int background, int light, Operation<Void> original) {
        if (text.getString().equals("Depth test")) { shadow = true; background = 0; }
        original.call(font, text, x, y, color, shadow, pose, buffers, mode, background, light);
    }
}
