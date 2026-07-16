package net.ravenclaw.ravenclawspingequalizer.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PingEqualizerStateResponsePayload(
        int requestId,
        boolean serverEnabled,
        String mode,
        int currentDelayMs,
        int basePingMs,
        int totalPingMs
) implements CustomPayload {
    public static final CustomPayload.Id<PingEqualizerStateResponsePayload> ID =
            new CustomPayload.Id<>(Identifier.of("ravenclawspingequalizer", "state_response"));

    public static final PacketCodec<RegistryByteBuf, PingEqualizerStateResponsePayload> CODEC =
            PacketCodec.of(
                    (payload, buf) -> {
                        buf.writeVarInt(payload.requestId());
                        buf.writeBoolean(payload.serverEnabled());
                        buf.writeString(payload.mode());
                        buf.writeVarInt(payload.currentDelayMs());
                        buf.writeVarInt(payload.basePingMs());
                        buf.writeVarInt(payload.totalPingMs());
                    },
                    buf -> new PingEqualizerStateResponsePayload(
                            buf.readVarInt(),
                            buf.readBoolean(),
                            buf.readString(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt()
                    )
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
