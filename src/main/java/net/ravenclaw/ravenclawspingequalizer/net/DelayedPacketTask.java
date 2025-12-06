package net.ravenclaw.ravenclawspingequalizer.net;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.packet.Packet;

public final class DelayedPacketTask {
    public final Packet<?> packet;
    public final long sendTimeNanos;
    public final @Nullable Runnable sendAction;

    public DelayedPacketTask(Packet<?> packet, long sendTimeNanos) {
        this(packet, null, sendTimeNanos);
    }

    public DelayedPacketTask(Packet<?> packet, @Nullable Runnable sendAction, long sendTimeNanos) {
        this.packet = packet;
        this.sendAction = sendAction;
        this.sendTimeNanos = sendTimeNanos;
    }
}
