package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;

public class CryptoHandler {

    private static final int HEARTBEAT_INTERVAL_TICKS = 600;

    private String reconstructedKey;
    private final String modHash;
    private boolean canSign = false;
    private long tickCount = 0;
    private boolean isValidated = false;
    private PlayerAttestation currentAttestation;
    private String currentServerAddress = "";

    public CryptoHandler() {
        modHash = CryptoUtils.bytesToHex(CryptoUtils.calculateModHash());
        initializeAsync();
    }

    private void initializeAsync() {
        ApiService.validateModHash(modHash)
            .thenAccept(response -> {
                if (response.isValid() && response.signature() != null) {
                    boolean signatureValid = CryptoUtils.verifyServerSignature(modHash, response.signature());
                    canSign = signatureValid;
                    reconstructedKey = canSign ? PrivateKeyReconstructor.reconstructPrivateKey() : "";
                } else {
                    canSign = false;
                    reconstructedKey = "";
                }
            })
            .exceptionally(ex -> {
                canSign = false;
                reconstructedKey = "";
                return null;
            });
    }

    public void tick() {
        tickCount++;

        if (tickCount % HEARTBEAT_INTERVAL_TICKS == 0) {
            if (currentAttestation == null || currentAttestation.isExpired(tickCount)) {
                generateAttestation();
            } else {
                sendHeartbeat();
            }
        }
    }

    private void generateAttestation() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        Session session = client.getSession();
        if (session == null) return;

        String username = session.getUsername();
        UUID playerUuid = session.getUuidOrNull();
        if (username == null || playerUuid == null) return;

        String serverId = generateRandomServerId();

        try {
            client.getSessionService().joinServer(playerUuid, session.getAccessToken(), serverId);
        } catch (Exception e) {
            return;
        }

        MojangApiClient.getHasJoinedResponse(username, serverId)
            .thenAccept(mojangResponse -> {
                if (mojangResponse != null && !mojangResponse.isEmpty()) {
                    UUID parsedUuid = MojangApiClient.parseUuidFromHasJoinedResponse(mojangResponse);
                    String parsedUsername = MojangApiClient.parseUsernameFromHasJoinedResponse(mojangResponse);

                    if (parsedUuid != null && parsedUsername != null) {
                        currentAttestation = new PlayerAttestation(parsedUuid, parsedUsername, serverId, mojangResponse);
                        sendHeartbeat();
                    }
                }
            })
            .exceptionally(ex -> {
                currentAttestation = null;
                return null;
            });
    }

    private void sendHeartbeat() {
        if (currentAttestation == null || currentAttestation.isExpired(tickCount)) {
            isValidated = false;
            return;
        }

        String signature = null;
        if (canSign && reconstructedKey != null && !reconstructedKey.isEmpty()) {
            signature = createAndSignHeartbeatPayload();
        }

        String modStatus = canSign ? "signed" : "unsigned";

        net.ravenclaw.ravenclawspingequalizer.PingEqualizerState peState =
            net.ravenclaw.ravenclawspingequalizer.PingEqualizerState.getInstance();
        String peMode = peState.getMode().name().toLowerCase();
        int peDelay = peState.getCurrentDelayMs();
        int peBasePing = peState.getBasePing();
        int peTotalPing = peState.getTotalPing();

        ApiService.HeartbeatPayload payload = ApiService.HeartbeatPayload.create(
            modHash,
            currentAttestation,
            currentServerAddress,
            modStatus,
            signature,
            peMode,
            peDelay,
            peBasePing,
            peTotalPing
        );

        ApiService.sendHeartbeat(payload)
            .thenAccept(success -> isValidated = success)
            .exceptionally(ex -> {
                isValidated = false;
                return null;
            });
    }

    private String createAndSignHeartbeatPayload() {
        StringBuilder payload = new StringBuilder();
        payload.append(modHash);
        payload.append("|");
        payload.append(currentAttestation.getPlayerUuid());
        payload.append("|");
        payload.append(currentAttestation.getServerId());
        payload.append("|");
        payload.append(System.currentTimeMillis());

        try {
            PrivateKey privateKey = CryptoUtils.parsePrivateKeyFromPEM(reconstructedKey);
            return CryptoUtils.signPayload(payload.toString().getBytes(StandardCharsets.UTF_8), privateKey);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    private String generateRandomServerId() {
        StringBuilder serverId = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            serverId.append(String.format("%x", (int) (Math.random() * 16)));
        }
        return serverId.toString();
    }

    public void setCurrentServer(String serverAddress) {
        this.currentServerAddress = serverAddress != null ? serverAddress : "";
    }

    public String getCurrentServer() {
        return this.currentServerAddress;
    }

    public CompletableFuture<ApiService.PlayerValidationResult> validatePlayer(UUID playerUuid) {
        return ApiService.validatePlayerByUuid(playerUuid);
    }

    public CompletableFuture<ApiService.PlayerValidationResult> validatePlayer(String username) {
        return ApiService.validatePlayerByUsername(username);
    }

    public boolean isValidated() {
        return isValidated;
    }

    public boolean canSign() {
        return canSign;
    }

    public boolean hasValidKey() {
        return canSign && reconstructedKey != null && !reconstructedKey.isEmpty();
    }

    public String getModHash() {
        return modHash;
    }

    public PlayerAttestation getCurrentAttestation() {
        return currentAttestation;
    }

    void setKey(String reconstructedKey) {
        this.reconstructedKey = reconstructedKey;
    }

    String getKey() {
        return this.reconstructedKey;
    }
}





