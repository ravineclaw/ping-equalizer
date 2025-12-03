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
import net.ravenclaw.ravenclawspingequalizer.obfuscation.S;

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
                payload.addProperty(S.F_USERNAME(), MinecraftClient.getInstance().getSession().getUsername());
                payload.addProperty(S.F_MOD_VERSION(), session.getModVersion());
                payload.addProperty(S.F_MOD_HASH(), session.getModHash());
                payload.addProperty("k", CryptoUtils.serializePublicKey(session.getSessionKeyPair().getPublic()));

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.registerEndpoint))
                        .header(S.CONTENT_TYPE(), S.APP_JSON())
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    String sessionId = json.get("a").getAsString();
                    String challenge = json.get("b").getAsString();
                    
                    if (json.has("s")) {
                        String signature = json.get("s").getAsString();
                        String dataToVerify = sessionId + challenge;
                        if (!CryptoUtils.verifyServerSignature(dataToVerify, signature)) {
                            return;
                        }
                    }

                    session.setSessionId(sessionId);
                    attest(challenge);
                } else {
                    retry(() -> register(attempt + 1), attempt);
                }
            } catch (Exception e) {
                retry(() -> register(attempt + 1), attempt);
            }
        });
    }

    public static void unregister() {
        try {
            SessionManager session = SessionManager.getInstance();
            if (session.getSessionId() == null) return;
            
            ModConfig config = ModConfig.getInstance();
            JsonObject payload = new JsonObject();
            payload.addProperty("a", session.getSessionId());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.unregisterEndpoint))
                    .header(S.CONTENT_TYPE(), S.APP_JSON())
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .build();

            CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // silent
        }
    }

    public static void attest(String challenge) {
        CompletableFuture.runAsync(() -> {
            try {
                SessionManager session = SessionManager.getInstance();
                ModConfig config = ModConfig.getInstance();

                PrivateKey embeddedKey = CryptoUtils.getEmbeddedPrivateKey();
                if (embeddedKey == null) {
                    return;
                }
                String signature = CryptoUtils.signPayload(challenge.getBytes(StandardCharsets.UTF_8), embeddedKey);

                String serverId = UUID.randomUUID().toString().replace("-", "");
                session.setServerId(serverId);
                
                try {
                    MinecraftSessionService sessionService = MinecraftClient.getInstance().getSessionService();
                    sessionService.joinServer(MinecraftClient.getInstance().getSession().getUuidOrNull(), MinecraftClient.getInstance().getSession().getAccessToken(), serverId);
                } catch (AuthenticationException e) {
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
                        .header(S.CONTENT_TYPE(), S.APP_JSON())
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                    boolean valid = json.get("v").getAsBoolean();
                    session.setValidated(valid);
                }
            } catch (Exception e) {
                // silent
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
                payload.addProperty("a", session.getSessionId());
                payload.addProperty("h", session.getAddedDelayMs());
                payload.addProperty("i", session.getBasePingMs());

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.heartbeatEndpoint))
                        .header(S.CONTENT_TYPE(), S.APP_JSON())
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    session.setLastHeartbeatTime(System.currentTimeMillis());
                }
            } catch (Exception e) {
                // silent
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
                    error.status = "x";
                    return error;
                }
            } catch (Exception e) {
                ValidationResult error = new ValidationResult();
                error.found = false;
                error.status = "x";
                return error;
            }
        });
    }

    private static void retry(Runnable action, int attempt) {
        if (attempt > 3) {
            return;
        }
        long delay = (long) Math.pow(2, attempt) * 1000;
        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(action);
    }
}
