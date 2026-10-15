package io.github.terabold.skycosmetics.mixin.zoom;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.zoom.Zoom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.util.SmoothDouble;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
    @Shadow @Final private SmoothDouble smoothTurnX;
    @Shadow @Final private SmoothDouble smoothTurnY;
    @Unique private boolean skycosmetics$wasSmoothing;
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, require = 0)
    private void skycosmetics$wheel(long window, double x, double y, CallbackInfo ci) {
        try {
            if (window == Minecraft.getInstance().getWindow().handle() && Zoom.scroll(y)) ci.cancel();
        } catch (RuntimeException e) { Zoom.reset(); Io.failed("Scrolling zoom", e); }
    }

    @ModifyExpressionValue(method = "turnPlayer", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;smoothCamera:Z"), require = 0)
    private boolean skycosmetics$cinematic(boolean original) {
        try {
            Zoom.update();
            // While zoomed only Zoom.smooth smooths (Cinematic included), never vanilla's camera on top of it.
            boolean zoomed = Zoom.visible();
            if (zoomed != skycosmetics$wasSmoothing) { smoothTurnX.reset(); smoothTurnY.reset(); }
            skycosmetics$wasSmoothing = zoomed;
            return !zoomed && original;
        }
        catch (RuntimeException e) { Zoom.reset(); Io.failed("Checking cinematic zoom", e); return original; }
    }

    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), require = 0)
    private void skycosmetics$turn(LocalPlayer player, double x, double y, Operation<Void> original, @Local(argsOnly = true) double dt) {
        double outX = x, outY = y;
        try {
            double scale = Zoom.sensitivity();
            outX = Zoom.smooth(x * scale, dt, true); outY = Zoom.smooth(y * scale, dt, false);
        } catch (RuntimeException e) { Zoom.reset(); Io.failed("Applying zoom sensitivity", e); outX = x; outY = y; }
        original.call(player, outX, outY);
    }
}
