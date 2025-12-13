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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ApiService {

    private static final String DEFAULT_API_BASE_URL = "http://localhost:3000";
    private static final String TUNNEL_URL_GIST_ID = "0de120a0cfc62d7a98f7b0c752602293";
    private static final String TUNNEL_URL_GIST_FILENAME = "tunnel-url.txt";
    private static final String TUNNEL_URL_GIST_API =
        "https://api.github.com/gists/" + TUNNEL_URL_GIST_ID;
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_REDIRECTS = 5;

    private static volatile String apiBaseUrl = DEFAULT_API_BASE_URL;
    private static volatile String cachedRemoteBaseUrl;
    private ApiService() {
    }

    public static String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public static void setApiBaseUrl(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized != null) {
            apiBaseUrl = normalized;
        }
    }

    public static CompletableFuture<String> refreshApiBaseUrlFromGistAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String text = loadTunnelUrlFromGist();
                String normalized = normalizeBaseUrl(text);
                if (normalized == null) {
                    return null;
                }
                setApiBaseUrl(normalized);
                cachedRemoteBaseUrl = normalized;
                org.slf4j.LoggerFactory.getLogger("PingEqualizer")
                    .info("API endpoint set to {}", normalized);
                return normalized;
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger("PingEqualizer")
                    .warn("Failed to load API endpoint from gist: {}", e.getMessage());
                return null;
            }
        });
    }

    public static CompletableFuture<HashValidationResponse> validateModHash(String modHash) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String path = "/api/hash/" + URLEncoder.encode(modHash, StandardCharsets.UTF_8);
                String response = httpGetApiPath(path);

                if (response == null || response.isEmpty()) {
                    return new HashValidationResponse(false, null, null);
                }

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                boolean isValid = json.has("is_valid") && json.get("is_valid").getAsBoolean();
                String signature = json.has("signature") ? json.get("signature").getAsString() : null;
                String version = json.has("version") && !json.get("version").isJsonNull()
                    ? json.get("version").getAsString()
                    : null;

                return new HashValidationResponse(isValid, signature, version);
            } catch (Exception e) {
                return new HashValidationResponse(false, null, null);
            }
        });
    }

    public record HashValidationResponse(boolean isValid, String signature, String version) {
    }

    public static CompletableFuture<Boolean> sendHeartbeat(HeartbeatPayload payload) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String jsonPayload = toHeartbeatJson(payload);
                String response = httpPostApiPath("/api/heartbeat", jsonPayload);

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

    // Build the heartbeat JSON by hand to avoid any reflection/annotation reliance under obfuscation.
    private static String toHeartbeatJson(HeartbeatPayload payload) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJsonString(sb, "modHash", payload.modHash());
        appendJsonString(sb, "playerUuid", payload.playerUuid());
        appendJsonString(sb, "username", payload.username());
        appendJsonNumber(sb, "timestamp", payload.timestamp());
        appendJsonString(sb, "currentServer", payload.currentServer());
        appendJsonString(sb, "modStatus", payload.modStatus());
        appendJsonString(sb, "modVersion", payload.modVersion());
        appendJsonString(sb, "minecraftProof", payload.minecraftProof());
        appendJsonString(sb, "serverId", payload.serverId());
        appendJsonString(sb, "signature", payload.signature());
        appendJsonBoolean(sb, "isSigned", payload.isSigned());
        appendJsonString(sb, "peMode", payload.peMode());
        appendJsonNumber(sb, "peDelay", payload.peDelay());
        appendJsonNumber(sb, "peBasePing", payload.peBasePing());
        appendJsonNumber(sb, "peTotalPing", payload.peTotalPing());
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonString(StringBuilder sb, String key, String value) {
        sb.append('"').append(key).append('"').append(':');
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escapeJson(value)).append('"');
        }
        sb.append(',');
    }

    private static void appendJsonNumber(StringBuilder sb, String key, long value) {
        sb.append('"').append(key).append('"').append(':').append(value).append(',');
    }

    private static void appendJsonNumber(StringBuilder sb, String key, int value) {
        sb.append('"').append(key).append('"').append(':').append(value).append(',');
    }

    private static void appendJsonBoolean(StringBuilder sb, String key, boolean value) {
        sb.append('"').append(key).append('"').append(':').append(value).append(',');
    }

    private static String escapeJson(String input) {
        StringBuilder out = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    // HeartbeatPayload record moved to its own file


    public static CompletableFuture<PlayerValidationResult> validatePlayerByUuid(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String response = httpGetApiPath("/api/validate/" + playerUuid);
                if (response == null) {
                    // 404 - player not found but server is reachable
                    return PlayerValidationResult.invalid();
                }
                return parseValidationResponse(response);
            } catch (java.io.IOException e) {
                // Network issues, timeouts, connection refused, unknown host, unexpected response codes
                return PlayerValidationResult.unreachable();
            } catch (Exception e) {
                // Other unexpected errors - treat as unreachable
                return PlayerValidationResult.unreachable();
            }
        });
    }

    public static CompletableFuture<PlayerValidationResult> validatePlayerByUsername(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String response = httpGetApiPath("/api/validate/" + URLEncoder.encode(username, StandardCharsets.UTF_8));
                if (response == null) {
                    // 404 - player not found but server is reachable
                    return PlayerValidationResult.invalid();
                }
                return parseValidationResponse(response);
            } catch (java.io.IOException e) {
                // Network issues, timeouts, connection refused, unknown host, unexpected response codes
                return PlayerValidationResult.unreachable();
            } catch (Exception e) {
                // Other unexpected errors - treat as unreachable
                return PlayerValidationResult.unreachable();
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
            String modVersion = json.has("mod_version") ? json.get("mod_version").getAsString() : "";

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
                modVersion,
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
        String modVersion,
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
            return new PlayerValidationResult(false, false, false, "unknown", "", "", "", 0, "unknown", 0, 0, 0, "", false);
        }

        public static PlayerValidationResult unreachable() {
            return new PlayerValidationResult(false, false, false, "unknown", "", "", "", 0, "unknown", 0, 0, 0, "", true);
        }
    }

    private static String httpGetApiPath(String path) throws IOException {
        String currentBase = getApiBaseUrl();
        try {
            return httpGet(currentBase + path);
        } catch (IOException first) {
            String alternateBase = resolveAlternateBaseUrl(currentBase);
            if (alternateBase == null || alternateBase.equals(currentBase)) {
                throw first;
            }
            try {
                String response = httpGet(alternateBase + path);
                setApiBaseUrl(alternateBase);
                return response;
            } catch (IOException ignored) {
                throw first;
            }
        }
    }

    private static String httpPostApiPath(String path, String jsonPayload) throws IOException {
        String currentBase = getApiBaseUrl();
        try {
            return httpPost(currentBase + path, jsonPayload);
        } catch (IOException first) {
            String alternateBase = resolveAlternateBaseUrl(currentBase);
            if (alternateBase == null || alternateBase.equals(currentBase)) {
                throw first;
            }
            try {
                String response = httpPost(alternateBase + path, jsonPayload);
                setApiBaseUrl(alternateBase);
                return response;
            } catch (IOException ignored) {
                throw first;
            }
        }
    }

    private static String loadTunnelUrlFromGist() throws IOException {
        String gistJson = httpGetText(TUNNEL_URL_GIST_API, "application/vnd.github+json");
        if (gistJson == null || gistJson.isBlank()) {
            return null;
        }

        JsonObject gist = JsonParser.parseString(gistJson).getAsJsonObject();
        if (!gist.has("files") || !gist.get("files").isJsonObject()) {
            return null;
        }

        JsonObject files = gist.getAsJsonObject("files");
        if (!files.has(TUNNEL_URL_GIST_FILENAME) || !files.get(TUNNEL_URL_GIST_FILENAME).isJsonObject()) {
            return null;
        }

        JsonObject file = files.getAsJsonObject(TUNNEL_URL_GIST_FILENAME);
        if (file.has("content") && !file.get("content").isJsonNull()) {
            String content = file.get("content").getAsString();
            if (content != null && !content.isBlank()) {
                return content;
            }
        }

        if (file.has("raw_url") && !file.get("raw_url").isJsonNull()) {
            String rawUrl = file.get("raw_url").getAsString();
            if (rawUrl != null && !rawUrl.isBlank()) {
                return httpGetText(rawUrl, "text/plain,*/*");
            }
        }

        return null;
    }

    private static String resolveAlternateBaseUrl(String currentBaseUrl) {
        if (currentBaseUrl == null) {
            return null;
        }

        if (!DEFAULT_API_BASE_URL.equals(currentBaseUrl)) {
            return DEFAULT_API_BASE_URL;
        }

        String cached = cachedRemoteBaseUrl;
        if (cached != null && !cached.isBlank() && !DEFAULT_API_BASE_URL.equals(cached)) {
            return cached;
        }

        try {
            String remote = normalizeBaseUrl(loadTunnelUrlFromGist());
            if (remote != null && !DEFAULT_API_BASE_URL.equals(remote)) {
                cachedRemoteBaseUrl = remote;
                return remote;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static String httpGet(String urlString) throws IOException {
        return httpGetText(urlString, "application/json");
    }

    private static String httpPost(String urlString, String jsonPayload) throws IOException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "RavenclawsPingEqualizer/1.0");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("Unexpected response code: " + responseCode);
        }

        try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        }
    }

    private static String httpGetText(String urlString, String acceptHeader) throws IOException {
        URI current = URI.create(urlString);
        for (int redirectCount = 0; redirectCount < MAX_REDIRECTS; redirectCount++) {
            URL url = current.toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "RavenclawsPingEqualizer/1.0");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Accept", acceptHeader);

            int responseCode = conn.getResponseCode();
            if (responseCode == 404) {
                return null;
            }
            if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isBlank()) {
                    throw new IOException("Redirect without Location");
                }
                current = current.resolve(location);
                continue;
            }
            if (responseCode != 200) {
                throw new IOException("Unexpected response code: " + responseCode);
            }

            try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                return scanner.hasNext() ? scanner.next() : "";
            }
        }
        throw new IOException("Too many redirects");
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String candidate = baseUrl.trim();
        if (candidate.isEmpty()) {
            return null;
        }
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }

        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (Exception e) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            return null;
        }
        String lowerScheme = scheme.toLowerCase();
        if (!lowerScheme.equals("http") && !lowerScheme.equals("https")) {
            return null;
        }
        if (uri.getHost() == null && uri.getAuthority() == null) {
            return null;
        }
        return candidate;
    }
}
