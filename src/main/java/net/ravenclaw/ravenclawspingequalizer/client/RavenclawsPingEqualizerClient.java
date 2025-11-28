package net.ravenclaw.ravenclawspingequalizer.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RavenclawsPingEqualizerClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("RavenclawsPingEqualizer");

    @Override
    public void onInitializeClient() {
        // Register Tick Event
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PingEqualizerState.getInstance().tick(client);
        });

        // Register Disconnect Event
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            PingEqualizerState.getInstance().setOff();
        });

        // Register Commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Build command tree (pingequalizer + alias pe)
            var root = ClientCommandManager.literal("pingequalizer")
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            PingEqualizerState.getInstance().setAddPing(amount);
                            LOGGER.info("[RPE] Command: /pingequalizer add {}", amount);
                            sendPublicMessage("Ping Equalizer: Added " + amount + "ms delay.");
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("stable")
                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            PingEqualizerState.getInstance().setStablePing(amount);
                            sendPublicMessage("Ping Equalizer: Stabilizing ping to " + amount + "ms.");
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("status")
                    .executes(ctx -> {
                        sendClientMessage(PingEqualizerState.getInstance().getStatusMessage());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("off")
                    .executes(ctx -> {
                        PingEqualizerState.getInstance().setOff();
                        LOGGER.info("[RPE] Command: /pingequalizer off");
                        sendPublicMessage("Ping Equalizer: Disabled.");
                        return 1;
                    })
                );

            // Register main command
            dispatcher.register(root);
            // Register alias /pe with identical structure
            var alias = ClientCommandManager.literal("pe")
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            PingEqualizerState.getInstance().setAddPing(amount);
                            LOGGER.info("[RPE] Command: /pe add {}", amount);
                            sendPublicMessage("Ping Equalizer: Added " + amount + "ms delay.");
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("stable")
                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            PingEqualizerState.getInstance().setStablePing(amount);
                            LOGGER.info("[RPE] Command: /pe stable {}", amount);
                            sendPublicMessage("Ping Equalizer: Stabilizing ping to " + amount + "ms.");
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("status")
                    .executes(ctx -> {
                        LOGGER.info("[RPE] Command: /pe status");
                        sendClientMessage(PingEqualizerState.getInstance().getStatusMessage());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("off")
                    .executes(ctx -> {
                        PingEqualizerState.getInstance().setOff();
                        LOGGER.info("[RPE] Command: /pe off");
                        sendPublicMessage("Ping Equalizer: Disabled.");
                        return 1;
                    })
                );
            dispatcher.register(alias);
        });
    }

    private String lastMessage = "";

    private void sendPublicMessage(String message) {
        if (message.equals(lastMessage)) return;
        lastMessage = message;
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatMessage(message);
        }
    }

    private void sendClientMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }
}
