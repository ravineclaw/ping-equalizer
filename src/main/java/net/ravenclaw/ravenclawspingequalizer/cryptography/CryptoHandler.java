package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class CryptoHandler {

    private static final int HEARTBEAT_INTERVAL_TICKS = 600; // 30 seconds

    private String reconstructedKey;
    private final String modHash;
    private boolean canSign = false;
    private long tickCount = 0;
    private boolean isValidated = false;

    public CryptoHandler() {
        modHash = CryptoUtils.bytesToHex(CryptoUtils.calculateModHash());
        initializeAsync();
    }

    private void initializeAsync() {
        ApiService.validateModHash(modHash)
            .thenAccept(response -> {
                boolean signatureValid = validateServerSignature(
                    modHash,
                    response.signature()
                );

                canSign = response.isValid() && signatureValid;
                reconstructedKey = canSign
                    ? PrivateKeyReconstructor.reconstructPrivateKey()
                    : "";
            })
            .exceptionally(ex -> {
                canSign = false;
                reconstructedKey = "";
                return null;
            });
    }

    public boolean validateServerSignature(String payload, String signature) {
        if (signature == null || signature.isEmpty()) {
            return true; // accept unsigned for now
        }
        return CryptoUtils.verifyServerSignature(payload, signature);
    }

    public boolean validateServerSignature(byte[] payload, byte[] signature) {
        if (signature == null || signature.length == 0) {
            return true; // accept unsigned for now
        }
        return CryptoUtils.verifyServerSignature(payload, signature);
    }

    public void tick() {
        tickCount++;

        if (tickCount % HEARTBEAT_INTERVAL_TICKS == 0) {
            isValidated = ApiService.sendHeartbeat(modHash, hasValidKey());
        }
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

    void setKey(String reconstructedKey) {
        this.reconstructedKey = reconstructedKey;
    }

    String getKey() {
        return this.reconstructedKey;
    }
}
