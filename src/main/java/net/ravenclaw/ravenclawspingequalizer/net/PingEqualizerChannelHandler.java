package net.ravenclaw.ravenclawspingequalizer.net;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;

public class PingEqualizerChannelHandler extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "ping_equalizer";

    private static final long PRECISION_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long MIN_RESCHEDULE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    private final ConcurrentLinkedQueue<OutboundTask> outboundQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<InboundTask> inboundQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean processingOutbound = new AtomicBoolean(false);
    private final AtomicBoolean processingInbound = new AtomicBoolean(false);

    private volatile boolean active = true;
    private volatile ChannelHandlerContext savedContext;

    private static final class OutboundTask {
        final Object msg;
        final ChannelPromise promise;
        final long sendTimeNanos;

        OutboundTask(Object msg, ChannelPromise promise, long sendTimeNanos) {
            this.msg = msg;
            this.promise = promise;
            this.sendTimeNanos = sendTimeNanos;
        }
    }

    private static final class InboundTask {
        final Object msg;
        final long deliverTimeNanos;

        InboundTask(Object msg, long deliverTimeNanos) {
            this.msg = msg;
            this.deliverTimeNanos = deliverTimeNanos;
        }
    }

    public void setActive(boolean active) {
        this.active = active;
        if (!active) {
            flushAllQueues();
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.savedContext = ctx;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        flushAllQueues();
        this.savedContext = null;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!active || !(msg instanceof Packet<?> packet)) {
            super.write(ctx, msg, promise);
            return;
        }

        if (shouldBypassPacket(packet)) {
            queueOutbound(ctx, msg, promise, 0);
            return;
        }

        if (packet instanceof QueryPingC2SPacket qp) {
            PingEqualizerState.getInstance().onPingSent(qp.getStartTime());
        }

        PingEqualizerState state = PingEqualizerState.getInstance();
        if (state.getMode() == PingEqualizerState.Mode.OFF && outboundQueue.isEmpty()) {
            if (packet instanceof QueryPingC2SPacket qp) {
                PingEqualizerState.getInstance().onPingActuallySent(qp.getStartTime());
            }
            super.write(ctx, msg, promise);
            return;
        }

        long delay = state.getOutboundDelayPortion();

        if (delay <= 0 && outboundQueue.isEmpty()) {
            if (packet instanceof QueryPingC2SPacket qp) {
                PingEqualizerState.getInstance().onPingActuallySent(qp.getStartTime());
            }
            super.write(ctx, msg, promise);
            return;
        }

        if (packet instanceof QueryPingC2SPacket qp) {
            PingEqualizerState.getInstance().recordPingOutboundDelay(qp.getStartTime(), delay);
        }

        queueOutbound(ctx, msg, promise, delay);
    }

    private void queueOutbound(ChannelHandlerContext ctx, Object msg, ChannelPromise promise, long delayMs) {
        long sendTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        outboundQueue.offer(new OutboundTask(msg, promise, sendTime));
        processOutboundQueue(ctx);
    }

    private void processOutboundQueue(ChannelHandlerContext ctx) {
        if (!processingOutbound.compareAndSet(false, true)) {
            return;
        }

        if (ctx.executor().inEventLoop()) {
            drainOutbound(ctx);
        } else {
            ctx.executor().execute(() -> drainOutbound(ctx));
        }
    }

    private void drainOutbound(ChannelHandlerContext ctx) {
        boolean wrote = false;
        try {
            while (true) {
                OutboundTask task = outboundQueue.peek();
                if (task == null) {
                    processingOutbound.set(false);
                    if (wrote && ctx.channel().isOpen()) {
                        ctx.flush();
                    }
                    return;
                }

                long delayNanos = task.sendTimeNanos - System.nanoTime();
                if (delayNanos <= 0) {
                    outboundQueue.poll();
                    if (ctx.channel().isOpen()) {
                        if (task.msg instanceof QueryPingC2SPacket qp) {
                            PingEqualizerState.getInstance().onPingActuallySent(qp.getStartTime());
                        }
                        ctx.write(task.msg, task.promise);
                        wrote = true;
                    }
                    continue;
                }

                if (wrote && ctx.channel().isOpen()) {
                    ctx.flush();
                    wrote = false;
                }

                if (delayNanos <= PRECISION_WINDOW_NANOS) {
                    spinWait(delayNanos);
                    continue;
                }

                long waitNanos = Math.max(delayNanos - PRECISION_WINDOW_NANOS, MIN_RESCHEDULE_NANOS);
                ctx.executor().schedule(() -> processOutboundQueue(ctx), waitNanos, TimeUnit.NANOSECONDS);
                processingOutbound.set(false);
                return;
            }
        } catch (Exception e) {
            processingOutbound.set(false);
            throw e;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!active || !(msg instanceof Packet<?> packet)) {
            super.channelRead(ctx, msg);
            return;
        }

        if (shouldBypassPacket(packet)) {
            queueInbound(ctx, msg, 0);
            return;
        }

        PingEqualizerState state = PingEqualizerState.getInstance();
        if (state.getMode() == PingEqualizerState.Mode.OFF && inboundQueue.isEmpty()) {
            if (packet instanceof PingResultS2CPacket pingResult) {
                state.onPingArrived(pingResult.startTime());
                state.handlePingResult(pingResult);
            }
            super.channelRead(ctx, msg);
            return;
        }

        long delay = state.getInboundDelayPortion();

        if (delay <= 0 && inboundQueue.isEmpty()) {
            if (packet instanceof PingResultS2CPacket pingResult) {
                state.onPingArrived(pingResult.startTime());
                state.handlePingResult(pingResult);
            }
            super.channelRead(ctx, msg);
            return;
        }

        if (packet instanceof PingResultS2CPacket ping) {
            PingEqualizerState.getInstance().recordPingInboundDelay(ping.startTime(), delay);
        }

        queueInbound(ctx, msg, delay);
    }

    private void queueInbound(ChannelHandlerContext ctx, Object msg, long delayMs) {
        long deliverTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        inboundQueue.offer(new InboundTask(msg, deliverTime));
        processInboundQueue(ctx);
    }

    private void processInboundQueue(ChannelHandlerContext ctx) {
        if (!processingInbound.compareAndSet(false, true)) {
            return;
        }

        if (ctx.executor().inEventLoop()) {
            drainInbound(ctx);
        } else {
            ctx.executor().execute(() -> drainInbound(ctx));
        }
    }

    private void drainInbound(ChannelHandlerContext ctx) {
        try {
            while (true) {
                InboundTask task = inboundQueue.peek();
                if (task == null) {
                    processingInbound.set(false);
                    return;
                }

                long delayNanos = task.deliverTimeNanos - System.nanoTime();
                if (delayNanos <= 0) {
                    inboundQueue.poll();
                    if (ctx.channel().isOpen()) {
                        if (task.msg instanceof PingResultS2CPacket pingResult) {
                            PingEqualizerState state = PingEqualizerState.getInstance();
                            state.onPingArrived(pingResult.startTime());
                            state.handlePingResult(pingResult);
                        }
                        ctx.fireChannelRead(task.msg);
                    }
                    continue;
                }

                if (delayNanos <= PRECISION_WINDOW_NANOS) {
                    spinWait(delayNanos);
                    continue;
                }

                long waitNanos = Math.max(delayNanos - PRECISION_WINDOW_NANOS, MIN_RESCHEDULE_NANOS);
                ctx.executor().schedule(() -> processInboundQueue(ctx), waitNanos, TimeUnit.NANOSECONDS);
                processingInbound.set(false);
                return;
            }
        } catch (Exception e) {
            processingInbound.set(false);
            throw e;
        }
    }

    private boolean shouldBypassPacket(Packet<?> packet) {
        return packet.transitionsNetworkState();
    }

    private static void spinWait(long nanos) {
        long deadline = System.nanoTime() + nanos;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    public void flushAllQueues() {
        ChannelHandlerContext ctx = savedContext;
        if (ctx == null || !ctx.channel().isOpen()) {
            outboundQueue.clear();
            inboundQueue.clear();
            processingOutbound.set(false);
            processingInbound.set(false);
            return;
        }

        if (ctx.executor().inEventLoop()) {
            flushQueuesNow(ctx);
        } else {
            ctx.executor().execute(() -> flushQueuesNow(ctx));
        }
    }

    private void flushQueuesNow(ChannelHandlerContext ctx) {
        OutboundTask outTask;
        while ((outTask = outboundQueue.poll()) != null) {
            if (ctx.channel().isOpen()) {
                ctx.write(outTask.msg, outTask.promise);
            }
        }
        if (ctx.channel().isOpen()) {
            ctx.flush();
        }
        processingOutbound.set(false);

        InboundTask inTask;
        while ((inTask = inboundQueue.poll()) != null) {
            if (ctx.channel().isOpen()) {
                ctx.fireChannelRead(inTask.msg);
            }
        }
        processingInbound.set(false);
    }
}
