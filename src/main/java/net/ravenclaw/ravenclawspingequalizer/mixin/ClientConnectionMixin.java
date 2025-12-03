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
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
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
    private volatile boolean pingEqualizer$suppressDelays = false;
    @Unique
    private ChannelHandlerContext pingEqualizer$lastReceiveContext;
    @Unique
    private static final long PRECISION_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    @Unique
    private static final long MIN_RESCHEDULE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pingEqualizer$onSend(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof QueryPingC2SPacket qp) {
            PingEqualizerState.getInstance().onPingSent(qp.getStartTime());
        }
        if (pingEqualizer$handleSend(packet, () -> self().send(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pingEqualizer$onSendWithCallbacks(Packet<?> packet, PacketCallbacks callbacks, CallbackInfo ci) {
        if (pingEqualizer$handleSend(packet, () -> self().send(packet, callbacks))) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void pingEqualizer$onSendWithCallbacksAndFlush(Packet<?> packet, PacketCallbacks callbacks, boolean flush, CallbackInfo ci) {
        if (pingEqualizer$handleSend(packet, () -> self().send(packet, callbacks, flush))) {
            ci.cancel();
        }
    }

    private boolean pingEqualizer$handleSend(Packet<?> packet, Runnable sendAction) {
        if (isDelayedSend.get() || pingEqualizer$suppressDelays) {
            return false;
        }

        PingEqualizerState state = PingEqualizerState.getInstance();
        boolean shouldBypass = pingEqualizer$shouldBypass(packet);

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
        pingEqualizer$processSendQueue();
        return true;
    }

    private void pingEqualizer$processSendQueue() {
        if (!processingSendQueue.compareAndSet(false, true)) {
            return;
        }

        if (channel == null || channel.eventLoop() == null) {
            processingSendQueue.set(false);
            return;
        }

        if (channel.eventLoop().inEventLoop()) {
            pingEqualizer$drainSendQueue();
        } else {
            channel.eventLoop().execute(this::pingEqualizer$drainSendQueue);
        }
    }

    private void pingEqualizer$drainSendQueue() {
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
                    pingEqualizer$spinWait(delayNanos);
                    continue;
                }

                long waitNanos = Math.max(delayNanos - PRECISION_WINDOW_NANOS, MIN_RESCHEDULE_NANOS);
                channel.eventLoop().schedule(this::pingEqualizer$processSendQueue, waitNanos, TimeUnit.NANOSECONDS);
                processingSendQueue.set(false);
                return;
            }
        } catch (Exception e) {
            processingSendQueue.set(false);
            throw e;
        }
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void pingEqualizer$onDisconnect(Text reason, CallbackInfo ci) {
        PingEqualizerState.getInstance().setOff();
    }

    @Shadow
    protected abstract void channelRead0(ChannelHandlerContext ctx, Packet<?> msg);

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void pingEqualizer$onChannelRead(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        pingEqualizer$lastReceiveContext = context;
        if (isDelayedReceive.get() || pingEqualizer$suppressDelays) {
            return;
        }

        PingEqualizerState state = PingEqualizerState.getInstance();
        boolean shouldBypass = pingEqualizer$shouldBypass(packet);

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
        pingEqualizer$processReceiveQueue(context);
    }

    private void pingEqualizer$processReceiveQueue(ChannelHandlerContext context) {
        if (!processingReceiveQueue.compareAndSet(false, true)) {
            return;
        }

        if (context.executor() == null) {
            processingReceiveQueue.set(false);
            return;
        }

        Runnable runner = () -> pingEqualizer$drainReceiveQueue(context);
        if (context.executor().inEventLoop()) {
            runner.run();
        } else {
            context.executor().execute(runner);
        }
    }

    private void pingEqualizer$drainReceiveQueue(ChannelHandlerContext context) {
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
                        pingEqualizer$processReceiveQueue(context);
                    }, delayNanos, TimeUnit.NANOSECONDS);
                    return;
                }
            }
        } catch (Exception e) {
            processingReceiveQueue.set(false);
            throw e;
        }
    }

    private static void pingEqualizer$spinWait(long nanos) {
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

    private static boolean pingEqualizer$shouldBypass(Packet<?> packet) {
        return packet.transitionsNetworkState();
    }

    private ClientConnection self() {
        return (ClientConnection)(Object)this;
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
        pingEqualizer$suppressDelays = !playPhase;
        if (!playPhase) {
            pingEqualizer$flushQueuesNow();
            PingEqualizerState.getInstance().suspendForProtocolChange();
        }
    }

    @Override
    public void pingEqualizer$signalPlayPhaseEntry() {
        pingEqualizer$suppressDelays = false;
        PingEqualizerState.getInstance().prepareForNewPlaySession();
    }

    @Unique
    private void pingEqualizer$flushQueuesNow() {
        pingEqualizer$flushSendQueueNow();
        pingEqualizer$flushReceiveQueueNow();
    }

    @Unique
    private void pingEqualizer$flushSendQueueNow() {
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
    private void pingEqualizer$flushReceiveQueueNow() {
        ChannelHandlerContext context = pingEqualizer$lastReceiveContext;
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
