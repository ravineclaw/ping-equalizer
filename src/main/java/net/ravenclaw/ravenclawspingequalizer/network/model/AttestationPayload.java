package net.ravenclaw.ravenclawspingequalizer.network.model;

import com.google.gson.annotations.SerializedName;

public class AttestationPayload {
    @SerializedName("sessionId")
    public String sessionId;
    @SerializedName("challengeResponse")
    public String challengeResponse;
    @SerializedName("mojangProof")
    public String mojangProof;
    @SerializedName("username")
    public String username;
    @SerializedName("modHash")
    public String modHash;
    @SerializedName("modVersion")
    public String modVersion;
    @SerializedName("timestamp")
    public long timestamp;
    @SerializedName("addedDelayMs")
    public long addedDelayMs;
    @SerializedName("basePingMs")
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
