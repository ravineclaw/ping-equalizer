package net.ravenclaw.ravenclawspingequalizer.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onPingResult", at = @At("HEAD"), cancellable = true)
    private void rpe$onPingResult(PingResultS2CPacket packet, CallbackInfo ci) {
        if (PingEqualizerState.getInstance().getMode() == PingEqualizerState.Mode.OFF) {
            return;
        }
        PingEqualizerState.getInstance().handlePingResult(packet);
    }

    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void rpe$onGameJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
        PingEqualizerState.getInstance().setOff();
    }
}
