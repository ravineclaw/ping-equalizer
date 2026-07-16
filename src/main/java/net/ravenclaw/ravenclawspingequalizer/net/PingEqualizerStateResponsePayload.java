package net.ravenclaw.ravenclawspingequalizer.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PingEqualizerStateResponsePayload(
        int requestId,
        boolean serverEnabled,
        String mode,
        int currentDelayMs,
        int basePingMs,
        int totalPingMs
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PingEqualizerStateResponsePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ravenclawspingequalizer", "state_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PingEqualizerStateResponsePayload> CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> {
                        buf.writeVarInt(payload.requestId());
                        buf.writeBoolean(payload.serverEnabled());
                        buf.writeUtf(payload.mode());
                        buf.writeVarInt(payload.currentDelayMs());
                        buf.writeVarInt(payload.basePingMs());
                        buf.writeVarInt(payload.totalPingMs());
                    },
                    buf -> new PingEqualizerStateResponsePayload(
                            buf.readVarInt(),
                            buf.readBoolean(),
                            buf.readUtf(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt()
                    )
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
