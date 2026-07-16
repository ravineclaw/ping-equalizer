package net.ravenclaw.ravenclawspingequalizer.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PingEqualizerControlPayload(boolean enabled) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PingEqualizerControlPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ravenclawspingequalizer", "control"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PingEqualizerControlPayload> CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> buf.writeBoolean(payload.enabled()),
                    buf -> new PingEqualizerControlPayload(buf.readBoolean())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
