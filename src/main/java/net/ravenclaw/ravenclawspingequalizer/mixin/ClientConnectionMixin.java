package net.ravenclaw.ravenclawspingequalizer.mixin;

import java.lang.reflect.Method;
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
    @Unique
    private static final Method LEGACY_SEND_PACKET_CALLBACKS = findLegacyPacketCallbacksMethod(2);
    @Unique
    private static final Method LEGACY_SEND_PACKET_CALLBACKS_FLUSH = findLegacyPacketCallbacksMethod(3);

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rpe$onSendSimple(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof QueryPingC2SPacket qp) {
            PingEqualizerState.getInstance().onPingSent(qp.getStartTime());
        }
        if (rpe$handleSend(packet, () -> self().send(packet))) {
            ci.cancel();
        }
    }


    private boolean rpe$handleSend(Packet<?> packet, Runnable sendAction) {
        if (isDelayedSend.get() || rpe$suppressDelays) {
            return false;
        }

        PingEqualizerState state = PingEqualizerState.getInstance();
        if (state.getMode() == PingEqualizerState.Mode.OFF) {
            return false;
        }

        if (rpe$shouldBypassSend(packet)) {
            return false;
        }

        if (rpe$suppressDelays) {
            return false;
        }

        long delay = state.getOutboundDelayPortion();
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
                            isDelayedSend.set(false);
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
        if (state.getMode() == PingEqualizerState.Mode.OFF) {
            return;
        }

        if (rpe$shouldBypassReceive(packet)) {
            return;
        }

        if (rpe$suppressDelays) {
            return;
        }

        long delay = state.getInboundDelayPortion();
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
                            isDelayedReceive.set(false);
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

    private static boolean rpe$shouldBypassSend(Packet<?> packet) {
        if (packet.transitionsNetworkState()) {
            return true;
        }
        if (packet instanceof CustomPayloadC2SPacket customPayload) {
            return rpe$isPlayerLoadedPayload(customPayload.payload());
        }
        return false;
    }

    private static boolean rpe$shouldBypassReceive(Packet<?> packet) {
        if (packet.transitionsNetworkState()) {
            return true;
        }
        if (packet instanceof CustomPayloadS2CPacket customPayload) {
            return rpe$isPlayerLoadedPayload(customPayload.payload());
        }
        return false;
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

    private static Method findLegacyPacketCallbacksMethod(int parameterCount) {
        try {
            for (Method method : ClientConnection.class.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == parameterCount
                        && Packet.class.isAssignableFrom(parameters[0])
                        && parameters[1] == PacketCallbacks.class
                        && (parameterCount == 2 || parameters[2] == boolean.class)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void invokeLegacySend(Packet<?> packet, PacketCallbacks callbacks) {
        Method method = LEGACY_SEND_PACKET_CALLBACKS;
        if (method == null) {
            throw new IllegalStateException("Legacy send(Packet, PacketCallbacks) is unavailable in this runtime");
        }
        try {
            method.invoke(self(), packet, callbacks);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke legacy send(Packet, PacketCallbacks)", e);
        }
    }

    private void invokeLegacySend(Packet<?> packet, PacketCallbacks callbacks, boolean flush) {
        Method method = LEGACY_SEND_PACKET_CALLBACKS_FLUSH;
        if (method == null) {
            throw new IllegalStateException("Legacy send(Packet, PacketCallbacks, boolean) is unavailable in this runtime");
        }
        try {
            method.invoke(self(), packet, callbacks, flush);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke legacy send(Packet, PacketCallbacks, boolean)", e);
        }
    }
}
