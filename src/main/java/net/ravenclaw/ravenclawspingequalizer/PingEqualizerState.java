package net.ravenclaw.ravenclawspingequalizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.util.Util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages ping equalization state and delay calculations.
 * Modes: ADD (fixed added RTT), TOTAL (target total RTT), MATCH (match another player's reported ping).
 */
public class PingEqualizerState {
    public enum Mode { OFF, ADD, TOTAL, MATCH }

    private static final PingEqualizerState INSTANCE = new PingEqualizerState();

    // Timing constants
    private static final long BASE_PING_MAX_AGE_MS = 250;
    private static final long PING_REQUEST_COOLDOWN_MS = 400;
    private static final long MATCH_TARGET_RESET_MS = 5000;
    private static final int MATCH_TARGET_NOISE_FLOOR_MS = 3;
    private static final int MATCH_TARGET_MIN_STEP_MS = 1;
    private static final int MATCH_TARGET_MAX_STEP_MS = 75;
    private static final double MATCH_TARGET_RATE_MS_PER_SECOND = 25.0;

    // State
    private Mode currentMode = Mode.OFF;
    private int addAmount = 0;            // For ADD mode (full RTT to add)
    private int totalTarget = 0;          // Target total RTT for TOTAL mode
    private String matchPlayerName = ""; // Player name for MATCH mode

    // Current additive delay (full RTT we are adding). Split evenly on send/receive.
    private long currentDelayMs = 0;

    // Base ping tracking (attempt to measure underlying RTT excluding our artificial delay)
    private int lastValidBasePing = 0;        // Last measured base ping (ms)
    private double smoothedBasePing = 0;      // Smoothed base ping (EMA)
    private long lastBasePingSampleTime = 0;  // Timestamp of last base ping sample (ms)
    private long lastPingRequestTime = 0;     // Timestamp of last ping request sent (ms)
    private boolean awaitingBasePing = false; // Whether we're waiting for a ping response

    // Observed ping preview (base + added delay) used in status
    private int lastObservedPing = 0;
    private long lastObservedSampleTime = 0;

    // MATCH mode smoother
    private double matchSmoothedTarget = -1;
    private long matchTargetLastUpdate = 0;

    // Pending ping packets keyed by their startTime (packet timestamp). We store send & arrival times.
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
        pendingPings.clear();
        awaitingBasePing = false;
        resetMatchSmoother();
    }

    public void setAddPing(int amount) {
        currentMode = Mode.ADD;
        addAmount = Math.max(0, amount);
        currentDelayMs = addAmount;
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
            : smoothedBasePing * 0.85 + estimatedBase * 0.15;
        lastBasePingSampleTime = now;
        awaitingBasePing = false;

        lastObservedPing = (int) Math.max(0, measuredRtt);
        lastObservedSampleTime = now;
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
        return lastValidBasePing;
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

        currentDelayMs = Math.max(0, targetPing - basePing);
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

    // Delay splitting & status remain same but include target info when available
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
        return currentDelayMs / 2;
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
}
