package com.example.forkworld.mixin;

import com.example.forkworld.PacketHolder;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link PacketHolder} park the real server's packets while the player is inside the
 * sandbox. Runs on the netty thread, before the packet is scheduled onto the client thread.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void forkworld$hold(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (PacketHolder.offer((Connection) (Object) this, packet)) {
            ci.cancel();
        }
    }
}
