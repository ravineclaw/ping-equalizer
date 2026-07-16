package net.ravenclaw.ravenclawspingequalizer.bridge;

public interface PingEqualizerConnectionBridge {
    void pingEqualizer$signalPlayPhaseEntry();
    void pingEqualizer$setHandlerEnabled(boolean enabled);
}

