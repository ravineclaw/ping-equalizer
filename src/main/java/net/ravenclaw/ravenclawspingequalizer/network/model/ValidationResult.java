package net.ravenclaw.ravenclawspingequalizer.network.model;

import com.google.gson.annotations.SerializedName;

public class ValidationResult {
    @SerializedName("a")
    public boolean found;
    @SerializedName("b")
    public String status;
    @SerializedName("c")
    public String playerUsername;
    @SerializedName("d")
    public String modVersion;
    @SerializedName("e")
    public long addedDelayMs;
    @SerializedName("f")
    public long basePingMs;
    @SerializedName("g")
    public long lastValidated;

    public ValidationResult() {}

    public ValidationResult(boolean found, String status, String playerUsername, String modVersion, long addedDelayMs, long basePingMs, long lastValidated) {
        this.found = found;
        this.status = status;
        this.playerUsername = playerUsername;
        this.modVersion = modVersion;
        this.addedDelayMs = addedDelayMs;
        this.basePingMs = basePingMs;
        this.lastValidated = lastValidated;
    }

    public boolean isFound() { return found; }
    public String getStatus() { return status; }
    public String getPlayerUsername() { return playerUsername; }
    public String getModVersion() { return modVersion; }
    public long getAddedDelayMs() { return addedDelayMs; }
    public long getBasePingMs() { return basePingMs; }
    public long getLastValidated() { return lastValidated; }
}
