package net.ravenclaw.ravenclawspingequalizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("ravenclawspingequalizer.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ModConfig instance;

    @SerializedName("registerEndpoint")
    public String registerEndpoint = "https://api.ravenclaw.net/api/register";
    @SerializedName("unregisterEndpoint")
    public String unregisterEndpoint = "https://api.ravenclaw.net/api/unregister";
    @SerializedName("attestEndpoint")
    public String attestEndpoint = "https://api.ravenclaw.net/api/attest";
    @SerializedName("heartbeatEndpoint")
    public String heartbeatEndpoint = "https://api.ravenclaw.net/api/heartbeat";
    @SerializedName("validateEndpoint")
    public String validateEndpoint = "https://api.ravenclaw.net/api/validate";
    @SerializedName("mojangSessionServer")
    public String mojangSessionServer = "https://sessionserver.mojang.com/session/minecraft/hasJoined";

    public static ModConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                instance = GSON.fromJson(json, ModConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
                instance = new ModConfig();
            }
        } else {
            instance = new ModConfig();
            save();
        }
    }

    public static void save() {
        try {
            String json = GSON.toJson(instance);
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
