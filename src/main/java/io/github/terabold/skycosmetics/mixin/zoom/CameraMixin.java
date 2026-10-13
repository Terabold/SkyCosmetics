package io.github.terabold.skycosmetics.mixin.zoom;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.zoom.Zoom;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
abstract class CameraMixin {
    @Shadow private float oldFovModifier;
    @Shadow private float fovModifier;

    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"), require = 0)
    private float skycosmetics$zoom(float original, float partialTick) {
        try { return Zoom.worldFov(original, Mth.lerp(partialTick, oldFovModifier, fovModifier)); }
        catch (RuntimeException e) { Zoom.reset(); Io.failed("Calculating zoom FOV", e); return original; }
    }

    @ModifyReturnValue(method = "calculateHudFov", at = @At("RETURN"), require = 0)
    private float skycosmetics$hand(float original) {
        try { return Zoom.handFov(original); }
        catch (RuntimeException e) { Io.failed("Calculating zoom hand FOV", e); return original; }
    }

}
