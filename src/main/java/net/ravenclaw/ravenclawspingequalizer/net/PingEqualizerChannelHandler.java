package net.ravenclaw.ravenclawspingequalizer.net;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;

public class PingEqualizerChannelHandler extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "ping_equalizer";

    private final ArrayDeque<OutboundTask> outboundQueue = new ArrayDeque<>();
    private final ArrayDeque<InboundTask> inboundQueue = new ArrayDeque<>();

    private ScheduledFuture<?> outboundDrainFuture;
    private long outboundDrainAtNanos = Long.MAX_VALUE;

    private ScheduledFuture<?> inboundDrainFuture;
    private long inboundDrainAtNanos = Long.MAX_VALUE;

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
        if (!ctx.executor().inEventLoop()) {
            ctx.executor().execute(() -> {
                try {
                    write(ctx, msg, promise);
                } catch (Exception e) {
                    promise.setFailure(e);
                }
            });
            return;
        }

        if (!active || !(msg instanceof Packet<?> packet)) {
            if (!outboundQueue.isEmpty()) {
                flushQueuesNow(ctx);
            }
            super.write(ctx, msg, promise);
            return;
        }

        if (shouldBypassPacket(packet)) {
            flushAllQueues();
            super.write(ctx, msg, promise);
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
        outboundQueue.addLast(new OutboundTask(msg, promise, sendTime));
        ensureOutboundScheduled(ctx);
    }

    private void drainOutbound(ChannelHandlerContext ctx) {
        if (!ctx.executor().inEventLoop()) {
            ctx.executor().execute(() -> drainOutbound(ctx));
            return;
        }

        outboundDrainFuture = null;
        outboundDrainAtNanos = Long.MAX_VALUE;

        boolean wrote = false;
        long now = System.nanoTime();
        while (true) {
            OutboundTask task = outboundQueue.peekFirst();
            if (task == null) {
                break;
            }
            if (task.sendTimeNanos > now) {
                break;
            }

            outboundQueue.pollFirst();
            if (ctx.channel().isOpen()) {
                if (task.msg instanceof QueryPingC2SPacket qp) {
                    PingEqualizerState.getInstance().onPingActuallySent(qp.getStartTime());
                }
                ctx.write(task.msg, task.promise);
                wrote = true;
            }
            now = System.nanoTime();
        }

        if (wrote && ctx.channel().isOpen()) {
            ctx.flush();
        }

        ensureOutboundScheduled(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!ctx.executor().inEventLoop()) {
            ctx.executor().execute(() -> {
                try {
                    channelRead(ctx, msg);
                } catch (Exception e) {
                    ctx.fireExceptionCaught(e);
                }
            });
            return;
        }

        if (!active || !(msg instanceof Packet<?> packet)) {
            if (!inboundQueue.isEmpty()) {
                flushQueuesNow(ctx);
            }
            super.channelRead(ctx, msg);
            return;
        }

        if (shouldBypassPacket(packet)) {
            flushAllQueues();
            super.channelRead(ctx, msg);
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
        inboundQueue.addLast(new InboundTask(msg, deliverTime));
        ensureInboundScheduled(ctx);
    }

    private void drainInbound(ChannelHandlerContext ctx) {
        if (!ctx.executor().inEventLoop()) {
            ctx.executor().execute(() -> drainInbound(ctx));
            return;
        }

        inboundDrainFuture = null;
        inboundDrainAtNanos = Long.MAX_VALUE;

        long now = System.nanoTime();
        while (true) {
            InboundTask task = inboundQueue.peekFirst();
            if (task == null) {
                break;
            }
            if (task.deliverTimeNanos > now) {
                break;
            }

            inboundQueue.pollFirst();
            if (ctx.channel().isOpen()) {
                if (task.msg instanceof PingResultS2CPacket pingResult) {
                    PingEqualizerState state = PingEqualizerState.getInstance();
                    state.onPingArrived(pingResult.startTime());
                    state.handlePingResult(pingResult);
                }
                ctx.fireChannelRead(task.msg);
            }
            now = System.nanoTime();
        }

        ensureInboundScheduled(ctx);
    }

    private boolean shouldBypassPacket(Packet<?> packet) {
        return packet.transitionsNetworkState();
    }

    public void flushAllQueues() {
        ChannelHandlerContext ctx = savedContext;
        if (ctx == null || !ctx.channel().isOpen()) {
            outboundQueue.clear();
            inboundQueue.clear();
            cancelOutboundSchedule();
            cancelInboundSchedule();
            return;
        }

        if (ctx.executor().inEventLoop()) {
            flushQueuesNow(ctx);
        } else {
            ctx.executor().execute(() -> flushQueuesNow(ctx));
        }
    }

    private void flushQueuesNow(ChannelHandlerContext ctx) {
        cancelOutboundSchedule();
        cancelInboundSchedule();

        OutboundTask outTask;
        while ((outTask = outboundQueue.poll()) != null) {
            if (ctx.channel().isOpen()) {
                ctx.write(outTask.msg, outTask.promise);
            }
        }
        if (ctx.channel().isOpen()) {
            ctx.flush();
        }

        InboundTask inTask;
        while ((inTask = inboundQueue.poll()) != null) {
            if (ctx.channel().isOpen()) {
                ctx.fireChannelRead(inTask.msg);
            }
        }
    }

    private void ensureOutboundScheduled(ChannelHandlerContext ctx) {
        if (!ctx.executor().inEventLoop()) {
            ctx.executor().execute(() -> ensureOutboundScheduled(ctx));
            return;
        }

        if (outboundQueue.isEmpty()) {
            cancelOutboundSchedule();
            return;
        }

        long nextAt = outboundQueue.peekFirst().sendTimeNanos;
        if (nextAt <= System.nanoTime()) {
            cancelOutboundSchedule();
            drainOutbound(ctx);
            return;
        }
        if (outboundDrainFuture != null && nextAt == outboundDrainAtNanos) {
            return;
        }

        cancelOutboundSchedule();
        outboundDrainAtNanos = nextAt;
        long delayNanos = Math.max(0, nextAt - System.nanoTime());
        outboundDrainFuture = ctx.executor().schedule(() -> drainOutbound(ctx), delayNanos, TimeUnit.NANOSECONDS);
    }

    private void ensureInboundScheduled(ChannelHandlerContext ctx) {
        if (!ctx.executor().inEventLoop()) {
            ctx.executor().execute(() -> ensureInboundScheduled(ctx));
            return;
        }

        if (inboundQueue.isEmpty()) {
            cancelInboundSchedule();
            return;
        }

        long nextAt = inboundQueue.peekFirst().deliverTimeNanos;
        if (nextAt <= System.nanoTime()) {
            cancelInboundSchedule();
            drainInbound(ctx);
            return;
        }
        if (inboundDrainFuture != null && nextAt == inboundDrainAtNanos) {
            return;
        }

        cancelInboundSchedule();
        inboundDrainAtNanos = nextAt;
        long delayNanos = Math.max(0, nextAt - System.nanoTime());
        inboundDrainFuture = ctx.executor().schedule(() -> drainInbound(ctx), delayNanos, TimeUnit.NANOSECONDS);
    }

    private void cancelOutboundSchedule() {
        if (outboundDrainFuture != null) {
            outboundDrainFuture.cancel(false);
            outboundDrainFuture = null;
        }
        outboundDrainAtNanos = Long.MAX_VALUE;
    }

    private void cancelInboundSchedule() {
        if (inboundDrainFuture != null) {
            inboundDrainFuture.cancel(false);
            inboundDrainFuture = null;
        }
        inboundDrainAtNanos = Long.MAX_VALUE;
    }
}
