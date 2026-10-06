package io.github.terabold.skycosmetics.mixin.hand;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.hand.HandSwing;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the Hand feature's own swing timer see your swings: every swing of yours (clicks, abilities, a swing
 * the server shows) passes here. Observe only: the swing is never changed, cancelled, started or sent, and
 * other players and mobs are ignored.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"), require = 0)
    private void skycosmetics$swung(InteractionHand hand, boolean sendToSwingingEntity, CallbackInfo ci) {
        try {
            if ((Object) this == Minecraft.getInstance().player) HandSwing.swung(hand);
        } catch (RuntimeException e) {
            Io.failed("Watching your swing", e);
        }
    }
}
