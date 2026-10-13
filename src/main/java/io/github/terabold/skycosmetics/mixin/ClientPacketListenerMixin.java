package io.github.terabold.skycosmetics.mixin;

import io.github.terabold.skycosmetics.Io;
import io.github.terabold.skycosmetics.data.PreviewRecorder;
import io.github.terabold.skycosmetics.data.TimingLearner;
import io.github.terabold.skycosmetics.data.SkinLearner;
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
        for (var slot : packet.getSlots()) skycosmetics$learn(slot.getSecond());
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
        skycosmetics$learn(packet.getItem());
        try { PreviewRecorder.onSlot(packet); }
        catch (RuntimeException e) { Io.failed("Learning a preview choice", e); }
        try {
            TimingLearner.onSlot(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void skycosmetics$openScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        try { PreviewRecorder.onOpenScreen(packet); }
        catch (RuntimeException e) { Io.failed("Following a skin preview menu", e); }
        try {
            TimingLearner.onOpenScreen(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void skycosmetics$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        var items = packet.items();
        for (int i = 0; i < Math.min(512, items.size()); i++) skycosmetics$learn(items.get(i));
        try { PreviewRecorder.onContent(packet); }
        catch (RuntimeException e) { Io.failed("Learning preview choices", e); }
        try {
            TimingLearner.onContent(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"))
    private void skycosmetics$inventory(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        skycosmetics$learn(packet.contents());
        try {
            TimingLearner.onInventory(packet);
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void skycosmetics$entityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        try { PreviewRecorder.onEntityData(packet); }
        catch (RuntimeException e) { Io.failed("Recording a display preview", e); }
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
            PreviewRecorder.onTime();
        } catch (RuntimeException e) {
            Io.failed("Timing an animated head", e);
        }
    }

    private static void skycosmetics$learn(net.minecraft.world.item.ItemStack stack) {
        try { SkinLearner.observe(stack); }
        catch (RuntimeException e) { Io.failed("Learning an incoming skin choice", e); }
    }
}
