package net.ravenclaw.ravenclawspingequalizer.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;
import net.ravenclaw.ravenclawspingequalizer.bridge.PingEqualizerConnectionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Shadow
    public abstract ClientConnection getConnection();

    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void pingEqualizer$onGameJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
        PingEqualizerState.getInstance().prepareForNewPlaySession();
        ClientConnection connection = this.getConnection();
        if (connection instanceof PingEqualizerConnectionBridge bridge) {
            bridge.pingEqualizer$signalPlayPhaseEntry();
        }
    }
}
