package io.github.terabold.skycosmetics.mixin;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.data.PreviewRecorder;
import io.github.terabold.skycosmetics.data.TimingLearner;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link PreviewRecorder} watch a skin preview stand's head change, and
 * {@link TimingLearner} time the frames of animated heads (and the server's
 * clock, for lag). Read only and never cancels; at TAIL each runs on the main
 * thread, after the packet has been applied (the netty-thread pass throws out
 * before reaching it). An exception here would count as a bad packet and
 * disconnect you, so none gets out.
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
        try {
            TimingLearner.onEquipment(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void skycosmetics$slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        try {
            TimingLearner.onSlot(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void skycosmetics$openScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        try {
            TimingLearner.onOpenScreen(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void skycosmetics$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        try {
            TimingLearner.onContent(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"))
    private void skycosmetics$inventory(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        try {
            TimingLearner.onInventory(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void skycosmetics$entityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        try {
            TimingLearner.onEntityData(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void skycosmetics$time(ClientboundSetTimePacket packet, CallbackInfo ci) {
        try {
            TimingLearner.onTime(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }
}
