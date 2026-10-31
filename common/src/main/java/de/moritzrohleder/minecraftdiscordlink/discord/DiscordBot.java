package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.data.StateStore;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.maintenance.Maintenance;
import de.moritzrohleder.minecraftdiscordlink.moderation.DiscordModeration;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.monitoring.ReportTracker;
import de.moritzrohleder.minecraftdiscordlink.notifications.NotificationEvent;
import de.moritzrohleder.minecraftdiscordlink.notifications.SubscriptionStore;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import net.dv8tion.jda.api.EmbedBuilder;
import com.neovisionaries.ws.client.WebSocketFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.minecraft.server.MinecraftServer;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Connection of the mod to Discord.
 * <p>
 * The bot connects in the background, so the server starts independently of Discord. Once both the bot is ready
 * and the server has started, the "Server started" message is sent and the status updates begin, no matter which
 * of the two happens first.
 * <p>
 * All Discord work runs on a single daemon thread owned by this class. The bot token and the channel IDs are taken
 * from the config at construction and do not change until the next server start.
 */
public final class DiscordBot {

    /**
     * Gateway intents needed by the mod. Message Content and Server Members are privileged intents; Guild
     * Expressions provides the custom emojis, Guild Moderation the bans for the ban sync.
     */
    private static final EnumSet<GatewayIntent> INTENTS = EnumSet.of(
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.GUILD_MEMBERS,
            GatewayIntent.GUILD_EXPRESSIONS,
            GatewayIntent.DIRECT_MESSAGES,
            GatewayIntent.GUILD_MODERATION);

    /** Maximum time the server waits on shutdown for the "Server stopped" message and the disconnect. */
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);


    private final String token;
    private final OptionalLong chatChannelId;
    private final OptionalLong consoleChannelId;
    private final MinecraftServer server;
    private final Instant startTime;
    private final PlayerStats stats;
    private final Linking linking;
    private final StateStore state;
    private final Moderation moderation;
    private final Maintenance maintenance;
    private final ReportTracker report;
    private final SubscriptionStore subscriptions;
    private final ScheduledExecutorService executor;

    /** The bot, set as soon as it is built. */
    private volatile JDA jda;
    /** The chat bridge, set once the bot is ready if the chat channel is available. */
    private volatile ChatBridge chatBridge;
    /** The Discord side of the linking, set once the bot is ready if one of the channels is available. */
    private volatile AccountLinking accountLinking;
    /** The slash commands, set once the bot is ready if one of the channels is available. */
    private volatile SlashCommands slashCommands;
    /** The notifications of subscribers, set once the bot is ready. */
    private volatile Notifications notifications;
    /** The monitoring channel, set once both the bot is ready and the server has started. */
    private volatile MonitoringChannel monitoring;
    /** The ban sync, set once the bot is ready if one of the channels is available. */
    private volatile BanSync banSync;
    /** Whether the bot is connected and ready. */
    private boolean ready;
    /** Whether the server has finished starting. */
    private boolean serverStarted;

    /**
     * Creates the bot without connecting yet, see {@link #connect()}.
     *
     * @param token         the bot token
     * @param config        the config at server start, for the channel IDs
     * @param server        the starting server
     * @param startTime     when the server was started, for {@code {uptime}}
     * @param stats         the player statistics, for the unique player placeholders
     * @param linking       the account linking
     * @param state         the stored state of the mod
     * @param moderation    the moderation, for the moderation commands and the moderation channel
     * @param maintenance   the maintenance mode, for the topic and the bot status
     * @param report        the numbers of the daily report
     * @param subscriptions the subscriptions of the notifications
     */
    public DiscordBot(String token, ModConfig config, MinecraftServer server, Instant startTime, PlayerStats stats,
                      Linking linking, StateStore state, Moderation moderation, Maintenance maintenance,
                      ReportTracker report, SubscriptionStore subscriptions) {
        this.token = token;
        this.chatChannelId = config.chat().channelId();
        this.consoleChannelId = config.console().channelId();
        this.server = server;
        this.startTime = startTime;
        this.stats = stats;
        this.linking = linking;
        this.state = state;
        this.moderation = moderation;
        this.maintenance = maintenance;
        this.report = report;
        this.subscriptions = subscriptions;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, Constants.MOD_NAME + " Discord");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Connects to Discord in the background. Connection errors are logged; the mod stays inactive then.
     */
    public void connect() {
        executor.execute(() -> {
            try {
                jda = JDABuilder.createLight(token, INTENTS)
                        // All members are cached to resolve mentions and roles by name
                        .setMemberCachePolicy(MemberCachePolicy.ALL)
                        .setChunkingFilter(ChunkingFilter.ALL)
                        .enableCache(CacheFlag.EMOJI)
                        .addEventListeners(new ShutdownReasonLogger())
                        .setWebsocketFactory(webSocketFactory())
                        .build();
                jda.awaitReady();
                setUpFeatures();
                onReady();
            } catch (InvalidTokenException e) {
                Constants.LOG.error("Discord rejected the bot token. Check 'discord.token' in the config.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                Constants.LOG.error("Could not connect to Discord: {}", e.getMessage());
            }
        });
    }

    /**
     * Creates the factory for the gateway connection. HTTP requests of JDA use the JVM's proxy settings
     * automatically, the WebSocket library does not; so an HTTPS proxy configured with the system properties
     * {@code https.proxyHost} and {@code https.proxyPort} is applied to it as well.
     *
     * @return the WebSocket factory
     */
    private static WebSocketFactory webSocketFactory() {
        WebSocketFactory factory = new WebSocketFactory();
        String proxyHost = System.getProperty("https.proxyHost");
        if (proxyHost != null && !proxyHost.isBlank()) {
            factory.getProxySettings().setHost(proxyHost).setPort(Integer.getInteger("https.proxyPort", 443));
        }
        return factory;
    }

    /**
     * Called by the mod when the server has finished starting.
     */
    public synchronized void onServerStarted() {
        serverStarted = true;
        if (ready) {
            start();
        }
    }

    /**
     * Sets up the features once the bot is connected: checks the channels and starts the chat bridge, the console
     * channel, the account linking and the slash commands. Runs on the bot thread without holding the lock, as it
     * waits for Discord.
     */
    private void setUpFeatures() {
        Constants.LOG.info("Connected to Discord as {}", jda.getSelfUser().getName());
        checkChannel(chatChannelId, "chat channel", "chat bridge");
        checkChannel(consoleChannelId, "console channel", "console channel");
        chatChannel().ifPresent(channel -> {
            ChatBridge bridge = new ChatBridge(jda, channel, server, linking);
            bridge.setUpWebhook();
            jda.addEventListener(bridge);
            chatBridge = bridge;
        });
        channel(consoleChannelId).ifPresent(channel -> {
            ConsoleChannel console = new ConsoleChannel(channel, server, executor);
            jda.addEventListener(console);
            console.start();
        });
        notifications = new Notifications(jda, subscriptions, linking.links());
        ModerationChannel moderationChannel = new ModerationChannel(jda);
        moderation.log().setListener(moderationChannel::send);
        guild().ifPresent(guild -> {
            AccountLinking accounts = new AccountLinking(jda, guild, server, linking, state, executor);
            SlashCommands commands = new SlashCommands(guild, linking, server, startTime, stats, moderation,
                    subscriptions);
            BanSync sync = new BanSync(guild, server, linking.links(), executor);
            jda.addEventListener(accounts, commands, sync);
            sync.start();
            banSync = sync;
            commands.register();
            slashCommands = commands;
            accountLinking = accounts;
            accounts.apply();
        });
    }

    /**
     * Returns the Discord server of the mod: the server of the chat channel, otherwise of the console channel.
     *
     * @return the Discord server, empty if neither channel is available
     */
    private Optional<Guild> guild() {
        return chatChannel().or(() -> channel(consoleChannelId)).map(GuildMessageChannel::getGuild);
    }

    /**
     * Called on the bot thread once the bot is connected and its features are set up.
     */
    private synchronized void onReady() {
        ready = true;
        if (serverStarted) {
            start();
        }
    }

    /**
     * Starts the features that need both a ready bot and a started server: the start message, the bot status and
     * the channel topic.
     */
    private void start() {
        if (ConfigManager.get().chat().events().serverStart()) {
            chatChannel().ifPresent(channel -> channel.sendMessageEmbeds(
                    embed("✅ Server started", EmbedColors.GREEN)).queue(null, this::logSendError));
        }
        notifications.onEvent(NotificationEvent.SERVER_START, "✅ The Minecraft server has started.",
                EmbedColors.GREEN);
        maintenance.addListener(change -> notifications.onEvent(NotificationEvent.MAINTENANCE, change
                .map(status -> "🔧 The Minecraft server is now in maintenance mode."
                        + status.reason().map(reason -> " Reason: " + DiscordText.escape(reason)).orElse(""))
                .orElse("✅ The maintenance mode of the Minecraft server is over."), EmbedColors.BLURPLE));
        MonitoringChannel monitor = new MonitoringChannel(jda, server, startTime, stats, state, report, executor);
        monitor.start();
        maintenance.addListener(change -> monitor.updateStatusNow());
        monitoring = monitor;
        StatusUpdater status = new StatusUpdater(jda, server, startTime, stats, executor);
        status.start();
        maintenance.addListener(change -> status.updateNow());
        chatChannel().ifPresent(channel -> {
            if (channel instanceof StandardGuildMessageChannel topicChannel) {
                TopicUpdater topic = new TopicUpdater(topicChannel, server, startTime, stats, executor);
                topic.start();
                maintenance.addListener(change -> topic.updateNow());
            } else {
                Constants.LOG.error("The chat channel has no topic. The topic updates stay inactive.");
            }
        });
    }

    /**
     * Returns the chat bridge.
     *
     * @return the chat bridge, empty while the bot is not ready or if the chat channel is not available
     */
    public Optional<ChatBridge> chatBridge() {
        return Optional.ofNullable(chatBridge);
    }

    /**
     * Returns the Discord side of the moderation.
     *
     * @return the ban sync, empty while the bot is not ready or if no channel is available
     */
    public Optional<DiscordModeration> moderation() {
        return Optional.ofNullable(banSync);
    }

    /**
     * Returns the Discord side of the account linking.
     *
     * @return the account linking, empty while the bot is not ready or if no channel is available
     */
    public Optional<AccountLinking> accountLinking() {
        return Optional.ofNullable(accountLinking);
    }

    /**
     * Applies a reloaded config: sets up the chat webhook again, as {@code chat.webhook} may have changed, and
     * applies the linked role and the whitelist roles and registers the slash commands again for the leaderboard
     * categories.
     */
    public void onConfigReloaded() {
        ChatBridge bridge = chatBridge;
        if (bridge != null) {
            try {
                executor.execute(bridge::setUpWebhook);
            } catch (RejectedExecutionException ignored) {
                // The bot is shutting down
            }
        }
        AccountLinking accounts = accountLinking;
        if (accounts != null) {
            accounts.apply();
        }
        SlashCommands commands = slashCommands;
        if (commands != null) {
            commands.register();
        }
        MonitoringChannel monitor = monitoring;
        if (monitor != null) {
            // The monitoring channel or the format may have changed
            monitor.updateStatusNow();
        }
    }

    /**
     * Notifies the subscribers of a player who came online.
     *
     * @param player the player's UUID
     * @param name   the player's name
     */
    public void onPlayerJoin(UUID player, String name) {
        Notifications notify = notifications;
        if (notify != null) {
            notify.onPlayerJoin(player, name);
        }
    }

    /**
     * Sends the crash notice to the monitoring channel, waiting a few seconds at most. Called before the server stops
     * after a crash.
     *
     * @param title     the title of the crash report
     * @param exception the exception that crashed the server
     */
    public void onCrash(String title, String exception) {
        MonitoringChannel monitor = monitoring;
        if (monitor != null) {
            monitor.sendCrash(title, exception, SHUTDOWN_TIMEOUT);
        }
    }

    /**
     * Sends the "Server stopped" message and disconnects. Blocks for at most {@link #SHUTDOWN_TIMEOUT}.
     */
    public void shutdown() {
        long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
        MonitoringChannel monitor = monitoring;
        if (monitor != null) {
            monitor.markOffline(SHUTDOWN_TIMEOUT.dividedBy(3));
        }
        Notifications notify = notifications;
        if (notify != null && isReady()) {
            try {
                notify.onEvent(NotificationEvent.SERVER_STOP, "🛑 The Minecraft server is stopping.", EmbedColors.RED)
                        .get(SHUTDOWN_TIMEOUT.dividedBy(3).toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Constants.LOG.warn("Could not send all stop notifications: {}", e.toString());
            }
        }
        ChatBridge bridge = chatBridge;
        if (bridge != null) {
            // Players leaving while the server stops are not reported anymore
            bridge.deactivate();
        }
        // Stops the status updates and an unfinished connection attempt
        executor.shutdownNow();
        moderation.log().setListener(entry -> { });

        JDA bot = jda;
        if (bot == null) {
            return;
        }
        try {
            if (isReady() && ConfigManager.get().chat().events().serverStop()) {
                Optional<GuildMessageChannel> channel = chatChannel();
                if (channel.isPresent()) {
                    channel.get().sendMessageEmbeds(embed("🛑 Server stopped", EmbedColors.RED))
                            .submit().get(remaining(deadline), TimeUnit.NANOSECONDS);
                }
            }
            bot.shutdown();
            if (!bot.awaitShutdown(remaining(deadline), TimeUnit.NANOSECONDS)) {
                bot.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            bot.shutdownNow();
        } catch (Exception e) {
            Constants.LOG.error("Could not send the server stop message to Discord: {}", e.toString());
            bot.shutdownNow();
        }
    }

    /**
     * Explains why Discord closed the connection if the reason is a configuration problem.
     */
    private static final class ShutdownReasonLogger extends ListenerAdapter {

        @Override
        public void onShutdown(ShutdownEvent event) {
            CloseCode code = event.getCloseCode();
            if (code == CloseCode.DISALLOWED_INTENTS) {
                Constants.LOG.error("Discord refused the connection because of missing privileged intents. Enable "
                        + "'Server Members Intent' and 'Message Content Intent' for the bot in the Discord "
                        + "Developer Portal.");
            } else if (code == CloseCode.AUTHENTICATION_FAILED) {
                Constants.LOG.error("Discord rejected the bot token. Check 'discord.token' in the config.");
            }
        }
    }

    /**
     * Checks whether the bot is connected and ready.
     *
     * @return whether the bot is connected and ready
     */
    private synchronized boolean isReady() {
        return ready;
    }

    /**
     * Looks up the chat channel.
     *
     * @return the chat channel, empty if not configured or not accessible
     */
    private Optional<GuildMessageChannel> chatChannel() {
        return channel(chatChannelId);
    }

    /**
     * Looks up a channel by its configured ID.
     *
     * @param id the channel ID from the config
     * @return the channel, empty if not configured or not accessible
     */
    private Optional<GuildMessageChannel> channel(OptionalLong id) {
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jda.getChannelById(GuildMessageChannel.class, id.getAsLong()));
    }

    /**
     * Logs an error if a configured channel cannot be found, e.g. because the bot is not a member of its server or
     * cannot see the channel.
     *
     * @param id      the channel ID from the config
     * @param name    name of the channel for the error message
     * @param feature name of the feature that stays inactive
     */
    private void checkChannel(OptionalLong id, String name, String feature) {
        if (id.isPresent() && channel(id).isEmpty()) {
            Constants.LOG.error("The {} {} was not found or the bot cannot see it. The {} stays inactive.",
                    name, id.getAsLong(), feature);
        }
    }

    /**
     * Creates a simple embed.
     *
     * @param text  the embed text
     * @param color the color of the embed bar
     * @return an embed with only a description and a color
     */
    private static MessageEmbed embed(String text, Color color) {
        return new EmbedBuilder().setDescription(text).setColor(color).build();
    }

    /**
     * Logs a message that could not be sent.
     *
     * @param error error of a failed message
     */
    private void logSendError(Throwable error) {
        Constants.LOG.error("Could not send a message to Discord: {}", error.getMessage());
    }

    /**
     * Calculates the time left until a deadline.
     *
     * @param deadline deadline as returned by {@link System#nanoTime()}
     * @return nanoseconds left until the deadline, at least 0
     */
    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }
}
