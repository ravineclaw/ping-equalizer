package net.ravenclaw.ravenclawspingequalizer;

import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.util.Util;

/**
 * Manages ping equalization state and delay calculations.
 * Three modes: ADD (fixed delay), STABLE (target ping), MATCH (match player).
 */
public class PingEqualizerState {
    public enum Mode {
        OFF,
        ADD,
        STABLE,
        MATCH
    }

    private static final PingEqualizerState INSTANCE = new PingEqualizerState();
    private static final long BASE_PING_MAX_AGE_MS = 200;
    private static final long OBSERVED_PING_MAX_AGE_MS = 750;
    private static final long PING_REQUEST_COOLDOWN_MS = 100;
    private static final int MATCH_TARGET_NOISE_FLOOR_MS = 3;
    private static final int MATCH_TARGET_MIN_STEP_MS = 1;
    private static final int MATCH_TARGET_MAX_STEP_MS = 75;
    private static final double MATCH_TARGET_RATE_MS_PER_SECOND = 25.0;
    private static final long MATCH_TARGET_RESET_MS = 5000;

    private Mode currentMode = Mode.OFF;
    private int addAmount = 0;
    private int stableTarget = 0;
    private String matchPlayerName = "";
    
    // The actual delay to apply (in milliseconds)
    // This is the full RTT delay we want to add, applied only on send (not split)
    private long currentDelayMs = 0;
    
    // Store the last valid base ping to handle periods where reported ping lags behind delay changes
    private int lastValidBasePing = 0;
    private double smoothedBasePing = 0;
    private long lastBasePingSampleTime = 0;
    private long lastPingRequestTime = 0;
    private boolean awaitingBasePing = false;

    private int lastObservedPing = 0;
    private long lastObservedSampleTime = 0;



    private static final class PendingPing {
        long actualSendTime = -1;
        long arrivalTime = -1;
    }

    private final Map<Long, PendingPing> pendingPings = new java.util.concurrent.ConcurrentHashMap<>();
    private int addTargetPing = -1;
    private double matchSmoothedTarget = -1;
    private long matchTargetLastUpdate = 0;

    private PingEqualizerState() {}

    public static PingEqualizerState getInstance() {
        return INSTANCE;
    }

    public Mode getMode() {
        return currentMode;
    }

    public void setOff() {
        this.currentMode = Mode.OFF;
        this.currentDelayMs = 0;
        this.addTargetPing = -1;
        this.pendingPings.clear();
        this.awaitingBasePing = false;
        resetMatchSmoother();
    }

    public void setAddPing(int amount) {
        this.currentMode = Mode.ADD;
        this.addAmount = Math.max(0, amount);
        this.addTargetPing = -1;
        clearPendingSamples();
        updateDelay(MinecraftClient.getInstance());
    }

    private void clearPendingSamples() {
        this.pendingPings.clear();
        this.awaitingBasePing = false;
    }

    private void resetMatchSmoother() {
        this.matchSmoothedTarget = -1;
        this.matchTargetLastUpdate = 0;
    }

    public void setStablePing(int target) {
        this.currentMode = Mode.STABLE;
        this.stableTarget = target;
        this.addTargetPing = -1;
        clearPendingSamples();
        updateDelay(MinecraftClient.getInstance());
    }

    public void setMatchPing(String playerName) {
        this.currentMode = Mode.MATCH;
        this.matchPlayerName = playerName;
        this.addTargetPing = -1;
        clearPendingSamples();
        resetMatchSmoother();
        updateDelay(MinecraftClient.getInstance());
    }

    public long getDelay() {
        return currentDelayMs;
    }

    public void tick(MinecraftClient client) {
        // Always update delay/base ping logic
        updateDelay(client);
    }

    public void onPingSent(long startTime) {
        if (currentMode == Mode.OFF) return;
        pendingPings.put(startTime, new PendingPing());
        lastPingRequestTime = Util.getMeasuringTimeMs();
        awaitingBasePing = true;
    }

    public void onPingActuallySent(long startTime) {
        PendingPing pending = pendingPings.get(startTime);
        if (pending != null) {
            pending.actualSendTime = Util.getMeasuringTimeMs();
        }
    }

    public void onPingArrived(long startTime) {
        PendingPing pending = pendingPings.get(startTime);
        if (pending != null) {
            pending.arrivalTime = Util.getMeasuringTimeMs();
        }
    }

    public void handlePingResult(PingResultS2CPacket packet) {
        PendingPing pending = pendingPings.remove(packet.startTime());
        if (pending == null) {
            return;
        }

        long now = Util.getMeasuringTimeMs();
        
        long sendTime = pending.actualSendTime > 0 ? pending.actualSendTime : packet.startTime();
        long arriveTime = pending.arrivalTime > 0 ? pending.arrivalTime : now;
        
        int estimatedBase = (int)Math.max(0, arriveTime - sendTime);

        this.lastValidBasePing = estimatedBase;
        if (this.smoothedBasePing == 0) {
            this.smoothedBasePing = estimatedBase;
        } else {
            this.smoothedBasePing = this.smoothedBasePing * 0.9 + estimatedBase * 0.1;
        }
        this.lastBasePingSampleTime = now;
        this.awaitingBasePing = false;
        
        // Calculate what the observed ping would be with current delay
        this.lastObservedPing = estimatedBase + (int)currentDelayMs;
        this.lastObservedSampleTime = now;
    }

    private void updateDelay(MinecraftClient client) {
        if (currentMode == Mode.OFF) {
            this.currentDelayMs = 0;
            this.addTargetPing = -1;
            return;
        }

        if (client == null) {
            client = MinecraftClient.getInstance();
        }
        if (client == null || client.player == null) {
            return;
        }

        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) {
            return;
        }

        long now = Util.getMeasuringTimeMs();
        ensurePing(handler, now);

        if (currentMode == Mode.ADD) {
            this.currentDelayMs = addAmount;
            if (hasFreshBase(now)) {
                this.addTargetPing = lastValidBasePing + addAmount;
            }
        }

        if (!hasFreshBase(now)) {
            return;
        }

        int effectiveBasePing = getCalibratedBase();
        int targetPing = computeTargetPing(handler, effectiveBasePing);

        if (currentMode != Mode.ADD) {
            if (targetPing <= 0) {
                return;
            }

            // Calculate delay needed: target - base = delay to add
            long newDelay = Math.max(0, targetPing - effectiveBasePing);
            this.currentDelayMs = newDelay;
        }
    }

    private int computeTargetPing(ClientPlayNetworkHandler handler, int basePing) {
        return switch (currentMode) {
            case ADD -> {
                if (basePing <= 0) {
                    yield -1;
                }
                addTargetPing = basePing + addAmount;
                yield addTargetPing;
            }
            case STABLE -> stableTarget > 0 ? stableTarget : basePing;
            case MATCH -> computeMatchTarget(handler);
            default -> -1;
        };
    }

    /**
     * Computes smoothed target ping for MATCH mode.
     * Uses rate-limited smoothing to reduce jitter from target player's ping fluctuations.
     */
    private int computeMatchTarget(ClientPlayNetworkHandler handler) {
        PlayerListEntry entry = findMatchEntry(handler);
        if (entry == null) {
            return -1;
        }

        int rawLatency = entry.getLatency();
        if (rawLatency <= 0) {
            return -1;
        }

        long now = Util.getMeasuringTimeMs();
        // Initialize or reset smoother if stale
        if (matchSmoothedTarget < 0 || now - matchTargetLastUpdate > MATCH_TARGET_RESET_MS) {
            matchSmoothedTarget = rawLatency;
            matchTargetLastUpdate = now;
            return rawLatency;
        }
        
        // Apply rate-limited smoothing
        double delta = rawLatency - matchSmoothedTarget;
        if (Math.abs(delta) > MATCH_TARGET_NOISE_FLOOR_MS) {
            double elapsedSeconds = Math.max(0.0, (now - matchTargetLastUpdate) / 1000.0);
            double allowedStep = elapsedSeconds * MATCH_TARGET_RATE_MS_PER_SECOND;
            allowedStep = Math.max(MATCH_TARGET_MIN_STEP_MS, Math.min(allowedStep, MATCH_TARGET_MAX_STEP_MS));
            if (Math.abs(delta) <= allowedStep) {
                matchSmoothedTarget = rawLatency;
            } else {
                matchSmoothedTarget += Math.copySign(allowedStep, delta);
            }
        }

        matchTargetLastUpdate = now;
        return (int)Math.round(matchSmoothedTarget);
    }

    private PlayerListEntry findMatchEntry(ClientPlayNetworkHandler handler) {
        if (matchPlayerName == null || matchPlayerName.isEmpty()) {
            return null;
        }

        for (PlayerListEntry entry : handler.getPlayerList()) {
            if (entry.getProfile().getName().trim().equalsIgnoreCase(matchPlayerName.trim())) {
                return entry;
            }
        }
        return null;
    }

    private boolean hasFreshBase(long now) {
        return lastValidBasePing > 0 && now - lastBasePingSampleTime <= BASE_PING_MAX_AGE_MS;
    }

    private boolean hasFreshObserved(long now) {
        return lastObservedPing > 0 && now - lastObservedSampleTime <= OBSERVED_PING_MAX_AGE_MS;
    }

    private void ensurePing(ClientPlayNetworkHandler handler, long now) {
        if (hasFreshBase(now)) {
            return;
        }
        if (awaitingBasePing && now - lastPingRequestTime < PING_REQUEST_COOLDOWN_MS) {
            return;
        }

        // If we need a ping, send one using vanilla mechanism
        handler.sendPacket(new QueryPingC2SPacket(now));
        // Note: onPingSent will be called by the mixin when this packet is sent
    }

    private int getCalibratedBase() {
        return lastValidBasePing;
    }

    public long getOutboundDelayPortion() {
        long total = currentDelayMs;
        // Split delay equally for all modes (outbound gets the extra ms if odd)
        long half = total / 2;
        return half + (total % 2);
    }

    public long getInboundDelayPortion() {
        // Split delay equally for all modes
        return currentDelayMs / 2;
    }

    /**
     * Returns a formatted status message showing current mode and ping information.
     */
    public String getStatusMessage() {
        if (currentMode == Mode.OFF) {
            return "Ping Equalizer: OFF";
        }

        long now = Util.getMeasuringTimeMs();
        int basePing = hasFreshBase(now) ? lastValidBasePing : -1;
        int observedPing = hasFreshObserved(now) ? lastObservedPing : -1;
        int addedPing = (int) currentDelayMs;

        String modeStr = switch (currentMode) {
            case ADD -> "ADD +" + addAmount + "ms";
            case STABLE -> "STABLE " + stableTarget + "ms";
            case MATCH -> "MATCH " + matchPlayerName;
            default -> "OFF";
        };

        if (basePing < 0 || observedPing < 0) {
            return String.format("Ping Equalizer: %s | Waiting for ping data...", modeStr);
        }

        return String.format("Ping Equalizer: %s | Base: %dms | Added: %dms | Total: %dms", 
                           modeStr, basePing, addedPing, observedPing);
    }

}
