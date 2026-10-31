package de.moritzrohleder.minecraftdiscordlink;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.console.ConsoleCapture;
import de.moritzrohleder.minecraftdiscordlink.data.StateStore;
import de.moritzrohleder.minecraftdiscordlink.discord.AccountLinking;
import de.moritzrohleder.minecraftdiscordlink.discord.ChatBridge;
import de.moritzrohleder.minecraftdiscordlink.discord.DiscordBot;
import de.moritzrohleder.minecraftdiscordlink.discord.EmbedColors;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.maintenance.Maintenance;
import de.moritzrohleder.minecraftdiscordlink.moderation.DiscordModeration;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationLog;
import de.moritzrohleder.minecraftdiscordlink.platform.Services;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Loader independent entry point, lifecycle and game events of the mod.
 * <p>
 * The loader specific entry points call {@link #init()} when the mod is loaded and forward the server lifecycle and
 * player events to the other methods of this class. Chat messages, deaths and advancements are forwarded by the
 * mixins in the {@code mixin} package. All methods are called on the server thread.
 */
public final class MinecraftDiscordLink {

    private static final String LOUDSPEAKER = "\uD83D\uDCE2";
    private static final String INBOX_TRAY = "\uD83D\uDCE5";
    private static final String OUTBOX_TRAY = "\uD83D\uDCE4";
    private static final String SKULL = "\uD83D\uDC80";
    private static final String TROPHY = "\uD83C\uDFC6";

    /** The Discord bot, {@code null} while the server is not running or the mod is inactive. */
    private static DiscordBot bot;
    /** The player statistics, {@code null} while the server is not running. */
    private static PlayerStats stats;
    /** The account linking, {@code null} while the server is not running. */
    private static Linking linking;
    /** When the server was started, {@code null} while the server is not running. */
    private static Instant startTime;
    /** The maintenance mode, {@code null} while the server is not running. */
    private static Maintenance maintenance;
    /** The moderation, {@code null} while the server is not running. */
    private static Moderation moderation;
    /** Whether the server has finished starting; list changes before are loading, not moderation. */
    private static volatile boolean started;
    /** Removes expired bans every minute while the server runs. */
    private static ScheduledExecutorService moderationTimer;

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
        startTime = Instant.now();
        Path dataDirectory = server.getServerDirectory().resolve(Constants.MOD_ID);
        stats = new PlayerStats(dataDirectory, Clock.systemDefaultZone());
        linking = new Linking(server, dataDirectory, MinecraftDiscordLink::accountLinking, stats);
        StateStore state = new StateStore(dataDirectory);
        maintenance = new Maintenance(server, state, linking.links(), discordId -> accountLinking()
                .map(accounts -> accounts.isAdministrator(discordId)).orElse(false));
        moderation = new Moderation(server, new ModerationLog(dataDirectory, ZoneId.systemDefault()), linking,
                MinecraftDiscordLink::discordModeration);
        ModConfig config = ConfigManager.get();
        config.discord().token().ifPresent(token -> {
            if (config.console().channelId().isPresent()) {
                // Captured from the start, so the console channel also shows the server start
                ConsoleCapture.install();
            }
            bot = new DiscordBot(token, config, server, startTime, stats, linking, state, moderation, maintenance);
            bot.connect();
        });
    }

    /**
     * Called when the server has finished starting.
     *
     * @param server the started server
     */
    public static void onServerStarted(MinecraftServer server) {
        started = true;
        moderation.removeExpiredBans();
        moderationTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, Constants.MOD_NAME + " Moderation");
            thread.setDaemon(true);
            return thread;
        });
        moderationTimer.scheduleAtFixedRate(() -> server.execute(MinecraftDiscordLink::removeExpiredBans),
                1, 1, TimeUnit.MINUTES);
        if (bot != null) {
            bot.onServerStarted();
        }
    }

    /**
     * Reloads the config and applies it to the running bot. Token and channel IDs stay unchanged until a restart.
     * Must be called on the server thread.
     *
     * @return the result message for the user who reloaded
     */
    public static String reloadConfig() {
        boolean restartNeeded = ConfigManager.reload();
        if (bot != null) {
            bot.onConfigReloaded();
        }
        return restartNeeded
                ? "Config reloaded. Changes to the bot token and the channel IDs need a server restart."
                : "Config reloaded.";
    }

    /**
     * Returns when the server was started.
     *
     * @return the start time, the current time while the server is not running
     */
    public static Instant startTime() {
        return startTime != null ? startTime : Instant.now();
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
            bridge.get().sendPlayerMessage(sender.getUUID(), webhookName(sender), text);
        } else if (type.get().equals(ChatType.SAY_COMMAND)) {
            if (sender != null) {
                bridge.get().sendPlayerMessage(sender.getUUID(), webhookName(sender), LOUDSPEAKER + " " + text);
            } else {
                bridge.get().sendBotMessage(LOUDSPEAKER + " **" + DiscordText.escape(senderName) + "**: " + text);
            }
        } else if (type.get().equals(ChatType.EMOTE_COMMAND)) {
            if (sender != null) {
                bridge.get().sendPlayerMessage(sender.getUUID(), webhookName(sender), "*\\* " + text + "*");
            } else {
                bridge.get().sendBotMessage("*\\* " + DiscordText.escape(senderName) + " " + text + "*");
            }
        }
    }

    /**
     * Called when a player has joined the server. Records the player in the statistics, sends the Discord members
     * as mention suggestions and reports the join.
     *
     * @param player the player
     */
    public static void onPlayerJoin(ServerPlayer player) {
        if (stats != null) {
            stats.recordJoin(player.getUUID(),
                    player.server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList());
        }
        accountLinking().ifPresent(accounts -> accounts.sendMentionSuggestions(List.of(player)));
        if (ConfigManager.get().chat().events().join()) {
            sendPlayerEvent(player, INBOX_TRAY + " **" + DiscordText.escape(playerName(player)) + "** joined the game",
                    EmbedColors.GREEN);
        }
    }

    /**
     * Called when a player has left the server. Records "last online" and reports the leave.
     *
     * @param player the player
     */
    public static void onPlayerLeave(ServerPlayer player) {
        if (stats != null) {
            stats.recordLeave(player.getUUID());
        }
        if (ConfigManager.get().chat().events().leave()) {
            sendPlayerEvent(player, OUTBOX_TRAY + " **" + DiscordText.escape(playerName(player)) + "** left the game",
                    EmbedColors.RED);
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
        sendPlayerEvent(player, SKULL + " " + DiscordText.boldName(message, playerName(player)), EmbedColors.BLACK);
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
        sendPlayerEvent(player, TROPHY + " " + text, EmbedColors.GOLD);
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
        String name = webhookName(player);
        chatBridge().ifPresent(bridge -> bridge.sendPlayerEvent(uuid, name, description, color));
    }

    /**
     * Returns the Minecraft name of a player.
     *
     * @param player the player
     * @return the player's name
     */
    private static String playerName(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    /**
     * Returns the name of a player used as webhook name in Discord: {@code linked_name_format} for linked players.
     *
     * @param player the player
     * @return the name
     */
    private static String webhookName(ServerPlayer player) {
        return linking != null ? linking.displayName(player) : playerName(player);
    }

    /**
     * Returns the account linking.
     *
     * @return the account linking, empty while the server is not running
     */
    public static Optional<Linking> linking() {
        return Optional.ofNullable(linking);
    }

    /**
     * Checks a joining player against the Discord whitelist. Called by a mixin during login.
     *
     * @param profile the joining player
     * @return the kick message, or {@code null} if the player may join
     */
    public static Component checkLogin(GameProfile profile) {
        Component maintenanceKick = maintenance != null ? maintenance.checkLogin(profile) : null;
        if (maintenanceKick != null) {
            return maintenanceKick;
        }
        return linking != null ? linking.checkLogin(profile) : null;
    }

    /**
     * Checks whether the Discord whitelist lets a player join, ignoring bans and the player limit. Called by a mixin
     * in place of the vanilla whitelist check.
     *
     * @param profile         the joining player
     * @param vanillaAllowed  whether the vanilla whitelist lets the player join
     * @return whether the player may join
     */
    public static boolean isWhitelisted(GameProfile profile, boolean vanillaAllowed) {
        if (linking == null || !ConfigManager.get().whitelist().active()) {
            return vanillaAllowed;
        }
        return linking.isWhitelisted(profile);
    }

    /**
     * Returns the maintenance mode.
     *
     * @return the maintenance mode, empty while the server is not running
     */
    public static Optional<Maintenance> maintenance() {
        return Optional.ofNullable(maintenance);
    }

    /**
     * Checks whether the maintenance mode is on.
     *
     * @return whether the maintenance mode is on
     */
    public static boolean maintenanceActive() {
        return maintenance != null && maintenance.status().isPresent();
    }

    /**
     * Returns the moderation.
     *
     * @return the moderation, empty while the server is not running
     */
    public static Optional<Moderation> moderation() {
        return Optional.ofNullable(moderation);
    }

    /**
     * Removes expired temporary bans, logging them and lifting the Discord bans. Called every minute and by a mixin
     * before the login checks, as Minecraft itself would remove them silently.
     */
    public static void removeExpiredBans() {
        if (started && moderation != null) {
            moderation.removeExpiredBans();
        }
    }

    /**
     * Called by a mixin after an entry was added to a vanilla ban or whitelist list.
     *
     * @param list  the list
     * @param entry the new entry
     */
    public static void onUserListAdded(StoredUserList<?, ?> list, StoredUserEntry<?> entry) {
        if (started && moderation != null) {
            moderation.onAdded(list, entry);
        }
    }

    /**
     * Called by a mixin before an entry is removed from a vanilla ban or whitelist list.
     *
     * @param list  the list
     * @param entry the entry being removed
     */
    public static void onUserListRemoved(StoredUserList<?, ?> list, StoredUserEntry<?> entry) {
        if (started && moderation != null) {
            moderation.onRemoved(list, entry);
        }
    }

    /**
     * Called by a mixin when the vanilla whitelist is switched on or off.
     *
     * @param on whether it is switched on
     */
    public static void onWhitelistSwitched(boolean on) {
        if (started && moderation != null) {
            moderation.onWhitelistSwitched(on);
        }
    }

    /**
     * Called by a mixin when the vanilla {@code /kick} command kicks a player.
     *
     * @param moderator who ran the command
     * @param player    the kicked player
     * @param reason    the reason
     */
    public static void onVanillaKick(String moderator, ServerPlayer player, String reason) {
        if (started && moderation != null) {
            moderation.onVanillaKick(moderator, player, reason);
        }
    }

    /**
     * Returns the Discord side of the moderation.
     *
     * @return the ban sync, empty while the bot is not ready
     */
    private static Optional<DiscordModeration> discordModeration() {
        return bot != null ? bot.moderation() : Optional.empty();
    }

    /**
     * Finds a member of the Discord server by username.
     *
     * @param username the Discord username
     * @return empty if Discord is not available, otherwise the member's ID or empty if there is no such member
     */
    public static Optional<OptionalLong> findDiscordMember(String username) {
        return accountLinking().map(accounts -> accounts.findMember(username));
    }

    /**
     * Returns the Discord side of the account linking.
     *
     * @return the account linking, empty while the bot is not connected
     */
    private static Optional<AccountLinking> accountLinking() {
        return bot != null ? bot.accountLinking() : Optional.empty();
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
        started = false;
        if (moderationTimer != null) {
            moderationTimer.shutdownNow();
            moderationTimer = null;
        }
        if (bot != null) {
            bot.shutdown();
            bot = null;
        }
        ConsoleCapture.uninstall();
    }
}
