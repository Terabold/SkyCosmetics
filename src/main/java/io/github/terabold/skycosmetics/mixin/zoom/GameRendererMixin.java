package io.github.terabold.skycosmetics.mixin.zoom;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.zoom.Zoom;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @ModifyExpressionValue(method = "bobView", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/level/CameraEntityRenderState;bob:F"), require = 0)
    private float skycosmetics$bob(float original) {
        try { return original * Zoom.bobbing(); }
        catch (RuntimeException e) { Io.failed("Scaling zoom view bobbing", e); return original; }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void skycosmetics$hand(CallbackInfo ci) {
        try { if (Zoom.hideHand()) ci.cancel(); }
        catch (RuntimeException e) { Io.failed("Hiding zoom hand", e); }
    }
}
