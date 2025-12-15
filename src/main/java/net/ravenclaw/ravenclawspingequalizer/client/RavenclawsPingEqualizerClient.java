package net.ravenclaw.ravenclawspingequalizer.client;

import java.util.UUID;

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
import net.minecraft.text.Text;
import net.ravenclaw.ravenclawspingequalizer.PingEqualizerState;
import net.ravenclaw.ravenclawspingequalizer.cryptography.ApiService;
import net.ravenclaw.ravenclawspingequalizer.cryptography.CryptoHandler;

public class RavenclawsPingEqualizerClient implements ClientModInitializer {

    private static final String COMMAND_RATE_LIMIT_NOTICE = "Ping Equalizer commands are temporarily limited to avoid hitting the API. Please wait a second before trying again.";

    private String lastMessage = "";

    public static CryptoHandler cryptoHandler;

    @Override
    public void onInitializeClient() {
        ApiService.refreshApiBaseUrlFromGistAsync();
        cryptoHandler = new CryptoHandler();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PingEqualizerState.getInstance().tick(client);
            cryptoHandler.tick();
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            lastMessage = "";
            if (handler.getServerInfo() != null) {
                cryptoHandler.setCurrentServer(handler.getServerInfo().address);
            } else {
                cryptoHandler.setCurrentServer("");
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            lastMessage = "";
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
                                if (!ensureCommandAllowed()) {
                                    return 0;
                                }
                                // If chat is set to commands-only and we can't reach the server
                                // to send a public announcement, block the command.
                                MinecraftClient mc = MinecraftClient.getInstance();
                                boolean chatCommandsOnly = isChatCommandsOnly();
                                if (chatCommandsOnly) {
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
                        } else {
                            if (state.isAddingDelay(amount)) {
                                logNoChange("Ping Equalizer already adding " + amount + "ms delay.");
                            } else {
                                state.setAddPing(amount);
                                        notifyStateChange("Added " + amount + "ms delay.");
                            }
                        }
                        cryptoHandler.triggerHeartbeatForCommand();
                        return 1;
                    })
            )
                            )
                            .then(ClientCommandManager.literal("total")
                                    .then(ClientCommandManager.argument("amount", IntegerArgumentType.integer(0))
                                            .executes(ctx -> {
                                if (!ensureCommandAllowed()) {
                                    return 0;
                                }
                                boolean chatCommandsOnly = isChatCommandsOnly();
                                if (chatCommandsOnly) {
                                    sendLocalMessage("\u00A7cChat is set to commands-only; mod changes must be announced publicly; command blocked.");
                                    return 0;
                                }
                        int amount = IntegerArgumentType.getInteger(ctx, "amount");
                        PingEqualizerState totalState = PingEqualizerState.getInstance();
                                if (amount == 0) {
                            if (totalState.isOffMode()) {
                                logNoChange("Ping Equalizer already disabled.");
                            } else {
                                totalState.setOff();
                                notifyStateChange("Disabled.");
                            }
                        } else {
                            if (totalState.isTotalPingTarget(amount)) {
                                logNoChange("Ping Equalizer already targeting " + amount + "ms total ping.");
                            } else {
                                totalState.setTotalPing(amount);
                                notifyStateChange("Total ping set to " + amount + "ms.");
                            }
                        }
                        cryptoHandler.triggerHeartbeatForCommand();
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
                                        if (!ensureCommandAllowed()) {
                                            return 0;
                                        }
                                        boolean chatCommandsOnly = isChatCommandsOnly();
                                        if (chatCommandsOnly) {
                                            sendLocalMessage("\u00A7cChat is set to commands-only; mod changes must be announced publicly; command blocked.");
                                            return 0;
                                        }
                                        PingEqualizerState offState = PingEqualizerState.getInstance();
                                        if (offState.isOffMode()) {
                                            logNoChange("Ping Equalizer already disabled.");
                                        } else {
                                            offState.setOff();
                                            notifyStateChange("Disabled.");
                                        }
                                        cryptoHandler.triggerHeartbeatForCommand();
                                        return 1;
                                    })
                            )
                            .then(ClientCommandManager.literal("validate")
                                    .then(ClientCommandManager.argument("player", StringArgumentType.string())
                                            .suggests(onlinePlayerSuggestions)
                                            .executes(ctx -> {
                                                if (!ensureCommandAllowed()) {
                                                    return 0;
                                                }
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

    private void notifyStateChange(String message) {
        String finalMessage = maybeAppendDeprecationNotice(message);
        if (finalMessage.equals(lastMessage)) {
            return;
        }
        lastMessage = finalMessage;
        sendPublicModeAnnouncement(message, finalMessage);
    }

    private void sendLocalMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(maybeAppendDeprecationNotice(message)), false);
        }
    }

    private void sendRawLocalMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            // Always include the deprecation notice on client-visible outputs
            client.player.sendMessage(Text.literal(maybeAppendDeprecationNotice(message)), false);
        }
    }

    private void sendPublicModeAnnouncement(String message, String fallbackLog) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null || message == null || message.isEmpty()) {
            return;
        }
        // Remove any duplicate "Ping Equalizer" prefix so the public announcement
        // only shows the tag once in brackets.
        String normalized = message == null ? "" : message.replaceAll("(?i)^\\s*Ping Equalizer:?\\s*", "");
        String sanitized = stripFormattingCodes(normalized.replace("\n", " ").trim());
        if (sanitized.isEmpty()) {
            return;
        }
        String outbound = "[Ping Equalizer] " + sanitized;
        try {
            client.getNetworkHandler().sendChatMessage(outbound);
            // Always show a client-only deprecation warning (not public) so the user knows
            // their build is deprecated without duplicating the full public announcement.
            showClientDeprecationWarning();
        } catch (Exception ignored) {
            // Intentionally do not show a client-side fallback announcement here.
            // Mod state changes must be announced publicly; commands are blocked when
            // the server cannot be reached, so no local fallback is printed.
        }
    }

    private String stripFormattingCodes(String value) {
        return value.replaceAll("\u00A7.", "");
    }

    private void logNoChange(String message) {
        sendLocalMessage(message);
    }

    private boolean ensureCommandAllowed() {
        if (cryptoHandler != null && cryptoHandler.beginCommandExecution()) {
            return true;
        }
        sendLocalMessage(COMMAND_RATE_LIMIT_NOTICE);
        return false;
    }

    private String maybeAppendDeprecationNotice(String message) {
        CryptoHandler handler = cryptoHandler;
        if (handler != null && handler.isCurrentVersionDeprecated()) {
            String notice = getDeprecationNotice();
            if (message == null || message.isEmpty()) {
                return notice;
            }
            if (message.contains(notice)) {
                return message;
            }
            if (message.endsWith("\n")) {
                return message + notice;
            }
            return message + "\n" + notice;
        }
        return message;
    }

    private String getDeprecationNotice() {
        return "\u00A7cA newer Ping Equalizer build is available. Please update to stay trusted.";
    }

    private void showClientDeprecationWarning() {
        CryptoHandler handler = cryptoHandler;
        if (handler == null || !handler.isCurrentVersionDeprecated()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        String notice = getDeprecationNotice();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(notice), false);
        } else if (client.inGameHud != null && client.inGameHud.getChatHud() != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal(notice));
        }
    }

    private boolean isChatCommandsOnly() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return false;
        try {
            java.lang.reflect.Field f = mc.options.getClass().getField("chatVisibility");
            Object v = f.get(mc.options);
            return v != null && "SYSTEM".equalsIgnoreCase(v.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private UUID parseUuid(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }

        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
        }

        if (input.length() == 32 && input.matches("[0-9a-fA-F]+")) {
            try {
                String withDashes = input.substring(0, 8) + "-" +
                        input.substring(8, 12) + "-" +
                        input.substring(12, 16) + "-" +
                        input.substring(16, 20) + "-" +
                        input.substring(20, 32);
                return UUID.fromString(withDashes);
            } catch (IllegalArgumentException ignored) {
            }
        }

        return null;
    }

    private String formatValidationResult(net.ravenclaw.ravenclawspingequalizer.cryptography.ApiService.PlayerValidationResult result, String input) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean isSelf = false;
        if (client != null && client.getSession() != null) {
            String selfUsername = client.getSession().getUsername();
            UUID selfUuid = client.getSession().getUuidOrNull();
            if (!result.username().isEmpty() && result.username().equalsIgnoreCase(selfUsername)) {
                isSelf = true;
            }
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
        if (result.isServerUnreachable()) {
            return isSelf ? formatClientSideFallback() : "\u00A7cUnable to reach validation server. Please try again later.";
        }

        if (!result.isConnected() && (result.username() == null || result.username().isEmpty())) {
            return "\u00A7cPlayer not found or has never connected.";
        }

        long now = System.currentTimeMillis();
        long heartbeatAge = now - result.lastHeartbeat();
        boolean isStale = heartbeatAge > 40000;

        if (isStale) {
            return "\u00A7cPlayer is not currently connected. Last seen: " + formatHeartbeatAge(heartbeatAge);
        }

        StringBuilder sb = new StringBuilder();
        boolean modeActive = !result.peMode().equalsIgnoreCase("off") && !result.peMode().equalsIgnoreCase("unknown");

        sb.append("\u00A76Player: \u00A7f").append(result.username());
        if (modeActive && !result.currentServer().isEmpty()) {
            sb.append(" \u00A77on \u00A7f").append(result.currentServer());
        }
        sb.append("\n");

        sb.append("\u00A76Mod Version: \u00A7f").append(result.modVersion().isEmpty() ? "unknown" : result.modVersion());
        sb.append("\n");

        sb.append("\u00A77[Hash=").append(result.isHashCorrect() ? "\u00A7aOK" : "\u00A7cBAD");
        sb.append("\u00A77, Signed=").append(result.isSigned() ? "\u00A7aYES" : "\u00A7cNO");
        sb.append("\u00A77, SignatureValid=").append(result.isSignatureCorrect() ? "\u00A7aYES" : "\u00A7cNO");
        sb.append("\u00A77]");
        sb.append("\n");

        String modeDisplay = formatMode(result.peMode());
        sb.append("\u00A76Status: \u00A7f").append(modeDisplay);
        if (modeActive) {
            sb.append(" \u00A77| Delay: \u00A7f").append(result.peDelay()).append("ms");
            sb.append(" \u00A77| Base: \u00A7f").append(result.peBasePing()).append("ms");
            sb.append(" \u00A77| Total: \u00A7f").append(result.peTotalPing()).append("ms");
        }
        sb.append("\n");

        sb.append("\u00A76Mod State: ");
        sb.append(getModStateDescription(result.isHashCorrect(), result.isSignatureCorrect(), result.modStatus(), result.isSigned()));
        sb.append("\n");

        sb.append("\u00A77LastHeartbeat: ").append(formatHeartbeatAge(heartbeatAge));

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
            return (seconds <= 40 ? "\u00A7a" : "\u00A7c") + seconds + "s ago";
        }
        long minutes = seconds / 60;
        return "\u00A7c" + minutes + "m ago";
    }

    private String getModStateDescription(boolean hashCorrect, boolean signatureCorrect, String modStatus, boolean isSigned) {
        if (hashCorrect && signatureCorrect && isSigned) {
            return "\u00A7aFully validated.";
        } else if (hashCorrect && !isSigned) {
            return "\u00A7cSignature missing. The mod is not cryptographically verified.";
        } else if (hashCorrect && !signatureCorrect) {
            return "\u00A7cSignature verification failed. The status cannot be trusted.";
        } else if (!hashCorrect && signatureCorrect) {
            return "\u00A7cMod hash mismatch with valid signature. Private key is compromised.";
        } else {
            return "\u00A7cModified mod with no valid signature. Status cannot be trusted.";
        }
    }

    private String formatClientSideFallback() {
        MinecraftClient client = MinecraftClient.getInstance();
        PingEqualizerState peState = PingEqualizerState.getInstance();

        StringBuilder sb = new StringBuilder();
        sb.append("\u00A7eServer unreachable - showing local values only\n");

        String username = client.getSession() != null ? client.getSession().getUsername() : "Unknown";
        sb.append("\u00A76Player: \u00A7f").append(username);
        String currentServer = cryptoHandler != null ? cryptoHandler.getCurrentServer() : "";
        if (!currentServer.isEmpty()) {
            sb.append(" \u00A77on \u00A7f").append(currentServer);
        }
        sb.append("\n");

        boolean isSigned = cryptoHandler != null && cryptoHandler.canSign();
        sb.append("\u00A77[Hash=\u00A7f").append(cryptoHandler != null ? "local" : "N/A");
        sb.append("\u00A77, Signed=").append(isSigned ? "\u00A7aYES" : "\u00A7cNO");
        sb.append("\u00A77, SignatureValid=\u00A7eN/A\u00A77]");
        sb.append("\n");

        String modeDisplay = formatMode(peState.getMode().name());
        boolean modeActive = peState.getMode() != PingEqualizerState.Mode.OFF;
        sb.append("\u00A76Status: \u00A7f").append(modeDisplay);
        if (modeActive) {
            sb.append(" \u00A77| Delay: \u00A7f").append(peState.getCurrentDelayMs()).append("ms");
            sb.append(" \u00A77| Base: \u00A7f").append(peState.getBasePing()).append("ms");
            sb.append(" \u00A77| Total: \u00A7f").append(peState.getTotalPing()).append("ms");
        }
        sb.append("\n");

        sb.append("\u00A76Mod State: ");
        if (isSigned) {
            sb.append("\u00A7aLocally validated (signed)");
        } else {
            sb.append("\u00A7eLocally running (unsigned)");
        }
        sb.append("\n");

        sb.append("\u00A77LastHeartbeat: \u00A7eUnable to verify");

        return sb.toString();
    }
}
