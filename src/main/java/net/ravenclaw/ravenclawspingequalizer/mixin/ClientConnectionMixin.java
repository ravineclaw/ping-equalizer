package net.ravenclaw.ravenclawspingequalizer.mixin;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.text.Text;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;
import net.ravenclaw.ravenclawspingequalizer.bridge.PingEqualizerConnectionBridge;
import net.ravenclaw.ravenclawspingequalizer.net.DelayedPacketTask;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin implements PingEqualizerConnectionBridge {

    @Shadow
    private Channel channel;

    @Unique
    private final ThreadLocal<Boolean> isDelayedSend = ThreadLocal.withInitial(() -> false);
    @Unique
    private final ConcurrentLinkedQueue<DelayedPacketTask> sendQueue = new ConcurrentLinkedQueue<>();
    @Unique
    private final AtomicBoolean processingSendQueue = new AtomicBoolean(false);

    @Unique
    private final ThreadLocal<Boolean> isDelayedReceive = ThreadLocal.withInitial(() -> false);
    @Unique
    private final ConcurrentLinkedQueue<DelayedPacketTask> receiveQueue = new ConcurrentLinkedQueue<>();
    @Unique
    private final AtomicBoolean processingReceiveQueue = new AtomicBoolean(false);
    @Unique
    private volatile boolean rpe$suppressDelays = false;
    @Unique
    private ChannelHandlerContext rpe$lastReceiveContext;
    @Unique
    private static final long PRECISION_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    @Unique
    private static final long MIN_RESCHEDULE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    @Unique
    private static final CustomPayload.Id<?> PLAYER_LOADED_PAYLOAD_ID = CustomPayload.id("player_loaded");

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rpe$onSendSimple(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof QueryPingC2SPacket qp) {
            PingEqualizerState.getInstance().onPingSent(qp.getStartTime());
        }
        if (rpe$handleSend(packet, () -> self().send(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rpe$onSendWithCallbacks(Packet<?> packet, PacketCallbacks callbacks, CallbackInfo ci) {
        if (rpe$handleSend(packet, () -> self().send(packet, callbacks))) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rpe$onSendWithCallbacksAndFlush(Packet<?> packet, PacketCallbacks callbacks, boolean flush, CallbackInfo ci) {
        if (rpe$handleSend(packet, () -> self().send(packet, callbacks, flush))) {
            ci.cancel();
        }
    }

    private boolean rpe$handleSend(Packet<?> packet, Runnable sendAction) {
        if (isDelayedSend.get() || rpe$suppressDelays) {
            return false;
        }

        PingEqualizerState state = PingEqualizerState.getInstance();
        boolean shouldBypass = rpe$shouldBypass(packet);

        if (shouldBypass && sendQueue.isEmpty()) {
            return false;
        }

        if (state.getMode() == PingEqualizerState.Mode.OFF && sendQueue.isEmpty()) {
            return false;
        }

        long delay = state.getOutboundDelayPortion();
        if (shouldBypass) {
            delay = 0;
        }

        if (delay <= 0 && sendQueue.isEmpty()) {
            if (packet instanceof QueryPingC2SPacket pingPacket) {
                state.onPingActuallySent(pingPacket.getStartTime());
            }
            return false;
        }
        if (!self().isOpen() || channel == null || channel.eventLoop() == null) {
            return false;
        }

        long sendTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        sendQueue.offer(new DelayedPacketTask(packet, sendAction, sendTime));
        if (packet instanceof QueryPingC2SPacket qp) {
            PingEqualizerState.getInstance().recordPingOutboundDelay(qp.getStartTime(), delay);
        }
        rpe$processSendQueue();
        return true;
    }

    private void rpe$processSendQueue() {
        if (!processingSendQueue.compareAndSet(false, true)) {
            return;
        }

        if (channel == null || channel.eventLoop() == null) {
            processingSendQueue.set(false);
            return;
        }

        if (channel.eventLoop().inEventLoop()) {
            rpe$drainSendQueue();
        } else {
            channel.eventLoop().execute(this::rpe$drainSendQueue);
        }
    }

    private void rpe$drainSendQueue() {
        try {
            while (true) {
                DelayedPacketTask task = sendQueue.peek();
                if (task == null) {
                    processingSendQueue.set(false);
                    return;
                }

                long delayNanos = task.sendTimeNanos - System.nanoTime();
                if (delayNanos <= 0) {
                    sendQueue.poll();
                    if (self().isOpen()) {
                        Runnable sendAction = task.sendAction;
                        if (sendAction == null) {
                            processingSendQueue.set(false);
                            return;
                        }

                        isDelayedSend.set(true);
                        try {
                            if (task.packet instanceof QueryPingC2SPacket pingPacket) {
                                PingEqualizerState.getInstance().onPingActuallySent(pingPacket.getStartTime());
                            }
                            sendAction.run();
                        } finally {
                            isDelayedSend.remove();
                        }
                    }
                    continue;
                }

                if (delayNanos <= PRECISION_WINDOW_NANOS) {
                    rpe$spinWait(delayNanos);
                    continue;
                }

                long waitNanos = Math.max(delayNanos - PRECISION_WINDOW_NANOS, MIN_RESCHEDULE_NANOS);
                channel.eventLoop().schedule(this::rpe$processSendQueue, waitNanos, TimeUnit.NANOSECONDS);
                processingSendQueue.set(false);
                return;
            }
        } catch (Exception e) {
            processingSendQueue.set(false);
            throw e;
        }
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void rpe$onDisconnect(Text reason, CallbackInfo ci) {
        PingEqualizerState.getInstance().setOff();
    }

    @Shadow
    protected abstract void channelRead0(ChannelHandlerContext ctx, Packet<?> msg);

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void rpe$onChannelRead(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        rpe$lastReceiveContext = context;
        if (isDelayedReceive.get() || rpe$suppressDelays) {
            return;
        }

        PingEqualizerState state = PingEqualizerState.getInstance();
        boolean shouldBypass = rpe$shouldBypass(packet);

        if (shouldBypass && receiveQueue.isEmpty()) {
            return;
        }

        if (state.getMode() == PingEqualizerState.Mode.OFF && receiveQueue.isEmpty()) {
            return;
        }

        long delay = state.getInboundDelayPortion();
        if (shouldBypass) {
            delay = 0;
        }

        if ((delay <= 0 && receiveQueue.isEmpty()) || !self().isOpen() || context.executor() == null) {
            if (packet instanceof PingResultS2CPacket pingResult) {
                state.onPingArrived(pingResult.startTime());
                state.handlePingResult(pingResult);
            }
            return;
        }

        ci.cancel();

        long deliverTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        if (packet instanceof PingResultS2CPacket ping) {
            PingEqualizerState.getInstance().recordPingInboundDelay(ping.startTime(), delay);
        }
        receiveQueue.offer(new DelayedPacketTask(packet, null, deliverTime));
        rpe$processReceiveQueue(context);
    }

    private void rpe$processReceiveQueue(ChannelHandlerContext context) {
        if (!processingReceiveQueue.compareAndSet(false, true)) {
            return;
        }

        if (context.executor() == null) {
            processingReceiveQueue.set(false);
            return;
        }

        Runnable runner = () -> rpe$drainReceiveQueue(context);
        if (context.executor().inEventLoop()) {
            runner.run();
        } else {
            context.executor().execute(runner);
        }
    }

    private void rpe$drainReceiveQueue(ChannelHandlerContext context) {
        try {
            while (true) {
                DelayedPacketTask task = receiveQueue.peek();
                if (task == null) {
                    processingReceiveQueue.set(false);
                    return;
                }

                long delayNanos = task.sendTimeNanos - System.nanoTime();
                if (delayNanos <= 0) {
                    receiveQueue.poll();
                    if (self().isOpen()) {
                        isDelayedReceive.set(true);
                        try {
                            if (task.packet instanceof PingResultS2CPacket pingResult) {
                                PingEqualizerState.getInstance().recordPingInboundDelay(pingResult.startTime(), PingEqualizerState.getInstance().getInboundDelayPortion());
                                PingEqualizerState.getInstance().onPingArrived(pingResult.startTime());
                                PingEqualizerState.getInstance().handlePingResult(pingResult);
                            }
                            this.channelRead0(context, task.packet);
                        } finally {
                            isDelayedReceive.remove();
                        }
                    }
                } else {
                    context.executor().schedule(() -> {
                        processingReceiveQueue.set(false);
                        rpe$processReceiveQueue(context);
                    }, delayNanos, TimeUnit.NANOSECONDS);
                    return;
                }
            }
        } catch (Exception e) {
            processingReceiveQueue.set(false);
            throw e;
        }
    }

    private static void rpe$spinWait(long nanos) {
        long deadline = System.nanoTime() + nanos;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            Thread.onSpinWait();
            if (remaining > TimeUnit.MILLISECONDS.toNanos(1)) {
                Thread.yield();
            }
        }
    }

    private static boolean rpe$shouldBypass(Packet<?> packet) {
        if (packet.transitionsNetworkState()) {
            return true;
        }
        CustomPayload payload = null;
        if (packet instanceof CustomPayloadC2SPacket c2s) {
            payload = c2s.payload();
        } else if (packet instanceof CustomPayloadS2CPacket s2c) {
            payload = s2c.payload();
        }
        
        return rpe$isPlayerLoadedPayload(payload);
    }

    private static boolean rpe$isPlayerLoadedPayload(CustomPayload payload) {
        return payload != null && payload.getId().equals(PLAYER_LOADED_PAYLOAD_ID);
    }

    private ClientConnection self() {
        return (ClientConnection)(Object)this;
    }

    @Inject(method = "transitionInbound", at = @At("HEAD"))
    private void rpe$onTransitionInbound(NetworkState<?> state, PacketListener listener, CallbackInfo ci) {
        rpe$handlePhaseTransition(state.id());
    }

    @Inject(method = "transitionOutbound", at = @At("HEAD"))
    private void rpe$onTransitionOutbound(NetworkState<?> state, CallbackInfo ci) {
        rpe$handlePhaseTransition(state.id());
    }

    @Unique
    private void rpe$handlePhaseTransition(NetworkPhase phase) {
        boolean playPhase = phase == NetworkPhase.PLAY;
        rpe$suppressDelays = !playPhase;
        if (!playPhase) {
            rpe$flushQueuesNow();
            PingEqualizerState.getInstance().suspendForProtocolChange();
        }
    }

    @Override
    public void rpe$signalPlayPhaseEntry() {
        rpe$suppressDelays = false;
        PingEqualizerState.getInstance().prepareForNewPlaySession();
    }

    @Unique
    private void rpe$flushQueuesNow() {
        rpe$flushSendQueueNow();
        rpe$flushReceiveQueueNow();
    }

    @Unique
    private void rpe$flushSendQueueNow() {
        if (channel == null || channel.eventLoop() == null) {
            sendQueue.clear();
            processingSendQueue.set(false);
            return;
        }
        Runnable flush = () -> {
            if (!sendQueue.isEmpty()) {
                sendQueue.clear();
            }
            processingSendQueue.set(false);
        };
        if (channel.eventLoop().inEventLoop()) {
            flush.run();
        } else {
            channel.eventLoop().execute(flush);
        }
    }

    @Unique
    private void rpe$flushReceiveQueueNow() {
        ChannelHandlerContext context = rpe$lastReceiveContext;
        if (context == null || context.executor() == null) {
            receiveQueue.clear();
            processingReceiveQueue.set(false);
            return;
        }
        Runnable flush = () -> {
            if (!receiveQueue.isEmpty()) {
                receiveQueue.clear();
            }
            processingReceiveQueue.set(false);
        };
        if (context.executor().inEventLoop()) {
            flush.run();
        } else {
            context.executor().execute(flush);
        }
    }
}
