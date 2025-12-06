package net.ravenclaw.ravenclawspingequalizer.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.text.Text;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;

public class RavenclawsPingEqualizerClient implements ClientModInitializer {

    private static final long SPOOF_SEND_DELAY_MS = 75L;
    private static final long SPOOF_RESTORE_DELAY_MS = 400L;

    private String lastMessage = "";
    private final Deque<String> pendingAnnouncements = new ArrayDeque<>();
    private boolean spoofInProgress = false;
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PingEqualizerState.getInstance().tick(client);
            
            tickCounter++;
            if (tickCounter >= 1200) {
                tickCounter = 0;
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            resetAnnouncementState();
        });
        
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            resetAnnouncementState();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralCommandNode<FabricClientCommandSource> rootNode = dispatcher.register(
                ClientCommandManager.literal("pingequalizer")
                    .then(ClientCommandManager.literal("add")
                        .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                            .executes(ctx -> {
                                int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                if (amount == 0) {
                                    PingEqualizerState.getInstance().setOff();
                                    notifyStateChange("PE: Off");
                                } else {
                                    PingEqualizerState.getInstance().setAddPing(amount);
                                    notifyStateChange("PE: +" + amount);
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
                                    notifyStateChange("PE: Off");
                                } else {
                                    PingEqualizerState.getInstance().setTotalPing(amount);
                                    notifyStateChange("PE: =" + amount);
                                }
                                return 1;
                            })
                        )
                    )
                    .then(ClientCommandManager.literal("status")
                        .executes(ctx -> {
                            String status = PingEqualizerState.getInstance().getStatusMessage();
                            sendLocalMessage(status);
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("off")
                        .executes(ctx -> {
                            PingEqualizerState.getInstance().setOff();
                            notifyStateChange("PE: Off");
                            return 1;
                        })
                    )
            );

            dispatcher.register(ClientCommandManager.literal("pe").redirect(rootNode));
        });
    }

    private void resetAnnouncementState() {
        lastMessage = "";
        pendingAnnouncements.clear();
        spoofInProgress = false;
    }

    private void notifyStateChange(String message) {
        if (message.equals(lastMessage)) {
            return;
        }
        lastMessage = message;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        pendingAnnouncements.addLast(message);
        processPendingAnnouncements(client);
    }

    private void processPendingAnnouncements(MinecraftClient client) {
        if (spoofInProgress || client.player == null) {
            return;
        }

        String next = pendingAnnouncements.pollFirst();
        if (next == null) {
            return;
        }

        ChatVisibility currentVisibility = client.options.getChatVisibility().getValue();
        if (currentVisibility == ChatVisibility.FULL) {
            client.player.networkHandler.sendChatMessage(next);
            processPendingAnnouncements(client);
            return;
        }

        spoofInProgress = true;
        SyncedClientOptions originalOptions = client.options.getSyncedOptions();
        SyncedClientOptions forcedOptions = copyWithVisibility(originalOptions, ChatVisibility.FULL);

        client.player.networkHandler.sendPacket(new ClientOptionsC2SPacket(forcedOptions));

        CompletableFuture.delayedExecutor(SPOOF_SEND_DELAY_MS, TimeUnit.MILLISECONDS).execute(() ->
            client.execute(() -> {
                if (client.player == null) {
                    spoofInProgress = false;
                    return;
                }

                client.player.networkHandler.sendChatMessage(next);

                CompletableFuture.delayedExecutor(SPOOF_RESTORE_DELAY_MS, TimeUnit.MILLISECONDS).execute(() ->
                    client.execute(() -> {
                        if (client.player != null) {
                            client.player.networkHandler.sendPacket(new ClientOptionsC2SPacket(originalOptions));
                        }
                        spoofInProgress = false;
                        processPendingAnnouncements(client);
                    })
                );
            })
        );
    }

    private static SyncedClientOptions copyWithVisibility(SyncedClientOptions base, ChatVisibility visibility) {
        return new SyncedClientOptions(
            base.language(),
            base.viewDistance(),
            visibility,
            base.chatColorsEnabled(),
            base.playerModelParts(),
            base.mainArm(),
            base.filtersText(),
            base.allowsServerListing()
        );
    }

    private void sendLocalMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), false);
        }
    }
}
