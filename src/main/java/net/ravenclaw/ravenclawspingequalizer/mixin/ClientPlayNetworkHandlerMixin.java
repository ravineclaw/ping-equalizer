package net.ravenclaw.ravenclawspingequalizer.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.ravenclaw.ravenclawspingequalizer.bridge.PingEqualizerConnectionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Shadow
    public abstract Connection getConnection();

    @Inject(method = "handleLogin", at = @At("TAIL"), require = 0)
    private void pingEqualizer$onGameJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
        Connection connection = this.getConnection();
        if (connection instanceof PingEqualizerConnectionBridge bridge) {
            bridge.pingEqualizer$signalPlayPhaseEntry();
        }
    }
}
