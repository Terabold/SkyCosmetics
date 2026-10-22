package io.github.terabold.skycosmetics.test.mixin;

import io.github.terabold.skycosmetics.test.PacketLog;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every packet the test client sends to its server, counted by type: the run checks that none is chat or a command. */
@Mixin(Connection.class)
public abstract class PacketLogFixture {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void skycosmeticsTest$log(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        PacketLog.sent(packet);
    }
}
