package net.ravenclaw.ravenclawspingequalizer.cryptography;

import com.google.gson.annotations.SerializedName;

public record HeartbeatPayload(
        @SerializedName("modHash") String modHash,
        @SerializedName("playerUuid") String playerUuid,
        @SerializedName("username") String username,
        @SerializedName("timestamp") long timestamp,
        @SerializedName("currentServer") String currentServer,
        @SerializedName("modStatus") String modStatus,
        @SerializedName("modVersion") String modVersion,
        @SerializedName("minecraftProof") String minecraftProof,
        @SerializedName("serverId") String serverId,
        @SerializedName("signature") String signature,
        @SerializedName("isSigned") boolean isSigned,
        @SerializedName("peMode") String peMode,
        @SerializedName("peDelay") int peDelay,
        @SerializedName("peBasePing") int peBasePing,
        @SerializedName("peTotalPing") int peTotalPing
) {
    public static HeartbeatPayload create(
            String modHash,
            PlayerAttestation attestation,
            String currentServer,
            String modStatus,
            String modVersion,
            String signature,
            String peMode,
            int peDelay,
            int peBasePing,
            int peTotalPing,
            long timestamp
    ) {
        return new HeartbeatPayload(
                modHash,
                attestation.getPlayerUuid().toString(),
                attestation.getUsername(),
                timestamp,
                currentServer,
                modStatus,
                modVersion,
                attestation.getMojangResponse(),
                attestation.getServerId(),
                signature,
                signature != null && !signature.isEmpty(),
                peMode,
                peDelay,
                peBasePing,
                peTotalPing
        );
    }
}
