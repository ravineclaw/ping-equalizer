package net.ravenclaw.ravenclawspingequalizer;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.minecraft.util.Util;

public class PingEqualizerState {
    public enum Mode { OFF, ADD, TOTAL }

    private static final PingEqualizerState INSTANCE = new PingEqualizerState();

    private static final long BASE_PING_MAX_AGE_MS = 1500;
    private static final long PING_REQUEST_COOLDOWN_MS = 1000;
    private static final long DELAY_UPDATE_MIN_INTERVAL_MS = 150;
    private static final long DELAY_HYSTERESIS_MS = 2;
    private static final int DELAY_QUANTUM_MS = 2;
    private static final double BASE_PING_ALPHA = 0.07;
    private static final double BASE_PING_MAX_STEP_MS = 25.0;
    private static final int BASE_FILTER_WINDOW = 5;
    private static final int MAX_ADDED_PING_MS = 400;

    private Mode currentMode = Mode.OFF;
    private int addAmount = 0;
    private int totalTarget = 0;

    private long currentDelayMs = 0;
    private double preciseDelay = 0;
    private long lastDelayUpdateTimeMs = 0;

    private int lastValidBasePing = 0;
    private double smoothedBasePing = 0;
    private long lastBasePingSampleTime = 0;
    private long lastPingRequestTime = 0;
    private boolean awaitingBasePing = false;

    private long lastMeasuredRtt = -1;

    private final int[] baseEstimateWindow = new int[BASE_FILTER_WINDOW];
    private int baseEstimateCount = 0;
    private int baseEstimateIndex = 0;

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

    public void setOff() {
        currentMode = Mode.OFF;
        currentDelayMs = 0;
        preciseDelay = 0;
        resetMeasurementState();
    }

    public void setAddPing(int amount) {
        currentMode = Mode.ADD;
        addAmount = clampAddedPing(amount);
        preciseDelay = addAmount;
        currentDelayMs = quantizeDelayMs(preciseDelay);
        lastDelayUpdateTimeMs = Util.getMeasuringTimeMs();
    }

    public void setTotalPing(int target) {
        int normalizedTarget = Math.max(0, target);
        boolean preserveDelay = currentMode == Mode.TOTAL && normalizedTarget == totalTarget;

        currentMode = Mode.TOTAL;
        totalTarget = normalizedTarget;

        long now = Util.getMeasuringTimeMs();
        if (!preserveDelay) {
            resetMeasurementState();
        }
        lastDelayUpdateTimeMs = now;

        MinecraftClient client = MinecraftClient.getInstance();
        int baseEstimate = estimateInitialBasePing(client);
        if (baseEstimate > 0) {
            seedBaseEstimate(baseEstimate);
            double targetDelay = clampAddedPing((int) Math.max(0, totalTarget - baseEstimate));
            if (!preserveDelay || targetDelay > preciseDelay) {
                preciseDelay = targetDelay;
            }
            currentDelayMs = quantizeDelayMs(preciseDelay);
        } else if (!preserveDelay) {
            preciseDelay = clampAddedPing(totalTarget);
            currentDelayMs = quantizeDelayMs(preciseDelay);
        }

        ClientPlayNetworkHandler handler = client == null ? null : client.getNetworkHandler();
        if (handler != null) {
            requestPingIfNeeded(handler, true);
        }
    }

    public void suspendForProtocolChange() {
        resetMeasurementState();
    }

    public void prepareForNewPlaySession() {
        resetMeasurementState();
        if (currentMode == Mode.ADD) {
            preciseDelay = addAmount;
            currentDelayMs = quantizeDelayMs(preciseDelay);
            lastDelayUpdateTimeMs = Util.getMeasuringTimeMs();
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
        long arriveTime = p.arrivalTime > 0 ? p.arrivalTime : now;

        long measuredRtt = Math.max(0, arriveTime - packet.startTime());
        lastMeasuredRtt = measuredRtt;

        long totalRecordedDelay = p.outboundDelayMs + p.inboundDelayMs;
        long totalAppliedForEstimate = totalRecordedDelay > 0 ? totalRecordedDelay : p.appliedDelayMs;

        long estimatedBase = Math.max(0, measuredRtt - totalAppliedForEstimate);
        int filteredBase = pushBaseEstimate((int) estimatedBase);

        if (filteredBase <= 0) {
            awaitingBasePing = false;
            return;
        }

        lastValidBasePing = filteredBase;

        double candidate = filteredBase;
        if (smoothedBasePing > 0) {
            double lo = smoothedBasePing - BASE_PING_MAX_STEP_MS;
            double hi = smoothedBasePing + BASE_PING_MAX_STEP_MS;
            candidate = Math.max(lo, Math.min(hi, candidate));
        }
        smoothedBasePing = smoothedBasePing == 0
                ? candidate
                : smoothedBasePing * (1.0 - BASE_PING_ALPHA) + candidate * BASE_PING_ALPHA;
        lastBasePingSampleTime = now;
        awaitingBasePing = false;
    }

    public void tick(MinecraftClient client) {
        updateDelay(client);
    }

    private boolean hasFreshBase(long now) {
        return lastValidBasePing > 0 && now - lastBasePingSampleTime <= BASE_PING_MAX_AGE_MS;
    }

    private int getCalibratedBase() {
        double candidate = smoothedBasePing > 0 ? smoothedBasePing : lastValidBasePing;
        return (int) Math.round(candidate);
    }

    private int estimateInitialBasePing(MinecraftClient client) {
        int best = getCalibratedBase();
        if (client == null || client.player == null) {
            return best;
        }
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) {
            return best;
        }
        PlayerListEntry self = handler.getPlayerListEntry(client.player.getUuid());
        if (self != null && self.getLatency() > 0) {
            best = Math.max(best, self.getLatency());
        }
        return best;
    }

    private void seedBaseEstimate(int estimateMs) {
        if (estimateMs <= 0) {
            return;
        }
        long now = Util.getMeasuringTimeMs();
        lastValidBasePing = Math.max(lastValidBasePing, estimateMs);
        if (smoothedBasePing <= 0) {
            smoothedBasePing = estimateMs;
        } else {
            smoothedBasePing = Math.max(smoothedBasePing, estimateMs);
        }
        lastBasePingSampleTime = now;
    }

    private int computeTargetPing(ClientPlayNetworkHandler handler, int basePing) {
        return switch (currentMode) {
            case TOTAL -> totalTarget > 0 ? totalTarget : basePing;
            default -> -1;
        };
    }

    private void updateDelay(MinecraftClient client) {
        if (currentMode == Mode.OFF) {
            currentDelayMs = 0;
            return;
        }

        if (currentMode == Mode.ADD) {
            preciseDelay = clampAddedPing(addAmount);
            currentDelayMs = quantizeDelayMs(preciseDelay);
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

        requestPingIfNeeded(handler, false);

        if (!hasFreshBase(now)) {
            return;
        }

        int basePing = getCalibratedBase();
        int targetPing = computeTargetPing(handler, basePing);
        if (targetPing <= 0) {
            return;
        }

        long targetDelay = clampAddedPing((int) Math.max(0, targetPing - basePing));
        adjustPreciseDelaySmoothly(targetDelay);
        setCurrentDelayQuantized(now, quantizeDelayMs(preciseDelay));
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

    private void setCurrentDelayQuantized(long nowMs, long newDelayMs) {
        long diff = Math.abs(newDelayMs - currentDelayMs);
        if (diff == 0) {
            return;
        }
        boolean largeChange = diff >= (DELAY_QUANTUM_MS * 3L);
        boolean hysteresisSatisfied = diff >= DELAY_HYSTERESIS_MS;
        boolean timeSatisfied = nowMs - lastDelayUpdateTimeMs >= DELAY_UPDATE_MIN_INTERVAL_MS;
        if (largeChange || (hysteresisSatisfied && timeSatisfied)) {
            currentDelayMs = newDelayMs;
            lastDelayUpdateTimeMs = nowMs;
        }
    }

    private static long quantizeDelayMs(double valueMs) {
        if (valueMs <= 0) {
            return 0;
        }
        long rounded = Math.round(valueMs);
        long q = DELAY_QUANTUM_MS;
        long half = q / 2L;
        long quantized = ((rounded + half) / q) * q;
        return Math.max(0, quantized);
    }

    private static int clampAddedPing(int amount) {
        return Math.min(MAX_ADDED_PING_MS, Math.max(0, amount));
    }

    public String getStatusMessage() {
        if (currentMode == Mode.OFF) {
            return "Ping Equalizer: OFF";
        }

        long now = Util.getMeasuringTimeMs();
        String modeStr = switch (currentMode) {
            case ADD -> "ADD +" + addAmount + "ms";
            case TOTAL -> "TOTAL " + totalTarget + "ms";
            default -> "OFF";
        };

        if (currentMode == Mode.ADD) {
            return String.format("Ping Equalizer: %s | Added: %dms", modeStr, currentDelayMs);
        }

        if (!hasFreshBase(now)) {
            return String.format("Ping Equalizer: %s | Measuring base ping...", modeStr);
        }

        int base = (int) Math.round(smoothedBasePing);
        int added = (int) currentDelayMs;
        int total = base + added;
        return String.format("Ping Equalizer: %s | Base: %dms | Added: %dms | Total: %dms", modeStr, base, added, total);
    }

    public String getServerSwitchStatusMessage() {
        return switch (currentMode) {
            case OFF -> "PE Status; OFF";
            case ADD -> String.format("PE Status; ADD: %d ms", currentDelayMs);
            case TOTAL -> String.format("PE Status; Total: %d ms", totalTarget);
        };
    }

    public Mode getMode() {
        return currentMode;
    }

    public int getCurrentDelayMs() {
        return (int) currentDelayMs;
    }

    public int getBasePing() {
        return lastValidBasePing;
    }

    public int getTotalPing() {
        return lastValidBasePing + (int) currentDelayMs;
    }

    public boolean isOffMode() {
        return currentMode == Mode.OFF;
    }

    public boolean isAddingDelay(int amount) {
        return currentMode == Mode.ADD && addAmount == Math.max(0, amount);
    }

    public boolean isTotalPingTarget(int amount) {
        return currentMode == Mode.TOTAL && totalTarget == Math.max(0, amount);
    }

    public long getOutboundDelayPortion() {
        return currentDelayMs / 2;
    }

    public long getInboundDelayPortion() {
        return currentDelayMs - getOutboundDelayPortion();
    }

    private void adjustPreciseDelaySmoothly(long targetDelayMs) {
        double delta = targetDelayMs - preciseDelay;
        double distance = Math.abs(delta);
        if (distance < 0.01) {
            preciseDelay = targetDelayMs;
            return;
        }
        double minStep = 0.5;
        double maxStep = 60.0;
        double ratio = Math.min(distance / 20.0, 1.0);
        double scale = 0.3 + 0.7 * ratio;
        double step = distance * scale;
        step = Math.min(distance, Math.max(minStep, Math.min(step, maxStep)));
        preciseDelay += Math.copySign(step, delta);
        if (Math.abs(targetDelayMs - preciseDelay) < 0.5) {
            preciseDelay = targetDelayMs;
        }
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
        lastMeasuredRtt = -1;
        baseEstimateCount = 0;
        baseEstimateIndex = 0;
    }

    private int pushBaseEstimate(int estimateMs) {
        if (estimateMs <= 0) {
            return -1;
        }
        baseEstimateWindow[baseEstimateIndex] = estimateMs;
        baseEstimateIndex = (baseEstimateIndex + 1) % BASE_FILTER_WINDOW;
        if (baseEstimateCount < BASE_FILTER_WINDOW) {
            baseEstimateCount++;
        }
        int[] copy = Arrays.copyOf(baseEstimateWindow, baseEstimateCount);
        Arrays.sort(copy);
        return copy[baseEstimateCount / 2];
    }
}
