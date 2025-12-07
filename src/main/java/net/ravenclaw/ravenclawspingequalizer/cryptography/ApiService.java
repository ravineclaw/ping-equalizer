package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ApiService {

    private ApiService() {
    }

    public static CompletableFuture<HashValidationResponse> validateModHash(String modHash) {
        // TODO: call server
        return CompletableFuture.completedFuture(new HashValidationResponse(true, ""));
    }

    public record HashValidationResponse(boolean isValid, String signature) {
    }

    public static boolean sendHeartbeat(String modHash, boolean hasValidKey) {
        // TODO: call server
        return hasValidKey;
    }

    public static CompletableFuture<PlayerValidationResult> validatePlayerByUuid(UUID playerUuid) {
        // TODO: call server
        return CompletableFuture.completedFuture(new PlayerValidationResult(false, "Not implemented"));
    }

    public static CompletableFuture<PlayerValidationResult> validatePlayerByUsername(String username) {
        // TODO: call server
        return CompletableFuture.completedFuture(new PlayerValidationResult(false, "Not implemented"));
    }

    public record PlayerValidationResult(boolean isValid, String message) {
        public PlayerValidationResult(boolean isValid) {
            this(isValid, isValid ? "Player is validated" : "Player is not validated");
        }
    }
}
