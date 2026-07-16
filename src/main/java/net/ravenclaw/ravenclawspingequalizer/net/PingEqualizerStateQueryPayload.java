package net.ravenclaw.ravenclawspingequalizer.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PingEqualizerStateQueryPayload(int requestId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PingEqualizerStateQueryPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ravenclawspingequalizer", "state_query"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PingEqualizerStateQueryPayload> CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> buf.writeVarInt(payload.requestId()),
                    buf -> new PingEqualizerStateQueryPayload(buf.readVarInt())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
