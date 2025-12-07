package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ApiService {

    private static final String API_BASE_URL = "https://api.ravenclaw.net";
    private static final int TIMEOUT_MS = 10000;
    private static final Gson GSON = new Gson();

    private ApiService() {
    }

    public static CompletableFuture<HashValidationResponse> validateModHash(String modHash) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = API_BASE_URL + "/api/hash/" + URLEncoder.encode(modHash, StandardCharsets.UTF_8);
                String response = httpGet(url);

                if (response == null || response.isEmpty()) {
                    return new HashValidationResponse(false, null);
                }

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                boolean isValid = json.has("is_valid") && json.get("is_valid").getAsBoolean();
                String signature = json.has("signature") ? json.get("signature").getAsString() : null;

                return new HashValidationResponse(isValid, signature);
            } catch (Exception e) {
                return new HashValidationResponse(false, null);
            }
        });
    }

    public record HashValidationResponse(boolean isValid, String signature) {
    }

    public static CompletableFuture<Boolean> sendHeartbeat(HeartbeatPayload payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = API_BASE_URL + "/api/heartbeat";
                String jsonPayload = GSON.toJson(payload);
                String response = httpPost(url, jsonPayload);

                if (response == null || response.isEmpty()) {
                    return false;
                }

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                return json.has("success") && json.get("success").getAsBoolean();
            } catch (Exception e) {
                return false;
            }
        });
    }

    public record HeartbeatPayload(
        String modHash,
        String playerUuid,
        String username,
        long timestamp,
        String currentServer,
        String modStatus,
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
            String signature,
            String peMode,
            int peDelay,
            int peBasePing,
            int peTotalPing
        ) {
            return new HeartbeatPayload(
                modHash,
                attestation.getPlayerUuid().toString(),
                attestation.getUsername(),
                System.currentTimeMillis(),
                currentServer,
                modStatus,
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

    public static CompletableFuture<PlayerValidationResult> validatePlayerByUuid(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = API_BASE_URL + "/api/validate/" + playerUuid.toString();
                String response = httpGet(url);
                if (response == null) {
                    return PlayerValidationResult.invalid();
                }
                return parseValidationResponse(response);
            } catch (java.net.SocketTimeoutException | java.net.ConnectException | java.net.UnknownHostException e) {
                return PlayerValidationResult.unreachable();
            } catch (Exception e) {
                return PlayerValidationResult.invalid();
            }
        });
    }

    public static CompletableFuture<PlayerValidationResult> validatePlayerByUsername(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = API_BASE_URL + "/api/validate/" + URLEncoder.encode(username, StandardCharsets.UTF_8);
                String response = httpGet(url);
                if (response == null) {
                    return PlayerValidationResult.invalid();
                }
                return parseValidationResponse(response);
            } catch (java.net.SocketTimeoutException | java.net.ConnectException | java.net.UnknownHostException e) {
                return PlayerValidationResult.unreachable();
            } catch (Exception e) {
                return PlayerValidationResult.invalid();
            }
        });
    }

    private static PlayerValidationResult parseValidationResponse(String response) {
        if (response == null || response.isEmpty()) {
            return PlayerValidationResult.unreachable();
        }

        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();

            // Check if the response has expected API fields - if not, we likely hit a non-API server
            if (!json.has("is_connected") && !json.has("uuid") && !json.has("username")) {
                return PlayerValidationResult.unreachable();
            }

            boolean isConnected = json.has("is_connected") && json.get("is_connected").getAsBoolean();
            boolean isHashCorrect = json.has("is_hash_correct") && json.get("is_hash_correct").getAsBoolean();
            boolean isSignatureCorrect = json.has("is_signature_correct") && json.get("is_signature_correct").getAsBoolean();
            String modStatus = json.has("mod_status") ? json.get("mod_status").getAsString() : "unknown";
            String currentServer = json.has("current_server") ? json.get("current_server").getAsString() : "";
            String username = json.has("username") ? json.get("username").getAsString() : "";
            String uuid = json.has("uuid") ? json.get("uuid").getAsString() : "";
            long lastHeartbeat = json.has("last_heartbeat") ? json.get("last_heartbeat").getAsLong() : 0;
            String peMode = json.has("pe_mode") ? json.get("pe_mode").getAsString() : "unknown";
            int peDelay = json.has("pe_delay") ? json.get("pe_delay").getAsInt() : 0;
            int peBasePing = json.has("pe_base_ping") ? json.get("pe_base_ping").getAsInt() : 0;
            int peTotalPing = json.has("pe_total_ping") ? json.get("pe_total_ping").getAsInt() : 0;

            return new PlayerValidationResult(
                isConnected,
                isHashCorrect,
                isSignatureCorrect,
                modStatus,
                currentServer,
                username,
                uuid,
                lastHeartbeat,
                peMode,
                peDelay,
                peBasePing,
                peTotalPing,
                false
            );
        } catch (Exception e) {
            // JSON parsing failed or unexpected response structure - server unreachable or wrong endpoint
            return PlayerValidationResult.unreachable();
        }
    }

    public record PlayerValidationResult(
        boolean isConnected,
        boolean isHashCorrect,
        boolean isSignatureCorrect,
        String modStatus,
        String currentServer,
        String username,
        String uuid,
        long lastHeartbeat,
        String peMode,
        int peDelay,
        int peBasePing,
        int peTotalPing,
        boolean serverUnreachable
    ) {
        public boolean isValid() {
            return isConnected && isHashCorrect;
        }

        public boolean isFullyValid() {
            return isConnected && isHashCorrect && isSignatureCorrect;
        }

        public boolean isServerUnreachable() {
            return serverUnreachable;
        }

        public static PlayerValidationResult invalid() {
            return new PlayerValidationResult(false, false, false, "unknown", "", "", "", 0, "unknown", 0, 0, 0, false);
        }

        public static PlayerValidationResult unreachable() {
            return new PlayerValidationResult(false, false, false, "unknown", "", "", "", 0, "unknown", 0, 0, 0, true);
        }
    }

    private static String httpGet(String urlString) throws IOException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "RavenclawsPingEqualizer/1.0");
        conn.setRequestProperty("Accept", "application/json");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            return null;
        }

        try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static String httpPost(String urlString, String jsonPayload) throws IOException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "RavenclawsPingEqualizer/1.0");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            return null;
        }

        try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }
}



