package net.ravenclaw.ravenclawspingequalizer.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.message.ChatVisibility;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.text.Text;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;
import net.ravenclaw.ravenclawspingequalizer.cryptography.CryptoHandler;

public class RavenclawsPingEqualizerClient implements ClientModInitializer {

    private static final long SPOOF_SEND_DELAY_MS = 75L;
    private static final long SPOOF_RESTORE_DELAY_MS = 400L;

    private String lastMessage = "";
    private final Deque<String> pendingAnnouncements = new ArrayDeque<>();
    private boolean spoofInProgress = false;
    private int tickCounter = 0;

    public static CryptoHandler cryptoHandler;

    @Override
    public void onInitializeClient() {
        cryptoHandler = new CryptoHandler();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PingEqualizerState.getInstance().tick(client);
            cryptoHandler.tick();
            
            tickCounter++;
            if (tickCounter >= 1200) {
                tickCounter = 0;
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            resetAnnouncementState();
            if (handler.getServerInfo() != null) {
                cryptoHandler.setCurrentServer(handler.getServerInfo().address);
            } else {
                cryptoHandler.setCurrentServer("");
            }
        });
        
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            resetAnnouncementState();
            cryptoHandler.setCurrentServer("");
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            SuggestionProvider<FabricClientCommandSource> onlinePlayerSuggestions = (context, builder) -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.getNetworkHandler() != null) {
                    for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
                        String name = entry.getProfile().getName();
                        if (name.toLowerCase().startsWith(builder.getRemainingLowerCase())) {
                            builder.suggest(name);
                        }
                    }
                }
                return builder.buildFuture();
            };

            LiteralCommandNode<FabricClientCommandSource> rootNode = dispatcher.register(
                ClientCommandManager.literal("pingequalizer")
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
                                cryptoHandler.triggerHeartbeatForCommand();
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
                                notifyStateChange("Ping Equalizer: Total ping set to " + amount + "ms.");
                                }
                                cryptoHandler.triggerHeartbeatForCommand();
                                return 1;
                            })
                        )
                    )
                    .then(ClientCommandManager.literal("stable")
                        .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                            .executes(ctx -> {
                                int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                if (amount == 0) {
                                    PingEqualizerState.getInstance().setOff();
                                    notifyStateChange("Ping Equalizer: Disabled.");
                                } else {
                                    PingEqualizerState.getInstance().setTotalPing(amount);
                                    notifyStateChange("Ping Equalizer: Stabilizing ping to " + amount + "ms.");
                                }
                                cryptoHandler.triggerImmediateHeartbeatWithCooldown();
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
                            notifyStateChange("Ping Equalizer: Disabled.");
                            cryptoHandler.triggerHeartbeatForCommand();
                            return 1;
                        })
                    )
                    .then(ClientCommandManager.literal("validate")
                        .then(ClientCommandManager.argument("player", StringArgumentType.string())
                            .suggests(onlinePlayerSuggestions)
                            .executes(ctx -> {
                                String input = StringArgumentType.getString(ctx, "player");
                                UUID playerUuid = parseUuid(input);

                                if (playerUuid != null) {
                                    sendLocalMessage("Validating player with UUID: " + playerUuid);
                                    cryptoHandler.validatePlayer(playerUuid)
                                        .thenAccept(result -> {
                                            MinecraftClient.getInstance().execute(() -> {
                                                sendLocalMessage(formatValidationResult(result, input));
                                            });
                                        })
                                        .exceptionally(ex -> {
                                            MinecraftClient.getInstance().execute(() -> {
                                                sendLocalMessage("Error validating player: " + ex.getMessage());
                                            });
                                            return null;
                                        });
                                } else {
                                    sendLocalMessage("Validating player: " + input);
                                    cryptoHandler.validatePlayer(input)
                                        .thenAccept(result -> {
                                            MinecraftClient.getInstance().execute(() -> {
                                                sendLocalMessage(formatValidationResult(result, input));
                                            });
                                        })
                                        .exceptionally(ex -> {
                                            MinecraftClient.getInstance().execute(() -> {
                                                sendLocalMessage("Error validating player: " + ex.getMessage());
                                            });
                                            return null;
                                        });
                                }
                                return 1;
                            })
                        )
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

    private UUID parseUuid(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }

        // Try standard UUID format with dashes
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
            // Not a standard UUID format
        }

        // Try UUID without dashes (32 hex characters)
        if (input.length() == 32 && input.matches("[0-9a-fA-F]+")) {
            try {
                String withDashes = input.substring(0, 8) + "-" +
                                   input.substring(8, 12) + "-" +
                                   input.substring(12, 16) + "-" +
                                   input.substring(16, 20) + "-" +
                                   input.substring(20, 32);
                return UUID.fromString(withDashes);
            } catch (IllegalArgumentException ignored) {
                // Still not valid
            }
        }

        // Not a UUID, treat as username
        return null;
    }

    private String formatValidationResult(net.ravenclaw.ravenclawspingequalizer.cryptography.ApiService.PlayerValidationResult result, String input) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Check if it's self from the result username, OR from the input if result is empty
        boolean isSelf = false;
        if (client != null && client.getSession() != null) {
            String selfUsername = client.getSession().getUsername();
            UUID selfUuid = client.getSession().getUuidOrNull();
            // Check from result
            if (!result.username().isEmpty() && result.username().equalsIgnoreCase(selfUsername)) {
                isSelf = true;
            }
            // Check from input if result is empty (server unreachable)
            if (result.username().isEmpty() && input != null) {
                if (input.equalsIgnoreCase(selfUsername)) {
                    isSelf = true;
                } else if (selfUuid != null) {
                    UUID inputUuid = parseUuid(input);
                    if (inputUuid != null && inputUuid.equals(selfUuid)) {
                        isSelf = true;
                    }
                }
            }
        }
        return formatValidationResult(result, isSelf);
    }

    private String formatValidationResult(net.ravenclaw.ravenclawspingequalizer.cryptography.ApiService.PlayerValidationResult result, boolean isSelf) {
        // Handle server unreachable case
        if (result.isServerUnreachable()) {
            if (isSelf) {
                // Fall back to client-side values when validating yourself
                return formatClientSideFallback();
            }
            return "§cUnable to reach validation server. Please try again later.";
        }

        if (!result.isConnected()) {
            return "§cPlayer not found or has never connected.";
        }

        // Check if heartbeat is stale (over 40 seconds ago)
        long now = System.currentTimeMillis();
        long heartbeatAge = now - result.lastHeartbeat();
        boolean isStale = heartbeatAge > 40000;

        if (isStale && !isSelf) {
            return "§cPlayer is not currently connected. Last seen: " + formatHeartbeatAge(heartbeatAge);
        }

        StringBuilder sb = new StringBuilder();
        boolean modeActive = !result.peMode().equalsIgnoreCase("off") && !result.peMode().equalsIgnoreCase("unknown");

        // Line 1: Player info with verification status
        sb.append("§6Player: §f").append(result.username());
        // Only show server if mode is active (mod is actually running)
        if (modeActive && !result.currentServer().isEmpty()) {
            sb.append(" §7on §f").append(result.currentServer());
        }
        // Add verification details on the same line
        sb.append(" §7[Hash=").append(result.isHashCorrect() ? "§aOK" : "§cBAD");
        sb.append("§7, Signed=").append(result.modStatus().equalsIgnoreCase("signed") ? "§aYES" : "§cNO");
        sb.append("§7, SignatureValid=").append(result.isSignatureCorrect() ? "§aYES" : "§cNO");
        sb.append("§7]");
        sb.append("\n");

        // Line 2: Status (mode, delay, ping info)
        String modeDisplay = formatMode(result.peMode());
        sb.append("§6Status: §f").append(modeDisplay);
        if (modeActive) {
            sb.append(" §7| Delay: §f").append(result.peDelay()).append("ms");
            sb.append(" §7| Base: §f").append(result.peBasePing()).append("ms");
            sb.append(" §7| Total: §f").append(result.peTotalPing()).append("ms");
        }
        sb.append("\n");

        // Line 3: Mod state description
        sb.append("§6Mod State: ");
        if (isStale) {
            sb.append("§cHeartbeat stale. Last seen: ").append(formatHeartbeatAge(heartbeatAge));
        } else {
            sb.append(getModStateDescription(result.isHashCorrect(), result.isSignatureCorrect(), result.modStatus()));
        }
        sb.append("\n");

        // Line 4: Last heartbeat
        sb.append("§7LastHeartbeat: ").append(formatHeartbeatAge(heartbeatAge));

        // If checking yourself, show all available information
        if (isSelf) {
            sb.append("\n§7UUID: §f").append(result.uuid());
            if (!result.currentServer().isEmpty()) {
                sb.append("\n§7Server: §f").append(result.currentServer());
            }
        }

        return sb.toString();
    }

    private String formatMode(String mode) {
        return switch (mode.toLowerCase()) {
            case "off" -> "OFF";
            case "add" -> "ADD";
            case "total" -> "TOTAL";
            case "match" -> "MATCH";
            default -> mode.toUpperCase();
        };
    }

    private String formatHeartbeatAge(long ageMs) {
        long seconds = ageMs / 1000;
        if (seconds < 60) {
            return (seconds <= 40 ? "§a" : "§c") + seconds + "s ago";
        }
        long minutes = seconds / 60;
        return "§c" + minutes + "m ago";
    }

    private String getModStateDescription(boolean hashCorrect, boolean signatureCorrect, String modStatus) {
        if (hashCorrect && signatureCorrect) {
            return "§aFully validated.";
        } else if (hashCorrect && modStatus.equalsIgnoreCase("unsigned")) {
            return "§cSignature missing. The mod is not cryptographically verified.";
        } else if (hashCorrect && !signatureCorrect) {
            return "§cSignature verification failed. The status cannot be trusted.";
        } else if (!hashCorrect && signatureCorrect) {
            return "§cMod hash mismatch with valid signature. Private key is compromised.";
        } else {
            return "§cModified mod with no valid signature. Status cannot be trusted.";
        }
    }

    private String formatClientSideFallback() {
        MinecraftClient client = MinecraftClient.getInstance();
        PingEqualizerState peState = PingEqualizerState.getInstance();

        StringBuilder sb = new StringBuilder();
        sb.append("§e⚠ Server unreachable - showing local values only\n");

        // Line 1: Player info with verification details
        String username = client.getSession() != null ? client.getSession().getUsername() : "Unknown";
        sb.append("§6Player: §f").append(username);
        String currentServer = cryptoHandler != null ? cryptoHandler.getCurrentServer() : "";
        if (!currentServer.isEmpty()) {
            sb.append(" §7on §f").append(currentServer);
        }
        // Add verification details on the same line
        boolean isSigned = cryptoHandler != null && cryptoHandler.canSign();
        sb.append(" §7[Hash=§f").append(cryptoHandler != null ? "local" : "N/A");
        sb.append("§7, Signed=").append(isSigned ? "§aYES" : "§cNO");
        sb.append("§7, SignatureValid=§eN/A§7]");
        sb.append("\n");

        // Line 2: Status (mode, delay, ping info)
        String modeDisplay = formatMode(peState.getMode().name());
        boolean modeActive = peState.getMode() != PingEqualizerState.Mode.OFF;
        sb.append("§6Status: §f").append(modeDisplay);
        if (modeActive) {
            sb.append(" §7| Delay: §f").append(peState.getCurrentDelayMs()).append("ms");
            sb.append(" §7| Base: §f").append(peState.getBasePing()).append("ms");
            sb.append(" §7| Total: §f").append(peState.getTotalPing()).append("ms");
        }
        sb.append("\n");

        // Line 3: Mod state (local info only)
        sb.append("§6Mod State: ");
        if (isSigned) {
            sb.append("§aLocally validated (signed)");
        } else {
            sb.append("§eLocally running (unsigned)");
        }
        sb.append("\n");

        // Line 4: Last heartbeat info
        sb.append("§7LastHeartbeat: §eUnable to verify");

        // UUID info
        if (client.getSession() != null && client.getSession().getUuidOrNull() != null) {
            sb.append("\n§7UUID: §f").append(client.getSession().getUuidOrNull());
        }

        return sb.toString();
    }
}
