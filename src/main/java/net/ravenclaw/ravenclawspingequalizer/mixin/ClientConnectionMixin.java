package net.ravenclaw.ravenclawspingequalizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkState;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.text.Text;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;
import net.ravenclaw.ravenclawspingequalizer.bridge.PingEqualizerConnectionBridge;
import net.ravenclaw.ravenclawspingequalizer.net.PingEqualizerChannelHandler;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin implements PingEqualizerConnectionBridge {

    @Shadow
    private Channel channel;

    @Unique
    private PingEqualizerChannelHandler pingEqualizer$channelHandler;

    @Unique
    private int pingEqualizer$tickCounter = 0;

    @Inject(method = "channelActive", at = @At("TAIL"))
    private void pingEqualizer$onChannelActive(io.netty.channel.ChannelHandlerContext context, CallbackInfo ci) {
        try {
            ChannelPipeline pipeline = context.pipeline();

            if (pipeline.get(PingEqualizerChannelHandler.HANDLER_NAME) != null) {
                pipeline.remove(PingEqualizerChannelHandler.HANDLER_NAME);
            }

            pingEqualizer$channelHandler = new PingEqualizerChannelHandler();

            if (pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", PingEqualizerChannelHandler.HANDLER_NAME, pingEqualizer$channelHandler);
            } else {
                pipeline.addLast(PingEqualizerChannelHandler.HANDLER_NAME, pingEqualizer$channelHandler);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("PingEqualizer")
                .warn("Failed to install channel handler: {}", e.getMessage());
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void pingEqualizer$onDisconnect(Text reason, CallbackInfo ci) {
        PingEqualizerState.getInstance().setOff();
        if (pingEqualizer$channelHandler != null) {
            pingEqualizer$channelHandler.setActive(false);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void pingEqualizer$onTick(CallbackInfo ci) {
        if (channel == null || !channel.isOpen()) {
            return;
        }

        if (pingEqualizer$tickCounter++ % 20 != 0) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(PingEqualizerChannelHandler.HANDLER_NAME) == null && pingEqualizer$channelHandler != null) {
            try {
                if (pipeline.get("packet_handler") != null) {
                    pipeline.addBefore("packet_handler", PingEqualizerChannelHandler.HANDLER_NAME, pingEqualizer$channelHandler);
                    org.slf4j.LoggerFactory.getLogger("PingEqualizer")
                        .info("Reinstalled channel handler after it was removed");
                }
            } catch (Exception ignored) {}
        }
    }

    @Inject(method = "transitionInbound", at = @At("HEAD"))
    private void pingEqualizer$onTransitionInbound(NetworkState<?> state, PacketListener listener, CallbackInfo ci) {
        pingEqualizer$handlePhaseTransition(state.id());
    }

    @Inject(method = "transitionOutbound", at = @At("HEAD"))
    private void pingEqualizer$onTransitionOutbound(NetworkState<?> state, CallbackInfo ci) {
        pingEqualizer$handlePhaseTransition(state.id());
    }

    @Unique
    private void pingEqualizer$handlePhaseTransition(NetworkPhase phase) {
        boolean playPhase = phase == NetworkPhase.PLAY;
        if (pingEqualizer$channelHandler != null) {
            pingEqualizer$channelHandler.setActive(playPhase);
        }
        if (!playPhase) {
            PingEqualizerState.getInstance().suspendForProtocolChange();
        }
    }

    @Override
    public void pingEqualizer$signalPlayPhaseEntry() {
        if (pingEqualizer$channelHandler != null) {
            pingEqualizer$channelHandler.setActive(true);
        }
        PingEqualizerState.getInstance().prepareForNewPlaySession();
    }
}
