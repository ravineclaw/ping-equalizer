package net.ravenclaw.ravenclawspingequalizer.network.model;

import com.google.gson.annotations.SerializedName;

public class AttestationPayload {
    @SerializedName("a")
    public String sessionId;
    @SerializedName("b")
    public String challengeResponse;
    @SerializedName("c")
    public String mojangProof;
    @SerializedName("d")
    public String username;
    @SerializedName("e")
    public String modHash;
    @SerializedName("f")
    public String modVersion;
    @SerializedName("g")
    public long timestamp;
    @SerializedName("h")
    public long addedDelayMs;
    @SerializedName("i")
    public long basePingMs;

    public AttestationPayload(String sessionId, String challengeResponse, String mojangProof, String username, String modHash, String modVersion, long timestamp, long addedDelayMs, long basePingMs) {
        this.sessionId = sessionId;
        this.challengeResponse = challengeResponse;
        this.mojangProof = mojangProof;
        this.username = username;
        this.modHash = modHash;
        this.modVersion = modVersion;
        this.timestamp = timestamp;
        this.addedDelayMs = addedDelayMs;
        this.basePingMs = basePingMs;
    }
}
