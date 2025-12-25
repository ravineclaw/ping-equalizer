package net.ravenclaw.ravenclawspingequalizer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkSide;
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

    @Shadow
    @Final
    private NetworkSide side;

    @Unique
    private PingEqualizerChannelHandler pingEqualizer$channelHandler;

    @Unique
    private int pingEqualizer$tickCounter = 0;

    @Unique
    private boolean pingEqualizer$enteredPlay = false;

    @Unique
    private boolean pingEqualizer$reconfiguring = false;

    @Unique
    private NetworkPhase pingEqualizer$lastPhase = null;

    @Unique
    private boolean pingEqualizer$isClientboundConnection() {
        return this.side == NetworkSide.CLIENTBOUND;
    }

    @Inject(method = "addFlowControlHandler", at = @At("RETURN"), require = 0)
    private void pingEqualizer$onAddFlowControl(ChannelPipeline pipeline, CallbackInfo ci) {
        if (!pingEqualizer$isClientboundConnection()) {
            return;
        }
        try {
            pingEqualizer$ensureHandler(pipeline);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("PingEqualizer")
                .warn("Failed to install channel handler at pipeline init: {}", e.getMessage());
        }
    }

    @Inject(method = "channelActive", at = @At("TAIL"), require = 0)
    private void pingEqualizer$onChannelActive(io.netty.channel.ChannelHandlerContext context, CallbackInfo ci) {
        if (!pingEqualizer$isClientboundConnection()) {
            return;
        }
        try {
            pingEqualizer$ensureHandler(context.pipeline());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger("PingEqualizer")
                .warn("Failed to install channel handler: {}", e.getMessage());
        }
    }

    @Inject(method = "disconnect(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), require = 0)
    private void pingEqualizer$onDisconnect(Text reason, CallbackInfo ci) {
        if (!pingEqualizer$isClientboundConnection()) {
            return;
        }
        PingEqualizerState.getInstance().setOff();
        pingEqualizer$enteredPlay = false;
        if (pingEqualizer$channelHandler != null) {
            pingEqualizer$channelHandler.setActive(false);
        }
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void pingEqualizer$onTick(CallbackInfo ci) {
        if (!pingEqualizer$isClientboundConnection()) {
            return;
        }
        if (channel == null || !channel.isOpen()) {
            return;
        }

        if (pingEqualizer$tickCounter++ % 20 != 0) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(PingEqualizerChannelHandler.HANDLER_NAME) == null) {
            try {
                if (pingEqualizer$ensureHandler(pipeline)) {
                org.slf4j.LoggerFactory.getLogger("PingEqualizer")
                    .info("Reinstalled channel handler after it was removed");
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Inject(method = "transitionInbound", at = @At("HEAD"), require = 0)
    private void pingEqualizer$onTransitionInbound(NetworkState<?> state, PacketListener listener, CallbackInfo ci) {
        if (!pingEqualizer$isClientboundConnection()) {
            return;
        }
        pingEqualizer$handlePhaseTransition(state.id());
    }

    @Inject(method = "transitionOutbound", at = @At("HEAD"), require = 0)
    private void pingEqualizer$onTransitionOutbound(NetworkState<?> state, CallbackInfo ci) {
        if (!pingEqualizer$isClientboundConnection()) {
            return;
        }
        pingEqualizer$handlePhaseTransition(state.id());
    }

    @Unique
    private void pingEqualizer$handlePhaseTransition(NetworkPhase phase) {
        if (phase == NetworkPhase.PLAY) {
            boolean reset = !pingEqualizer$reconfiguring;
            pingEqualizer$enterPlay(reset);
            pingEqualizer$reconfiguring = false;
        } else {
            if (phase == NetworkPhase.CONFIGURATION && pingEqualizer$lastPhase == NetworkPhase.PLAY) {
                pingEqualizer$reconfiguring = true;
            }
            pingEqualizer$leavePlay(phase != NetworkPhase.CONFIGURATION);
        }
        pingEqualizer$lastPhase = phase;
    }

    @Override
    public void pingEqualizer$signalPlayPhaseEntry() {
        if (!pingEqualizer$isClientboundConnection()) {
            return;
        }
        pingEqualizer$enterPlay(true);
    }

    @Unique
    private void pingEqualizer$enterPlay(boolean resetState) {
        if (!pingEqualizer$enteredPlay && resetState) {
            pingEqualizer$enteredPlay = true;
            PingEqualizerState.getInstance().prepareForNewPlaySession();
        } else if (resetState) {
            PingEqualizerState.getInstance().prepareForNewPlaySession();
        }
        if (pingEqualizer$channelHandler != null) {
            pingEqualizer$channelHandler.setActive(true);
        }
    }

    @Unique
    private void pingEqualizer$leavePlay(boolean suspendState) {
        if (pingEqualizer$channelHandler != null) {
            pingEqualizer$channelHandler.setActive(false);
        }
        if (pingEqualizer$enteredPlay && suspendState) {
            PingEqualizerState.getInstance().suspendForProtocolChange();
            pingEqualizer$enteredPlay = false;
        }
    }

    @Unique
    private boolean pingEqualizer$ensureHandler(ChannelPipeline pipeline) {
        if (pipeline == null) {
            return false;
        }
        PingEqualizerChannelHandler existing = (PingEqualizerChannelHandler) pipeline.get(PingEqualizerChannelHandler.HANDLER_NAME);
        if (existing != null) {
            pingEqualizer$channelHandler = existing;
            return false;
        }
        if (pingEqualizer$channelHandler == null) {
            pingEqualizer$channelHandler = new PingEqualizerChannelHandler();
        }
        String anchor = pingEqualizer$resolvePacketHandlerName(pipeline);
        if (anchor != null) {
            pipeline.addBefore(anchor, PingEqualizerChannelHandler.HANDLER_NAME, pingEqualizer$channelHandler);
        } else {
            pipeline.addLast(PingEqualizerChannelHandler.HANDLER_NAME, pingEqualizer$channelHandler);
        }
        return true;
    }

    @Unique
    private String pingEqualizer$resolvePacketHandlerName(ChannelPipeline pipeline) {
        io.netty.channel.ChannelHandlerContext context = pipeline.context((ChannelHandler)(Object)this);
        if (context != null) {
            return context.name();
        }
        if (pipeline.get("packet_handler") != null) {
            return "packet_handler";
        }
        return null;
    }
}
