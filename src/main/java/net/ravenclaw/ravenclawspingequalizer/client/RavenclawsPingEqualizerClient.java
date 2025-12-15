package net.ravenclaw.ravenclawspingequalizer.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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

    private static final Logger LOGGER = LoggerFactory.getLogger("RavenclawsPingEqualizer");
    private static final long SPOOF_SEND_DELAY_MS = 75L;
    private static final long SPOOF_RESTORE_DELAY_MS = 400L;

    private String lastMessage = "";
    private final Deque<String> pendingAnnouncements = new ArrayDeque<>();
    private boolean spoofInProgress = false;
    private final net.ravenclaw.ravenclawspingequalizer.cryptography.CryptoHandler cryptoHandler = new net.ravenclaw.ravenclawspingequalizer.cryptography.CryptoHandler();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> PingEqualizerState.getInstance().tick(client));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetAnnouncementState());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetAnnouncementState());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            LiteralCommandNode<FabricClientCommandSource> rootNode = dispatcher.register(
                ClientCommandManager.literal("pingequalizer")
                    .then(ClientCommandManager.literal("add")
                        .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                            .executes(ctx -> {
                                int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                if (amount == 0) {
                                    LOGGER.info("[RPE] Command executed: /pingequalizer add 0 (disabling)");
                                    PingEqualizerState.getInstance().setOff();
                                    notifyStateChange("Ping Equalizer: Disabled.");
                                } else {
                                    LOGGER.info("[RPE] Command executed: /pingequalizer add {} (adding {} ms delay)", amount, amount);
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
                                    LOGGER.info("[RPE] Command executed: /pingequalizer total 0 (disabling)");
                                    PingEqualizerState.getInstance().setOff();
                                    notifyStateChange("Ping Equalizer: Disabled.");
                                } else {
                                    LOGGER.info("[RPE] Command executed: /pingequalizer total {} (setting total ping to {} ms)", amount, amount);
                                    PingEqualizerState.getInstance().setTotalPing(amount);
                                    notifyStateChange("Ping Equalizer: Setting total ping to " + amount + "ms.");
                                }
                                return 1;
                            })
                        )
                    )
                    .then(ClientCommandManager.literal("status")
                        .executes(ctx -> {
                            String status = PingEqualizerState.getInstance().getStatusMessage();
                            LOGGER.info("[RPE] Command executed: /pingequalizer status -> {}", status);
                            sendLocalMessage(status);
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("validate")
                        .then(ClientCommandManager.argument("player", StringArgumentType.word())
                            .executes(ctx -> {
                                String player = StringArgumentType.getString(ctx, "player");
                                cryptoHandler.validatePlayer(player).thenAccept(result -> {
                                    MinecraftClient mc = MinecraftClient.getInstance();
                                    if (mc != null) {
                                        var lines = formatValidationLines(result);
                                        mc.execute(() -> {
                                            for (String l : lines) sendLocalMessage(l);
                                        });
                                    }
                                });
                                return 1;
                            })
                        )
                        .executes(ctx -> {
                            MinecraftClient mc = MinecraftClient.getInstance();
                            if (mc == null || mc.getSession() == null) {
                                sendLocalMessage("No player session available for validation.");
                                return 1;
                            }
                            String username = mc.getSession().getUsername();
                            cryptoHandler.validatePlayer(username).thenAccept(result -> {
                                MinecraftClient mc2 = MinecraftClient.getInstance();
                                if (mc2 != null) {
                                    var lines = formatValidationLines(result);
                                    mc2.execute(() -> {
                                        for (String l : lines) sendLocalMessage(l);
                                    });
                                }
                            });
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("off")
                        .executes(ctx -> {
                            LOGGER.info("[RPE] Command executed: /pingequalizer off (disabling)");
                            PingEqualizerState.getInstance().setOff();
                            notifyStateChange("Ping Equalizer: Disabled.");
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
        LOGGER.info("[RPE] {}", message);

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

    private java.util.List<String> formatValidationLines(net.ravenclaw.ravenclawspingequalizer.cryptography.ApiService.PlayerValidationResult r) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (r == null) {
            lines.add("Validation: no result");
            return lines;
        }

        lines.add("Validating player: " + (r.username() == null || r.username().isEmpty() ? "<unknown>" : r.username()));
        lines.add("Player: " + (r.username() == null || r.username().isEmpty() ? "<unknown>" : r.username()));
        lines.add("Mod Version: " + (r.modVersion() == null || r.modVersion().isEmpty() ? "unknown" : r.modVersion()));

        String hashState = r.isHashCorrect() ? "Hash=GOOD" : "Hash=BAD";
        String signedState = r.isSigned() ? "Signed=YES" : "Signed=NO";
        String sigValid = r.isSignatureCorrect() ? "SignatureValid=YES" : "SignatureValid=NO";
        lines.add("[" + hashState + " " + signedState + " " + sigValid + "]");

        // Status line: mode, delay, base, total
        String statusLine = String.format("Status: %s ; Delay: %dms ; Base: %dms ; Total: %dms",
            r.peMode() == null ? "unknown" : r.peMode().toUpperCase(),
            r.peDelay(),
            r.peBasePing(),
            r.peTotalPing()
        );
        lines.add(statusLine);

        // Mod state description
        if (!r.isSignatureCorrect()) {
            lines.add("Mod State: Modified mod with no valid signature. Status cannot be trusted.");
        } else if (!r.isHashCorrect()) {
            lines.add("Mod State: Hash mismatch detected; treat with caution.");
        } else {
            lines.add("Mod State: Verified mod.");
        }

        // Last heartbeat: show seconds ago (if present)
        if (r.lastHeartbeat() <= 0) {
            lines.add("LastHeartbeat: 0s ago");
        } else {
            long secs = Math.max(0L, (System.currentTimeMillis() - r.lastHeartbeat()) / 1000L);
            lines.add("LastHeartbeat: " + secs + "s ago");
        }

        return lines;
    }
}
