package net.ravenclaw.ravenclawspingequalizer.cryptography;

public record HeartbeatPayload(
        String modHash,
        String playerUuid,
        String username,
        long timestamp,
        String currentServer,
        String modStatus,
        String modVersion,
        String minecraftProof,
        String serverId,
        String signature,
        boolean isSigned,
        String peMode,
        int peDelay,
        int peBasePing,
        int peTotalPing
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
