package io.github.terabold.skycosmetics.mixin;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.data.PreviewRecorder;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link PreviewRecorder} watch a skin preview stand's head change. Read
 * only and never cancels; at TAIL it runs on the main thread, after the packet
 * has been applied (the netty-thread pass throws out before reaching it). An
 * exception here would count as a bad packet and disconnect you, so none
 * gets out.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleSetEquipment", at = @At("TAIL"))
    private void skycosmetics$equipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
        try {
            PreviewRecorder.onEquipment(packet);
        } catch (RuntimeException e) {
            Io.failed("Recording a skin preview", e);
        }
    }
}
