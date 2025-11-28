package net.ravenclaw.ravenclawspingequalizer.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.text.Text;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;
import net.ravenclaw.ravenclawspingequalizer.net.DelayedPacketTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {

    @Shadow
    private Channel channel;

    private final ThreadLocal<Boolean> isDelayedSend = ThreadLocal.withInitial(() -> false);
    private final ConcurrentLinkedQueue<DelayedPacketTask> sendQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean processingSendQueue = new AtomicBoolean(false);

    private final ThreadLocal<Boolean> isDelayedReceive = ThreadLocal.withInitial(() -> false);
    private final ConcurrentLinkedQueue<DelayedPacketTask> receiveQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean processingReceiveQueue = new AtomicBoolean(false);
    private static final long PRECISION_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long MIN_RESCHEDULE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    // The client must forward minecraft:player_loaded immediately so newer servers finish login.
    private static final CustomPayload.Id<?> PLAYER_LOADED_PAYLOAD_ID = CustomPayload.id("player_loaded");
    private static final Method LEGACY_SEND_PACKET_CALLBACKS = findLegacyPacketCallbacksMethod(2);
    private static final Method LEGACY_SEND_PACKET_CALLBACKS_FLUSH = findLegacyPacketCallbacksMethod(3);

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rpe$onSendSimple(Packet<?> packet, CallbackInfo ci) {
        if (handleSend(packet, () -> self().send(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rpe$onSendWithCallbacks(Packet<?> packet, PacketCallbacks callbacks, CallbackInfo ci) {
        if (handleSend(packet, () -> invokeLegacySend(packet, callbacks))) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rpe$onSendWithCallbacks(Packet<?> packet, PacketCallbacks callbacks, boolean flush, CallbackInfo ci) {
        if (handleSend(packet, () -> invokeLegacySend(packet, callbacks, flush))) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rpe$onSendWithListener(Packet<?> packet, ChannelFutureListener callbacks, CallbackInfo ci) {
        if (handleSend(packet, () -> self().send(packet, callbacks))) {
            ci.cancel();
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void rpe$onSendWithListener(Packet<?> packet, ChannelFutureListener callbacks, boolean flush, CallbackInfo ci) {
        if (handleSend(packet, () -> self().send(packet, callbacks, flush))) {
            ci.cancel();
        }
    }

    private boolean handleSend(Packet<?> packet, Runnable sendAction) {
        if (isDelayedSend.get()) {
            return false;
        }

        PingEqualizerState.Mode mode = PingEqualizerState.getInstance().getMode();
        if (mode == PingEqualizerState.Mode.OFF) {
            return false;
        }

        if (self().getSide() != NetworkSide.CLIENTBOUND) {
            return false;
        }

        if (shouldBypassSend(packet)) {
            return false;
        }

        if (packet instanceof QueryPingC2SPacket queryPacket) {
            PingEqualizerState.getInstance().onPingSent(queryPacket.getStartTime());
            // Apply delay to ping packets like any other packet for consistent timing
        }

        long delay = PingEqualizerState.getInstance().getOutboundDelayPortion();
        if ((delay <= 0 && sendQueue.isEmpty()) || !self().isOpen() || channel == null || channel.eventLoop() == null) {
            return false;
        }

        long sendTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        sendQueue.offer(new DelayedPacketTask(packet, sendAction, sendTime));
        processSendQueue();
        return true;
    }

    private void processSendQueue() {
        if (!processingSendQueue.compareAndSet(false, true)) {
            return;
        }

        if (channel == null || channel.eventLoop() == null) {
            processingSendQueue.set(false);
            return;
        }

        if (channel.eventLoop().inEventLoop()) {
            drainSendQueue();
        } else {
            channel.eventLoop().execute(this::drainSendQueue);
        }
    }

    private void drainSendQueue() {
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
                            if (task.packet instanceof QueryPingC2SPacket queryPacket) {
                                PingEqualizerState.getInstance().onPingActuallySent(queryPacket.getStartTime());
                            }
                            sendAction.run();
                        } finally {
                            isDelayedSend.set(false);
                        }
                    }
                    continue;
                }

                if (delayNanos <= PRECISION_WINDOW_NANOS) {
                    spinWait(delayNanos);
                    continue;
                }

                long waitNanos = Math.max(delayNanos - PRECISION_WINDOW_NANOS, MIN_RESCHEDULE_NANOS);
                channel.eventLoop().schedule(this::processSendQueue, waitNanos, TimeUnit.NANOSECONDS);
                processingSendQueue.set(false);
                return;
            }
        } catch (Exception e) {
            processingSendQueue.set(false);
            throw e;
        }
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void rpe$onDisconnect(Text disconnectReason, CallbackInfo ci) {
        PingEqualizerState.getInstance().setOff();
    }

    @Shadow
    protected abstract void channelRead0(ChannelHandlerContext ctx, Packet<?> msg);

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void onChannelRead(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (isDelayedReceive.get()) {
            return;
        }

        PingEqualizerState.Mode mode = PingEqualizerState.getInstance().getMode();
        if (mode == PingEqualizerState.Mode.OFF) {
            return;
        }

        if (self().getSide() != NetworkSide.CLIENTBOUND) {
            return;
        }

        if (shouldBypassReceive(packet)) {
            return;
        }

        if (packet instanceof PingResultS2CPacket pingPacket) {
            PingEqualizerState.getInstance().onPingArrived(pingPacket.startTime());
            // Apply split delay to ping result packets like any other packet
        }

        long delay = PingEqualizerState.getInstance().getInboundDelayPortion();
        if ((delay <= 0 && receiveQueue.isEmpty()) || !self().isOpen() || context.executor() == null) {
            return;
        }

        ci.cancel();

        long sendTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
        receiveQueue.offer(new DelayedPacketTask(packet, null, sendTime));
        processReceiveQueue(context);
    }

    private void processReceiveQueue(ChannelHandlerContext context) {
        if (!processingReceiveQueue.compareAndSet(false, true)) {
            return;
        }

        if (context.executor() == null) {
            processingReceiveQueue.set(false);
            return;
        }

        Runnable runner = () -> drainReceiveQueue(context);
        if (context.executor().inEventLoop()) {
            runner.run();
        } else {
            context.executor().execute(runner);
        }
    }

    private void drainReceiveQueue(ChannelHandlerContext context) {
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
                            this.channelRead0(context, task.packet);
                        } finally {
                            isDelayedReceive.set(false);
                        }
                    }
                } else {
                    context.executor().schedule(() -> {
                        processingReceiveQueue.set(false);
                        processReceiveQueue(context);
                    }, delayNanos, TimeUnit.NANOSECONDS);
                    return;
                }
            }
        } catch (Exception e) {
            processingReceiveQueue.set(false);
            throw e;
        }
    }

    private static void spinWait(long nanos) {
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

    private static boolean shouldBypassSend(Packet<?> packet) {
        if (packet instanceof CustomPayloadC2SPacket customPayload) {
            return isPlayerLoadedPayload(customPayload.payload());
        }
        return false;
    }

    private static boolean shouldBypassReceive(Packet<?> packet) {
        if (packet instanceof CustomPayloadS2CPacket customPayload) {
            return isPlayerLoadedPayload(customPayload.payload());
        }
        return false;
    }

    private static boolean isPlayerLoadedPayload(CustomPayload payload) {
        return payload != null && payload.getId().equals(PLAYER_LOADED_PAYLOAD_ID);
    }

    private ClientConnection self() {
        return (ClientConnection)(Object)this;
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
