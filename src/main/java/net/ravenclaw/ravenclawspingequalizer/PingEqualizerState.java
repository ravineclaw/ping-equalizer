package net.ravenclaw.ravenclawspingequalizer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.util.Util;

/**
 * Manages ping equalization state and delay calculations.
 * Modes: ADD (fixed added RTT), TOTAL (target total RTT), MATCH (match another player's reported ping).
 */
public class PingEqualizerState {
    public enum Mode { OFF, ADD, TOTAL, MATCH }

    private static final PingEqualizerState INSTANCE = new PingEqualizerState();

    private static final long BASE_PING_MAX_AGE_MS = 250;
    private static final long PING_REQUEST_COOLDOWN_MS = 400;
    private static final long MATCH_TARGET_RESET_MS = 5000;
    private static final int MATCH_TARGET_NOISE_FLOOR_MS = 3;
    private static final int MATCH_TARGET_MIN_STEP_MS = 1;
    private static final int MATCH_TARGET_MAX_STEP_MS = 75;
    private static final double MATCH_TARGET_RATE_MS_PER_SECOND = 25.0;

    private Mode currentMode = Mode.OFF;
    private int addAmount = 0;
    private int totalTarget = 0;
    private String matchPlayerName = "";

    private long currentDelayMs = 0;
    private double preciseDelay = 0;

    private int lastValidBasePing = 0;
    private double smoothedBasePing = 0;
    private long lastBasePingSampleTime = 0;
    private long lastPingRequestTime = 0;
    private boolean awaitingBasePing = false;

    private double matchSmoothedTarget = -1;
    private long matchTargetLastUpdate = 0;

    private static final class PendingPing {
        long appliedDelayMs;
        long actualSendTime = -1;
        long arrivalTime = -1;
        long outboundDelayMs = 0;
        long inboundDelayMs = 0;
    }
    private final Map<Long, PendingPing> pendingPings = new ConcurrentHashMap<>();

    private PingEqualizerState() {}
    public static PingEqualizerState getInstance() { return INSTANCE; }

    // Mode setters
    public void setOff() {
        currentMode = Mode.OFF;
        currentDelayMs = 0;
        preciseDelay = 0;
        resetMeasurementState();
        resetMatchSmoother();
    }

    public void setAddPing(int amount) {
        currentMode = Mode.ADD;
        addAmount = Math.max(0, amount);
        currentDelayMs = addAmount;
        preciseDelay = addAmount;
    }

    public void setTotalPing(int target) {
        currentMode = Mode.TOTAL;
        totalTarget = Math.max(0, target);
        updateDelay(MinecraftClient.getInstance());
    }

    public void setMatchPing(String playerName) {
        currentMode = Mode.MATCH;
        matchPlayerName = playerName == null ? "" : playerName.trim();
        resetMatchSmoother();
        updateDelay(MinecraftClient.getInstance());
    }

    public void suspendForProtocolChange() {
        resetMeasurementState();
        resetMatchSmoother();
    }

    public void prepareForNewPlaySession() {
        resetMeasurementState();
        resetMatchSmoother();
        if (currentMode == Mode.ADD) {
            currentDelayMs = addAmount;
            preciseDelay = addAmount;
        }
    }

    public void onPingSent(long startTime) {
        if (currentMode == Mode.OFF) return;
        PendingPing pending = new PendingPing();
        pending.appliedDelayMs = currentDelayMs;
        pending.outboundDelayMs = getOutboundDelayPortion();
        pending.inboundDelayMs = getInboundDelayPortion();
        pendingPings.put(startTime, pending);
        lastPingRequestTime = Util.getMeasuringTimeMs();
        awaitingBasePing = true;
    }

    public void onPingActuallySent(long startTime) {
        PendingPing p = pendingPings.get(startTime);
        if (p != null) {
            p.actualSendTime = Util.getMeasuringTimeMs();
        }
    }

    public void onPingArrived(long startTime) {
        PendingPing p = pendingPings.get(startTime);
        if (p != null) {
            p.arrivalTime = Util.getMeasuringTimeMs();
        }
    }

    public void handlePingResult(PingResultS2CPacket packet) {
        PendingPing p = pendingPings.remove(packet.startTime());
        if (p == null) {
            return;
        }

        long now = Util.getMeasuringTimeMs();
        long sendTime = p.actualSendTime > 0 ? p.actualSendTime : packet.startTime();
        long arriveTime = p.arrivalTime > 0 ? p.arrivalTime : now;
        long measuredRtt = Math.max(0, arriveTime - sendTime);
        long estimatedBase = Math.max(0, measuredRtt - (p.outboundDelayMs + p.inboundDelayMs));

        lastValidBasePing = (int) estimatedBase;
        smoothedBasePing = smoothedBasePing == 0
            ? estimatedBase
            : smoothedBasePing * 0.95 + estimatedBase * 0.05;
        lastBasePingSampleTime = now;
        awaitingBasePing = false;
    }

    public void tick(MinecraftClient client) {
        updateDelay(client);
    }

    private void resetMatchSmoother() {
        matchSmoothedTarget = -1;
        matchTargetLastUpdate = 0;
    }

    private boolean hasFreshBase(long now) {
        return lastValidBasePing > 0 && now - lastBasePingSampleTime <= BASE_PING_MAX_AGE_MS;
    }

    private int getCalibratedBase() {
        return (int) Math.round(smoothedBasePing > 0 ? smoothedBasePing : lastValidBasePing);
    }

    private int computeTargetPing(ClientPlayNetworkHandler handler, int basePing) {
        return switch (currentMode) {
            case TOTAL -> totalTarget > 0 ? totalTarget : basePing;
            case MATCH -> computeMatchTarget(handler);
            default -> -1;
        };
    }

    private void updateDelay(MinecraftClient client) {
        if (currentMode == Mode.OFF) {
            currentDelayMs = 0;
            return;
        }
        if (currentMode == Mode.ADD) {
            currentDelayMs = addAmount;
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

        requestPingIfNeeded(handler, false);

        long now = Util.getMeasuringTimeMs();
        if (!hasFreshBase(now)) {
            return;
        }

        int basePing = getCalibratedBase();
        int targetPing = computeTargetPing(handler, basePing);
        if (targetPing <= 0) {
            return;
        }

        long targetDelay = Math.max(0, targetPing - basePing);

        if (preciseDelay != targetDelay) {
            double diff = targetDelay - preciseDelay;
            double maxStep = 5.0; // Max 5ms change per tick
            double step = diff * 0.1; // 10% approach
            
            if (Math.abs(step) > maxStep) {
                step = Math.signum(step) * maxStep;
            }
            
            preciseDelay += step;
            
            if (Math.abs(targetDelay - preciseDelay) < 0.5) {
                preciseDelay = targetDelay;
            }
            
            currentDelayMs = (long) Math.round(preciseDelay);
        }
    }

    private void requestPingIfNeeded(ClientPlayNetworkHandler handler, boolean force) {
        long now = Util.getMeasuringTimeMs();
        if (!force) {
            if (hasFreshBase(now)) {
                return;
            }
            if (awaitingBasePing && now - lastPingRequestTime < PING_REQUEST_COOLDOWN_MS) {
                return;
            }
        }
        handler.sendPacket(new QueryPingC2SPacket(now));
    }

    public String getStatusMessage() {
        if (currentMode == Mode.OFF) {
            return "Ping Equalizer: OFF";
        }

        long now = Util.getMeasuringTimeMs();
        String modeStr = switch (currentMode) {
            case ADD -> "ADD +" + addAmount + "ms";
            case TOTAL -> "TOTAL " + totalTarget + "ms";
            case MATCH -> "MATCH " + matchPlayerName;
            default -> "OFF";
        };

        if (currentMode == Mode.ADD) {
            return String.format("Ping Equalizer: %s | Added: %dms", modeStr, currentDelayMs);
        }

        if (!hasFreshBase(now)) {
            return String.format("Ping Equalizer: %s | Measuring base ping...", modeStr);
        }

        int base = lastValidBasePing;
        int added = (int) currentDelayMs;
        int total = base + added;
        return String.format("Ping Equalizer: %s | Base: %dms | Added: %dms | Total: %dms", modeStr, base, added, total);
    }

    public Mode getMode() {
        return currentMode;
    }

    public long getOutboundDelayPortion() {
        return currentDelayMs / 2;
    }

    public long getInboundDelayPortion() {
        return currentDelayMs - getOutboundDelayPortion();
    }

    private int computeMatchTarget(ClientPlayNetworkHandler handler) {
        PlayerListEntry entry = findMatchEntry(handler);
        if (entry == null) {
            return -1;
        }
        int raw = entry.getLatency();
        if (raw <= 0) {
            return -1;
        }
        long now = Util.getMeasuringTimeMs();
        if (matchSmoothedTarget < 0 || now - matchTargetLastUpdate > MATCH_TARGET_RESET_MS) {
            matchSmoothedTarget = raw;
            matchTargetLastUpdate = now;
            return raw;
        }
        double delta = raw - matchSmoothedTarget;
        if (Math.abs(delta) > MATCH_TARGET_NOISE_FLOOR_MS) {
            double elapsedSeconds = Math.max(0, (now - matchTargetLastUpdate) / 1000.0);
            double allowed = elapsedSeconds * MATCH_TARGET_RATE_MS_PER_SECOND;
            allowed = Math.max(MATCH_TARGET_MIN_STEP_MS, Math.min(allowed, MATCH_TARGET_MAX_STEP_MS));
            if (Math.abs(delta) <= allowed) {
                matchSmoothedTarget = raw;
            } else {
                matchSmoothedTarget += Math.copySign(allowed, delta);
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
            if (entry.getProfile().getName().equalsIgnoreCase(matchPlayerName)) {
                return entry;
            }
        }
        return null;
    }

    public void recordPingOutboundDelay(long startTime, long delayMs) {
        PendingPing pending = pendingPings.get(startTime);
        if (pending != null) {
            pending.outboundDelayMs = delayMs;
        }
    }

    public void recordPingInboundDelay(long startTime, long delayMs) {
        PendingPing pending = pendingPings.get(startTime);
        if (pending != null) {
            pending.inboundDelayMs = delayMs;
        }
    }

    private void resetMeasurementState() {
        pendingPings.clear();
        awaitingBasePing = false;
        lastPingRequestTime = 0;
        lastValidBasePing = 0;
        smoothedBasePing = 0;
        lastBasePingSampleTime = 0;
    }
}
