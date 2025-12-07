package net.ravenclaw.ravenclawspingequalizer.cryptography;

import java.io.IOException;
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

public final class MojangApiClient {

    private static final String SESSION_SERVER = "https://sessionserver.mojang.com";
    private static final String API_SERVER = "https://api.mojang.com";
    private static final int TIMEOUT_MS = 10000;

    private MojangApiClient() {
    }

    public static CompletableFuture<String> getHasJoinedResponse(String username, String serverId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = SESSION_SERVER + "/session/minecraft/hasJoined?username=" +
                    URLEncoder.encode(username, StandardCharsets.UTF_8) +
                    "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
                return httpGet(url);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public static CompletableFuture<UUID> usernameToUuid(String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = API_SERVER + "/users/profiles/minecraft/" + URLEncoder.encode(username, StandardCharsets.UTF_8);
                String response = httpGet(url);

                if (response == null || response.isEmpty()) {
                    return null;
                }

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                if (!json.has("id")) {
                    return null;
                }

                String id = json.get("id").getAsString();
                return parseUuidWithoutDashes(id);
            } catch (Exception e) {
                return null;
            }
        });
    }

    public static CompletableFuture<String> uuidToUsername(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uuidStr = uuid.toString().replace("-", "");
                String url = SESSION_SERVER + "/session/minecraft/profile/" + uuidStr;
                String response = httpGet(url);

                if (response == null || response.isEmpty()) {
                    return null;
                }

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                if (!json.has("name")) {
                    return null;
                }

                return json.get("name").getAsString();
            } catch (Exception e) {
                return null;
            }
        });
    }

    public static UUID parseUuidFromHasJoinedResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            if (!json.has("id")) {
                return null;
            }
            return parseUuidWithoutDashes(json.get("id").getAsString());
        } catch (Exception e) {
            return null;
        }
    }

    public static String parseUsernameFromHasJoinedResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            if (!json.has("name")) {
                return null;
            }
            return json.get("name").getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID parseUuidWithoutDashes(String id) {
        if (id == null || id.length() != 32) {
            return null;
        }
        String formatted = id.substring(0, 8) + "-" + id.substring(8, 12) + "-" +
            id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20);
        return UUID.fromString(formatted);
    }

    private static String httpGet(String urlString) throws IOException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "RavenclawsPingEqualizer/1.0");

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

