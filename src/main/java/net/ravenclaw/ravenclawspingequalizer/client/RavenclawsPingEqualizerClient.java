package net.ravenclaw.ravenclawspingequalizer.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;
import net.ravenclaw.ravenclawspingequalizer.net.PingEqualizerControlPayload;
import net.ravenclaw.ravenclawspingequalizer.net.PingEqualizerStateQueryPayload;
import net.ravenclaw.ravenclawspingequalizer.net.PingEqualizerStateResponsePayload;

public class RavenclawsPingEqualizerClient implements ClientModInitializer {

    private String lastMessage = "";

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playS2C().register(PingEqualizerControlPayload.ID, PingEqualizerControlPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PingEqualizerStateQueryPayload.ID, PingEqualizerStateQueryPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PingEqualizerStateResponsePayload.ID, PingEqualizerStateResponsePayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(PingEqualizerControlPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                PingEqualizerState state = PingEqualizerState.getInstance();
                boolean wasEnabled = state.isServerEnabled();
                state.setServerEnabled(payload.enabled());
                if (wasEnabled != payload.enabled()) {
                    sendLocalMessage(payload.enabled()
                            ? "Ping Equalizer enabled by server."
                            : "\u00A7cPing Equalizer disabled by server.");
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(PingEqualizerStateQueryPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                PingEqualizerState state = PingEqualizerState.getInstance();
                ClientPlayNetworking.send(new PingEqualizerStateResponsePayload(
                        payload.requestId(),
                        state.isServerEnabled(),
                        state.getMode().name().toLowerCase(),
                        state.getCurrentDelayMs(),
                        state.getBasePing(),
                        state.getTotalPing()
                ));
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> PingEqualizerState.getInstance().tick(client));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            lastMessage = "";
            PingEqualizerState.getInstance().resetServerControl();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            lastMessage = "";
            PingEqualizerState.getInstance().resetServerControl();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralCommandNode<FabricClientCommandSource> rootNode = dispatcher.register(
                    ClientCommandManager.literal("pingequalizer")
                            .then(ClientCommandManager.literal("add")
                                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                                            .executes(ctx -> {
                                                if (!ensureServerEnabled()) {
                                                    return 0;
                                                }
                                                if (isChatCommandsOnly()) {
                                                    sendLocalMessage("\u00A7cChat is set to commands-only; mod changes must be announced publicly; command blocked.");
                                                    return 0;
                                                }
                                                int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                                PingEqualizerState state = PingEqualizerState.getInstance();
                                                if (amount == 0) {
                                                    if (state.isOffMode()) {
                                                        logNoChange("Ping Equalizer already disabled.");
                                                    } else {
                                                        state.setOff();
                                                        notifyStateChange("Disabled.");
                                                    }
                                                } else if (state.isAddingDelay(amount)) {
                                                    logNoChange("Ping Equalizer already adding " + amount + "ms delay.");
                                                } else {
                                                    state.setAddPing(amount);
                                                    notifyStateChange("Added " + amount + "ms delay.");
                                                }
                                                return 1;
                                            })
                                    )
                            )
                            .then(ClientCommandManager.literal("total")
                                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                                            .executes(ctx -> {
                                                if (!ensureServerEnabled()) {
                                                    return 0;
                                                }
                                                if (isChatCommandsOnly()) {
                                                    sendLocalMessage("\u00A7cChat is set to commands-only; mod changes must be announced publicly; command blocked.");
                                                    return 0;
                                                }
                                                int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                                PingEqualizerState state = PingEqualizerState.getInstance();
                                                if (amount == 0) {
                                                    if (state.isOffMode()) {
                                                        logNoChange("Ping Equalizer already disabled.");
                                                    } else {
                                                        state.setOff();
                                                        notifyStateChange("Disabled.");
                                                    }
                                                } else if (state.isTotalPingTarget(amount)) {
                                                    logNoChange("Ping Equalizer already targeting " + amount + "ms total ping.");
                                                } else {
                                                    state.setTotalPing(amount);
                                                    notifyStateChange("Total ping set to " + amount + "ms.");
                                                }
                                                return 1;
                                            })
                                    )
                            )
                            .then(ClientCommandManager.literal("status")
                                    .executes(ctx -> {
                                        sendLocalMessage(PingEqualizerState.getInstance().getStatusMessage());
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.literal("off")
                                    .executes(ctx -> {
                                        if (!ensureServerEnabled()) {
                                            return 0;
                                        }
                                        if (isChatCommandsOnly()) {
                                            sendLocalMessage("\u00A7cChat is set to commands-only; mod changes must be announced publicly; command blocked.");
                                            return 0;
                                        }
                                        PingEqualizerState state = PingEqualizerState.getInstance();
                                        if (state.isOffMode()) {
                                            logNoChange("Ping Equalizer already disabled.");
                                        } else {
                                            state.setOff();
                                            notifyStateChange("Disabled.");
                                        }
                                        return 1;
                                    })
                            )
            );

            dispatcher.register(ClientCommandManager.literal("pe").redirect(rootNode));
        });
    }

    private boolean ensureServerEnabled() {
        if (PingEqualizerState.getInstance().isServerEnabled()) {
            return true;
        }
        sendLocalMessage("\u00A7cPing Equalizer is disabled by this server.");
        return false;
    }

    private void notifyStateChange(String message) {
        if (message.equals(lastMessage)) {
            return;
        }
        lastMessage = message;
        sendPublicModeAnnouncement(message);
    }

    private static void sendLocalMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }

    private void sendPublicModeAnnouncement(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null || message == null || message.isEmpty()) {
            return;
        }
        String sanitized = stripFormattingCodes(message.replace("\n", " ").trim());
        if (sanitized.isEmpty()) {
            return;
        }
        client.getNetworkHandler().sendChatMessage("[Ping Equalizer] " + sanitized);
    }

    private String stripFormattingCodes(String value) {
        return value.replaceAll("\u00A7.", "");
    }

    private void logNoChange(String message) {
        sendLocalMessage(message);
    }

    private boolean isChatCommandsOnly() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) {
            return false;
        }
        try {
            java.lang.reflect.Field f = mc.options.getClass().getField("chatVisibility");
            Object v = f.get(mc.options);
            return v != null && "SYSTEM".equalsIgnoreCase(v.toString());
        } catch (Exception e) {
            return false;
        }
    }
}
