package net.ravenclaw.ravenclawspingequalizer.session;

import java.security.KeyPair;

public class SessionManager {
    private static SessionManager instance;

    private String sessionId;
    private KeyPair sessionKeyPair;
    private boolean isValidated;
    private long lastHeartbeatTime;
    private String serverId;
    
    // Metrics
    private String modVersion;
    private String modHash;
    private long addedDelayMs;
    private long basePingMs;

    private SessionManager() {
        this.isValidated = false;
    }

    public static synchronized SessionManager getInstance() {
        if (instance == null) {
            instance = new SessionManager();
        }
        return instance;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public KeyPair getSessionKeyPair() {
        return sessionKeyPair;
    }

    public void setSessionKeyPair(KeyPair sessionKeyPair) {
        this.sessionKeyPair = sessionKeyPair;
    }

    public boolean isValidated() {
        return isValidated;
    }

    public void setValidated(boolean validated) {
        isValidated = validated;
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public void setLastHeartbeatTime(long lastHeartbeatTime) {
        this.lastHeartbeatTime = lastHeartbeatTime;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getModVersion() {
        return modVersion;
    }

    public void setModVersion(String modVersion) {
        this.modVersion = modVersion;
    }

    public String getModHash() {
        return modHash;
    }

    public void setModHash(String modHash) {
        this.modHash = modHash;
    }

    public long getAddedDelayMs() {
        return addedDelayMs;
    }

    public void setAddedDelayMs(long addedDelayMs) {
        this.addedDelayMs = addedDelayMs;
    }

    public long getBasePingMs() {
        return basePingMs;
    }

    public void setBasePingMs(long basePingMs) {
        this.basePingMs = basePingMs;
    }
}
