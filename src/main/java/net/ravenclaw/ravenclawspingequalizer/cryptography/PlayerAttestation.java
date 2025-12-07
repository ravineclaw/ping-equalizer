package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.util.UUID;

public class PlayerAttestation {
    private final UUID playerUuid;
    private final String username;
    private final String serverId;
    private final String mojangResponse;
    private final long generatedAt;
    private static final long EXPIRATION_TICKS = 300;

    public PlayerAttestation(UUID playerUuid, String username, String serverId, String mojangResponse) {
        this.playerUuid = playerUuid;
        this.username = username;
        this.serverId = serverId;
        this.mojangResponse = mojangResponse;
        this.generatedAt = System.currentTimeMillis();
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getUsername() {
        return username;
    }

    public String getServerId() {
        return serverId;
    }

    public String getMojangResponse() {
        return mojangResponse;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }

    public boolean isExpired(long currentTicks) {
        long ageInTicks = (System.currentTimeMillis() - generatedAt) / 50;
        return ageInTicks > EXPIRATION_TICKS;
    }

    public long getExpirationTicks() {
        return EXPIRATION_TICKS;
    }
}

