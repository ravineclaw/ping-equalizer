package net.ravenclaw.ravenclawspingequalizer.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import net.minecraft.client.MinecraftClient;
import net.ravenclaw.ravenclawspingequalizer.config.ModConfig;
import net.ravenclaw.ravenclawspingequalizer.cryptography.CryptoUtils;
import net.ravenclaw.ravenclawspingequalizer.session.SessionManager;
import net.ravenclaw.ravenclawspingequalizer.network.model.AttestationPayload;
import net.ravenclaw.ravenclawspingequalizer.network.model.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class NetworkClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("RavenclawsPingEqualizer-Network");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();

    public static void register() {
        register(1);
    }

    private static void register(int attempt) {
        CompletableFuture.runAsync(() -> {
            try {
                SessionManager session = SessionManager.getInstance();
                ModConfig config = ModConfig.getInstance();

                JsonObject payload = new JsonObject();
                payload.addProperty("username", MinecraftClient.getInstance().getSession().getUsername());
                payload.addProperty("modVersion", session.getModVersion());
                payload.addProperty("modHash", session.getModHash());
                payload.addProperty("publicKey", CryptoUtils.serializePublicKey(session.getSessionKeyPair().getPublic()));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.registerEndpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    String sessionId = json.get("sessionId").getAsString();
                    String challenge = json.get("challenge").getAsString();
                    
                    // Verify server signature if present (Server signs sessionId + challenge)
                    if (json.has("signature")) {
                        String signature = json.get("signature").getAsString();
                        String dataToVerify = sessionId + challenge;
                        if (!CryptoUtils.verifyServerSignature(dataToVerify, signature)) {
                            LOGGER.error("Server signature verification failed! Possible Man-in-the-Middle attack.");
                            return;
                        }
                        LOGGER.info("Server signature verified.");
                    } else {
                        LOGGER.warn("Server did not provide a signature. Proceeding with caution (Dev Mode).");
                    }

                    session.setSessionId(sessionId);
                    attest(challenge);
                } else {
                    LOGGER.error("Registration failed: " + response.statusCode());
                    retry("register", () -> register(attempt + 1), attempt);
                }
            } catch (Exception e) {
                LOGGER.error("Registration error", e);
                retry("register", () -> register(attempt + 1), attempt);
            }
        });
    }

    public static void unregister() {
        try {
            SessionManager session = SessionManager.getInstance();
            if (session.getSessionId() == null) return;
            
            ModConfig config = ModConfig.getInstance();
            JsonObject payload = new JsonObject();
            payload.addProperty("sessionId", session.getSessionId());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.unregisterEndpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .build();

            // Send synchronously as we are shutting down
            CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            LOGGER.error("Unregister error", e);
        }
    }

    public static void attest(String challenge) {
        CompletableFuture.runAsync(() -> {
            try {
                SessionManager session = SessionManager.getInstance();
                ModConfig config = ModConfig.getInstance();

                // 1. Sign the challenge with the embedded private key (proof of mod authenticity)
                PrivateKey embeddedKey = CryptoUtils.getEmbeddedPrivateKey();
                if (embeddedKey == null) {
                    LOGGER.error("No embedded private key found!");
                    return;
                }
                String signature = CryptoUtils.signPayload(challenge.getBytes(StandardCharsets.UTF_8), embeddedKey);

                // 2. Mojang Verification (proof of account ownership)
                String serverId = UUID.randomUUID().toString().replace("-", ""); // Random serverId
                session.setServerId(serverId);
                
                try {
                    MinecraftSessionService sessionService = MinecraftClient.getInstance().getSessionService();
                    sessionService.joinServer(MinecraftClient.getInstance().getSession().getUuidOrNull(), MinecraftClient.getInstance().getSession().getAccessToken(), serverId);
                } catch (AuthenticationException e) {
                     LOGGER.error("Failed to join Mojang session server", e);
                     return;
                }

                AttestationPayload payload = new AttestationPayload(
                    session.getSessionId(),
                    signature,
                    serverId,
                    MinecraftClient.getInstance().getSession().getUsername(),
                    session.getModHash(),
                    session.getModVersion(),
                    System.currentTimeMillis(),
                    session.getAddedDelayMs(),
                    session.getBasePingMs()
                );

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.attestEndpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    boolean valid = json.get("valid").getAsBoolean();
                    session.setValidated(valid);
                    LOGGER.info("Attestation successful. Validated: " + valid);
                } else {
                    LOGGER.error("Attestation failed: " + response.statusCode());
                }
            } catch (Exception e) {
                LOGGER.error("Attestation error", e);
            }
        });
    }

    public static void heartbeat() {
        CompletableFuture.runAsync(() -> {
            try {
                SessionManager session = SessionManager.getInstance();
                if (session.getSessionId() == null) return;

                ModConfig config = ModConfig.getInstance();

                JsonObject payload = new JsonObject();
                payload.addProperty("sessionId", session.getSessionId());
                payload.addProperty("addedDelayMs", session.getAddedDelayMs());
                payload.addProperty("basePingMs", session.getBasePingMs());

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.heartbeatEndpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    session.setLastHeartbeatTime(System.currentTimeMillis());
                } else {
                    LOGGER.warn("Heartbeat failed: " + response.statusCode());
                }
            } catch (Exception e) {
                LOGGER.error("Heartbeat error", e);
            }
        });
    }

    public static CompletableFuture<ValidationResult> validateUser(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ModConfig config = ModConfig.getInstance();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.validateEndpoint + "/" + username))
                        .GET()
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return GSON.fromJson(response.body(), ValidationResult.class);
                } else {
                    ValidationResult error = new ValidationResult();
                    error.found = false;
                    error.status = "error";
                    return error;
                }
            } catch (Exception e) {
                LOGGER.error("Validation query error", e);
                ValidationResult error = new ValidationResult();
                error.found = false;
                error.status = "error";
                return error;
            }
        });
    }

    private static void retry(String operation, Runnable action, int attempt) {
        if (attempt > 3) {
            LOGGER.error("Failed to " + operation + " after 3 attempts");
            return;
        }
        long delay = (long) Math.pow(2, attempt) * 1000;
        LOGGER.info("Retrying " + operation + " in " + delay + "ms (attempt " + attempt + ")");
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(action);
    }
}
