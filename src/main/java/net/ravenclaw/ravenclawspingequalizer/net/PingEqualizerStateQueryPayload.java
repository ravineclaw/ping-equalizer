package net.ravenclaw.ravenclawspingequalizer.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record PingEqualizerStateQueryPayload(int requestId) implements CustomPayload {
    public static final CustomPayload.Id<PingEqualizerStateQueryPayload> ID =
            new CustomPayload.Id<>(Identifier.of("ravenclawspingequalizer", "state_query"));

    public static final PacketCodec<RegistryByteBuf, PingEqualizerStateQueryPayload> CODEC =
            PacketCodec.of(
                    (payload, buf) -> buf.writeVarInt(payload.requestId()),
                    buf -> new PingEqualizerStateQueryPayload(buf.readVarInt())
            );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
