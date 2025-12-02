package net.ravenclaw.ravenclawspingequalizer.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.message.ChatVisibility;
import net.minecraft.text.Text;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;

public class RavenclawsPingEqualizerClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("RavenclawsPingEqualizer");
    private String lastMessage = "";

    @Override
    public void onInitializeClient() {
        // Tick Event: Updates the ping equalizer state
        ClientTickEvents.END_CLIENT_TICK.register(client -> 
            PingEqualizerState.getInstance().tick(client)
        );

        // Join Event: Reset the spam filter so the state message is shown again in the new world
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> 
            lastMessage = ""
        );

        // Disconnect Event: Reset the spam filter
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> 
            lastMessage = ""
        );

        // Command Registration
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var rootBuilder = ClientCommandManager.literal("pingequalizer")
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            if (amount == 0) {
                                PingEqualizerState.getInstance().setOff();
                                notifyStateChange("Ping Equalizer: Disabled.");
                            } else {
                                PingEqualizerState.getInstance().setAddPing(amount);
                                notifyStateChange("Ping Equalizer: Added " + amount + "ms delay.");
                            }
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("total")
                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                        .executes(ctx -> {
                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                            if (amount == 0) {
                                PingEqualizerState.getInstance().setOff();
                                notifyStateChange("Ping Equalizer: Disabled.");
                            } else {
                                PingEqualizerState.getInstance().setTotalPing(amount);
                                notifyStateChange("Ping Equalizer: Setting total ping to " + amount + "ms.");
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
                        PingEqualizerState.getInstance().setOff();
                        notifyStateChange("Ping Equalizer: Disabled.");
                        return 1;
                    })
                );

            // Register main command
            LiteralCommandNode<FabricClientCommandSource> rootNode = dispatcher.register(rootBuilder);

            // Register alias '/pe' redirecting to '/pingequalizer'
            dispatcher.register(ClientCommandManager.literal("pe").redirect(rootNode));
        });
    }

    /**
     * Logs the state change and broadcasts it to the server chat.
     * Handles chat visibility settings to ensure the user always sees the confirmation.
     */
    private void notifyStateChange(String message) {
        if (message.equals(lastMessage)) return;
        lastMessage = message;

        // Always log state changes to the file/console
        LOGGER.info("[RPE] {}", message);
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            // Send to server (public chat)
            client.player.networkHandler.sendChatMessage(message);
            
            // If chat is hidden or commands-only, force a local display so the user knows it worked
            ChatVisibility visibility = client.options.getChatVisibility().getValue();
            if (visibility != ChatVisibility.FULL) {
                client.player.sendMessage(Text.literal(message), false);
            }
        }
    }

    private void sendLocalMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }
}
