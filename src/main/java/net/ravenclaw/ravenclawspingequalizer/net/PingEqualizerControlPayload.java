package net.ravenclaw.ravenclawspingequalizer.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PingEqualizerControlPayload(boolean enabled) implements CustomPayload {
    public static final CustomPayload.Id<PingEqualizerControlPayload> ID =
            new CustomPayload.Id<>(Identifier.of("ravenclawspingequalizer", "control"));

    public static final PacketCodec<RegistryByteBuf, PingEqualizerControlPayload> CODEC =
            PacketCodec.of(
                    (payload, buf) -> buf.writeBoolean(payload.enabled()),
                    buf -> new PingEqualizerControlPayload(buf.readBoolean())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
