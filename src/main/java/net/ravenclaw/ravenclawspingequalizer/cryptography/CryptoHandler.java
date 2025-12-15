package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import net.fabricmc.loader.api.FabricLoader;
import java.util.Locale;

public class CryptoHandler {

    private static final int HEARTBEAT_INTERVAL_TICKS = 600;

    private static final int MAX_HEARTBEATS_PER_MINUTE = 100;
    private static final long ONE_MINUTE_MS = 60_000;

    private static final int SPAM_THRESHOLD_COUNT = 2;
    private static final long SPAM_WINDOW_MS = 1000;
    private static final long SPAM_COOLDOWN_MS = 1000;

    private String reconstructedKey;
    private final String modHash;
    private boolean canSign = false;
    private volatile PrivateKey signingKey;
    private long tickCount = 0;
    private boolean isValidated = false;
    private PlayerAttestation currentAttestation;
    private String currentServerAddress = "";
    private final String modVersion;
    private volatile boolean isHashApproved = false;
    private volatile boolean isVersionDeprecated = false;
    private volatile boolean hashApprovalInProgress = false;

    private final Deque<Long> heartbeatTimestamps = new ArrayDeque<>();
    private final Deque<Long> commandHeartbeatTimestamps = new ArrayDeque<>();
    private long spamCooldownUntil = 0;

    public CryptoHandler() {
        modHash = CryptoUtils.bytesToHex(CryptoUtils.calculateModHash()).toUpperCase(Locale.ROOT);
        modVersion = resolveModVersion();
        validateHashAsync();
    }

    private void initializeSigningKey() {
        String candidate = "";
        try {
            candidate = PrivateKeyReconstructor.reconstructPrivateKey();
        } catch (Exception ignored) {
        }

        reconstructedKey = candidate != null ? candidate : "";
        if (reconstructedKey.isEmpty()) {
            canSign = false;
            signingKey = null;
            return;
        }

        try {
            signingKey = CryptoUtils.parsePrivateKeyFromPEM(reconstructedKey);
            canSign = signingKey != null;
        } catch (GeneralSecurityException e) {
            signingKey = null;
            canSign = false;
            reconstructedKey = "";
        }
    }

    private void validateHashAsync() {
        if (hashApprovalInProgress || isHashApproved) {
            return;
        }
        hashApprovalInProgress = true;
        ApiService.validateModHash(modHash)
            .thenAccept(response -> {
                boolean signatureValid = response.signature() != null &&
                    CryptoUtils.verifyServerSignature(modHash, response.signature());
                boolean versionOk = response.version() == null ||
                    response.version().isBlank() ||
                    response.version().equalsIgnoreCase("unknown") ||
                    response.version().equals(modVersion);
                isVersionDeprecated = response.deprecated();
                boolean approved = response.isValid() && signatureValid && versionOk;
                isHashApproved = approved;
                hashApprovalInProgress = false;
                if (approved) {
                    initializeSigningKey();
                }
            })
            .exceptionally(ex -> {
                isHashApproved = false;
                hashApprovalInProgress = false;
                isVersionDeprecated = false;
                return null;
            });
    }

    private volatile boolean attestationInProgress = false;

    public void tick() {
        tickCount++;

        if (tickCount % HEARTBEAT_INTERVAL_TICKS == 0) {
            if (currentAttestation == null || currentAttestation.isExpired(tickCount)) {
                generateAttestationAsync();
            } else {
                sendHeartbeatAsync();
            }
        }
    }

    private void generateAttestationAsync() {
        if (attestationInProgress) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;

        Session session = client.getSession();
        if (session == null) return;

        String username = session.getUsername();
        UUID playerUuid = session.getUuidOrNull();
        if (username == null || playerUuid == null) return;

        String accessToken = session.getAccessToken();
        var sessionService = client.getSessionService();

        attestationInProgress = true;
        String serverId = generateRandomServerId();

        CompletableFuture.runAsync(() -> {
            try {
                sessionService.joinServer(playerUuid, accessToken, serverId);
            } catch (Exception e) {
                attestationInProgress = false;
                return;
            }

            MojangApiClient.getHasJoinedResponse(username, serverId)
                .thenAccept(mojangResponse -> {
                    attestationInProgress = false;
                    if (mojangResponse != null && !mojangResponse.isEmpty()) {
                        UUID parsedUuid = MojangApiClient.parseUuidFromHasJoinedResponse(mojangResponse);
                        String parsedUsername = MojangApiClient.parseUsernameFromHasJoinedResponse(mojangResponse);

                        if (parsedUuid != null && parsedUsername != null) {
                            currentAttestation = new PlayerAttestation(parsedUuid, parsedUsername, serverId, mojangResponse);
                            sendHeartbeatAsync();
                        }
                    }
                })
                .exceptionally(ex -> {
                    attestationInProgress = false;
                    currentAttestation = null;
                    return null;
                });
        }).exceptionally(ex -> {
            attestationInProgress = false;
            return null;
        });
    }

    private void sendHeartbeatAsync() {
        if (currentAttestation == null || currentAttestation.isExpired(tickCount)) {
            isValidated = false;
            return;
        }

        if (!isHashApproved) {
            isValidated = false;
            validateHashAsync();
        }

        long timestamp = System.currentTimeMillis();

        String signature = null;
        String modStatus = "unsigned";

        if (isHashApproved) {
            if (canSign && signingKey != null) {
                signature = createAndSignHeartbeatPayload(timestamp);
            }
            if (signature == null || signature.isEmpty()) {
                signature = null;
                modStatus = "unsigned";
            } else {
                modStatus = "signed";
            }
        }
        String version = modVersion;

        net.ravenclaw.ravenclawspingequalizer.PingEqualizerState peState =
                net.ravenclaw.ravenclawspingequalizer.PingEqualizerState.getInstance();
        String peMode = peState.getMode().name().toLowerCase();
        int peDelay = peState.getCurrentDelayMs();
        int peBasePing = peState.getBasePing();
        int peTotalPing = peState.getTotalPing();

        HeartbeatPayload payload = HeartbeatPayload.create(
                modHash,
                currentAttestation,
                currentServerAddress,
                modStatus,
                version,
                signature,
                peMode,
                peDelay,
                peBasePing,
                peTotalPing,
                timestamp
        );

        ApiService.sendHeartbeat(payload)
                .thenAccept(success -> isValidated = success)
                .exceptionally(ex -> {
                    isValidated = false;
                    return null;
                });
    }

    private String createAndSignHeartbeatPayload(long timestamp) {
        StringBuilder payload = new StringBuilder();
        payload.append(modHash);
        payload.append("|");
        payload.append(modVersion);
        payload.append("|");
        payload.append(currentAttestation.getPlayerUuid());
        payload.append("|");
        payload.append(currentAttestation.getServerId());
        payload.append("|");
        payload.append(timestamp);

        PrivateKey key = signingKey;
        if (key == null) {
            return null;
        }
        try {
            return CryptoUtils.signPayload(payload.toString().getBytes(StandardCharsets.UTF_8), key);
        } catch (RuntimeException e) {
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

    private String resolveModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("ravenclawspingequalizer")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public void setCurrentServer(String serverAddress) {
        String newAddress = serverAddress != null ? serverAddress : "";
        boolean changed = !this.currentServerAddress.equals(newAddress);
        this.currentServerAddress = newAddress;
        if (changed) {
            triggerHeartbeatForServerChange();
        }
    }

    public String getCurrentServer() {
        return this.currentServerAddress;
    }

    private boolean canSendHeartbeat() {
        long now = System.currentTimeMillis();
        long cutoff = now - ONE_MINUTE_MS;

        while (!heartbeatTimestamps.isEmpty() && heartbeatTimestamps.peekFirst() < cutoff) {
            heartbeatTimestamps.pollFirst();
        }

        return heartbeatTimestamps.size() < MAX_HEARTBEATS_PER_MINUTE;
    }

    private void recordHeartbeat() {
        heartbeatTimestamps.addLast(System.currentTimeMillis());
    }

    private boolean isCommandSpamming() {
        long now = System.currentTimeMillis();

        if (now < spamCooldownUntil) {
            return true;
        }

        long cutoff = now - SPAM_WINDOW_MS;

        while (!commandHeartbeatTimestamps.isEmpty() && commandHeartbeatTimestamps.peekFirst() < cutoff) {
            commandHeartbeatTimestamps.pollFirst();
        }

        if (commandHeartbeatTimestamps.size() >= SPAM_THRESHOLD_COUNT) {
            spamCooldownUntil = now + SPAM_COOLDOWN_MS;
            return true;
        }

        return false;
    }

    private void recordCommandHeartbeat() {
        commandHeartbeatTimestamps.addLast(System.currentTimeMillis());
    }

    private void triggerHeartbeatForServerChange() {
        if (!canSendHeartbeat()) {
            return;
        }

        recordHeartbeat();

        if (currentAttestation == null || currentAttestation.isExpired(tickCount)) {
            generateAttestationAsync();
        } else {
            sendHeartbeatAsync();
        }
    }

    public void triggerHeartbeatForCommand() {
        if (!canSendHeartbeat()) {
            return;
        }

        recordHeartbeat();

        if (currentAttestation == null || currentAttestation.isExpired(tickCount)) {
            generateAttestationAsync();
        } else {
            sendHeartbeatAsync();
        }
    }

    public void triggerImmediateHeartbeat() {
        triggerHeartbeatForServerChange();
    }

    public boolean triggerImmediateHeartbeatWithCooldown() {
        if (!beginCommandExecution()) {
            return false;
        }
        triggerHeartbeatForCommand();
        return true;
    }

    public CompletableFuture<ApiService.PlayerValidationResult> validatePlayer(UUID playerUuid) {
        triggerHeartbeatForCommand();
        return ApiService.validatePlayerByUuid(playerUuid);
    }

    public CompletableFuture<ApiService.PlayerValidationResult> validatePlayer(String username) {
        triggerHeartbeatForCommand();
        return ApiService.validatePlayerByUsername(username);
    }

    public boolean beginCommandExecution() {
        if (!canSendHeartbeat()) {
            return false;
        }
        if (isCommandSpamming()) {
            return false;
        }
        recordCommandHeartbeat();
        return true;
    }

    public boolean isValidated() {
        return isValidated;
    }

    public boolean canSign() {
        return canSign;
    }

    public boolean hasValidKey() {
        return canSign && signingKey != null;
    }

    public String getModHash() {
        return modHash;
    }

    public boolean isHashApproved() {
        return isHashApproved;
    }

    public PlayerAttestation getCurrentAttestation() {
        return currentAttestation;
    }

    public boolean isCurrentVersionDeprecated() {
        return isVersionDeprecated;
    }

    void setKey(String reconstructedKey) {
        this.reconstructedKey = reconstructedKey;
    }

    String getKey() {
        return this.reconstructedKey;
    }
}
