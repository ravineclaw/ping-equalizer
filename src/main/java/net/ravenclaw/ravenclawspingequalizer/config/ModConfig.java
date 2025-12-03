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

    @SerializedName("a")
    public String registerEndpoint = "http://localhost:8080/api/register";
    @SerializedName("b")
    public String unregisterEndpoint = "http://localhost:8080/api/unregister";
    @SerializedName("c")
    public String attestEndpoint = "http://localhost:8080/api/attest";
    @SerializedName("d")
    public String heartbeatEndpoint = "http://localhost:8080/api/heartbeat";
    @SerializedName("e")
    public String validateEndpoint = "http://localhost:8080/api/validate";
    @SerializedName("f")
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
            // silently fail
        }
    }
}
