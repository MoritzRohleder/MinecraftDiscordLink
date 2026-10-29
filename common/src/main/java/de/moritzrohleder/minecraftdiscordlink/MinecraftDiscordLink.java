package de.moritzrohleder.minecraftdiscordlink;

import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.discord.ChatBridge;
import de.moritzrohleder.minecraftdiscordlink.discord.DiscordBot;
import de.moritzrohleder.minecraftdiscordlink.platform.Services;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Loader independent entry point, lifecycle and game events of the mod.
 * <p>
 * The loader specific entry points call {@link #init()} when the mod is loaded and forward the server lifecycle and
 * player events to the other methods of this class. Chat messages, deaths and advancements are forwarded by the
 * mixins in the {@code mixin} package. All methods are called on the server thread.
 */
public final class MinecraftDiscordLink {

    private static final Color GREEN = new Color(0x57F287);
    private static final Color RED = new Color(0xED4245);
    /** Almost black: Discord shows an embed with color 0 without a color bar. */
    private static final Color BLACK = new Color(0x010101);
    private static final Color GOLD = new Color(0xF1C40F);

    private static final String LOUDSPEAKER = "\uD83D\uDCE2";
    private static final String INBOX_TRAY = "\uD83D\uDCE5";
    private static final String OUTBOX_TRAY = "\uD83D\uDCE4";
    private static final String SKULL = "\uD83D\uDC80";
    private static final String TROPHY = "\uD83C\uDFC6";

    /** The Discord bot, {@code null} while the server is not running or the mod is inactive. */
    private static DiscordBot bot;
    /** The player statistics, {@code null} while the server is not running. */
    private static PlayerStats stats;

    private MinecraftDiscordLink() {
    }

    /**
     * Initializes the mod: loads the config.
     */
    public static void init() {
        Constants.LOG.info("Loading {} on {}", Constants.MOD_NAME, Services.PLATFORM.getPlatformName());
        ConfigManager.load(Services.PLATFORM.getConfigDirectory());
    }

    /**
     * Called when the server is starting. Loads the player statistics and starts connecting to Discord in the
     * background if a token is configured.
     *
     * @param server the starting server
     */
    public static void onServerStarting(MinecraftServer server) {
        stats = new PlayerStats(server.getServerDirectory().resolve(Constants.MOD_ID), Clock.systemDefaultZone());
        ModConfig config = ConfigManager.get();
        config.discord().token().ifPresent(token -> {
            bot = new DiscordBot(token, config, server, Instant.now(), stats);
            bot.connect();
        });
    }

    /**
     * Called when the server has finished starting.
     *
     * @param server the started server
     */
    public static void onServerStarted(MinecraftServer server) {
        if (bot != null) {
            bot.onServerStarted();
        }
    }

    /**
     * Reloads the config and applies it to the running bot. Token and channel IDs stay unchanged until a restart.
     */
    public static void reloadConfig() {
        ConfigManager.reload();
        if (bot != null) {
            bot.onConfigReloaded();
        }
    }

    /**
     * Called before a chat message is broadcast to all players: player chat, {@code /say} and {@code /me}.
     *
     * @param message the message
     * @param sender  the sending player, {@code null} for the console and command blocks
     * @param bound   the chat type and the sender's name
     */
    public static void onChatMessage(PlayerChatMessage message, @Nullable ServerPlayer sender, ChatType.Bound bound) {
        Optional<ChatBridge> bridge = chatBridge();
        if (bridge.isEmpty()) {
            return;
        }
        Optional<ResourceKey<ChatType>> type = bound.chatType().unwrapKey();
        if (type.isEmpty()) {
            return;
        }
        String text = message.decoratedContent().getString();
        String senderName = bound.name().getString();

        if (type.get().equals(ChatType.CHAT) && sender != null) {
            bridge.get().sendPlayerMessage(sender.getUUID(), playerName(sender), text);
        } else if (type.get().equals(ChatType.SAY_COMMAND)) {
            if (sender != null) {
                bridge.get().sendPlayerMessage(sender.getUUID(), playerName(sender), LOUDSPEAKER + " " + text);
            } else {
                bridge.get().sendBotMessage(LOUDSPEAKER + " **" + DiscordText.escape(senderName) + "**: " + text);
            }
        } else if (type.get().equals(ChatType.EMOTE_COMMAND)) {
            if (sender != null) {
                bridge.get().sendPlayerMessage(sender.getUUID(), playerName(sender), "*\\* " + text + "*");
            } else {
                bridge.get().sendBotMessage("*\\* " + DiscordText.escape(senderName) + " " + text + "*");
            }
        }
    }

    /**
     * Called when a player has joined the server. Records the player in the statistics and reports the join.
     *
     * @param player the player
     */
    public static void onPlayerJoin(ServerPlayer player) {
        if (stats != null) {
            stats.recordJoin(player.getUUID(),
                    player.server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());
        }
        if (ConfigManager.get().chat().events().join()) {
            sendPlayerEvent(player, INBOX_TRAY + " **" + DiscordText.escape(playerName(player)) + "** joined the game",
                    GREEN);
        }
    }

    /**
     * Called when a player has left the server.
     *
     * @param player the player
     */
    public static void onPlayerLeave(ServerPlayer player) {
        if (ConfigManager.get().chat().events().leave()) {
            sendPlayerEvent(player, OUTBOX_TRAY + " **" + DiscordText.escape(playerName(player)) + "** left the game",
                    RED);
        }
    }

    /**
     * Called when a player dies. The death is forwarded if vanilla shows its message to everyone, i.e. the game
     * rule {@code showDeathMessages} is on and the player's team does not hide death messages.
     *
     * @param player the player
     */
    public static void onPlayerDeath(ServerPlayer player) {
        if (!ConfigManager.get().chat().events().death()
                || !player.level().getGameRules().getBoolean(GameRules.RULE_SHOWDEATHMESSAGES)) {
            return;
        }
        Team team = player.getTeam();
        if (team != null && team.getDeathMessageVisibility() != Team.Visibility.ALWAYS) {
            return;
        }
        String message = player.getCombatTracker().getDeathMessage().getString();
        sendPlayerEvent(player, SKULL + " " + DiscordText.boldName(message, playerName(player)), BLACK);
    }

    /**
     * Called when a player has completed an advancement. It is forwarded if vanilla announces it in the chat, i.e.
     * the advancement is announced and the game rule {@code announceAdvancements} is on.
     *
     * @param player      the player
     * @param advancement the completed advancement
     */
    public static void onAdvancement(ServerPlayer player, AdvancementHolder advancement) {
        Optional<DisplayInfo> display = advancement.value().display();
        if (!ConfigManager.get().chat().events().advancement() || display.isEmpty()
                || !display.get().shouldAnnounceChat()
                || !player.level().getGameRules().getBoolean(GameRules.RULE_ANNOUNCE_ADVANCEMENTS)) {
            return;
        }
        // e.g. "%s has made the advancement %s"
        String format = Language.getInstance()
                .getOrDefault("chat.type.advancement." + display.get().getType().getSerializedName());
        String text = String.format(format,
                "**" + DiscordText.escape(playerName(player)) + "**",
                "**[" + DiscordText.escape(display.get().getTitle().getString()) + "]**");
        sendPlayerEvent(player, TROPHY + " " + text, GOLD);
    }

    /**
     * Sends an event of a player to the chat channel, if the chat bridge is active.
     *
     * @param player      the player
     * @param description the embed text
     * @param color       the color of the embed bar
     */
    private static void sendPlayerEvent(ServerPlayer player, String description, Color color) {
        UUID uuid = player.getUUID();
        String name = playerName(player);
        chatBridge().ifPresent(bridge -> bridge.sendPlayerEvent(uuid, name, description, color));
    }

    /**
     * Returns the name of a player shown in Discord.
     *
     * @param player the player
     * @return the player's name
     */
    private static String playerName(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    /**
     * Returns the chat bridge.
     *
     * @return the chat bridge, empty if the bot is not running or the chat channel is not available
     */
    private static Optional<ChatBridge> chatBridge() {
        return bot != null ? bot.chatBridge() : Optional.empty();
    }

    /**
     * Called when the server is stopping. Sends the stop message and disconnects from Discord, blocking for a few
     * seconds at most.
     *
     * @param server the stopping server
     */
    public static void onServerStopping(MinecraftServer server) {
        if (bot != null) {
            bot.shutdown();
            bot = null;
        }
    }
}
