package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.data.StateStore;
import de.moritzrohleder.minecraftdiscordlink.leaderboard.Leaderboards;
import de.moritzrohleder.minecraftdiscordlink.monitoring.LagDetector;
import de.moritzrohleder.minecraftdiscordlink.monitoring.ReportTracker;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import de.moritzrohleder.minecraftdiscordlink.text.Placeholders;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The monitoring channel ({@code monitoring.channel_id}): lag warnings, crash notices, the daily report and a status
 * message that is edited regularly.
 * <p>
 * The channel is looked up for every message, so it can be changed by a config reload. Everything runs on the bot's
 * executor; values of the game are read on the server thread.
 */
final class MonitoringChannel {

    /** Seconds between two TPS measurements. */
    private static final int LAG_CHECK_SECONDS = 5;
    /** Format of {@code {date}} in the daily report. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** Number of players in {@code {top_players}}. */
    private static final int TOP_PLAYERS = 3;
    /** Discord's maximum length of an embed description. */
    private static final int MAX_DESCRIPTION = MessageEmbed.DESCRIPTION_MAX_LENGTH;

    private final JDA jda;
    private final MinecraftServer server;
    private final StateStore state;
    private final ReportTracker report;
    private final ScheduledExecutorService executor;
    private final LagDetector lag = new LagDetector();
    private final StatusMessage statusMessage;
    /** The last channel ID reported as unavailable, so the error is logged only once. */
    private volatile long reportedChannel;

    /**
     * Creates the monitoring channel; call {@link #start()} to begin.
     *
     * @param jda       the bot
     * @param server    the running server
     * @param startTime when the server was started, for {@code {uptime}}
     * @param stats     the player statistics, for the placeholders
     * @param state     the stored state, for the ID of the status message
     * @param report    the numbers of the daily report
     * @param executor  the bot's executor
     */
    MonitoringChannel(JDA jda, MinecraftServer server, Instant startTime, PlayerStats stats, StateStore state,
                      ReportTracker report, ScheduledExecutorService executor) {
        this.jda = jda;
        this.server = server;
        this.state = state;
        this.report = report;
        this.executor = executor;
        this.statusMessage = new StatusMessage(server, startTime, stats, executor);
    }

    /**
     * Starts the lag checks, the daily report checks and the status message.
     */
    void start() {
        try {
            executor.scheduleWithFixedDelay(this::checkLag, LAG_CHECK_SECONDS, LAG_CHECK_SECONDS, TimeUnit.SECONDS);
            executor.scheduleWithFixedDelay(this::checkReport, 0, 1, TimeUnit.MINUTES);
        } catch (RejectedExecutionException ignored) {
            // The bot is shutting down
        }
        statusMessage.start();
    }

    /**
     * Updates the status message now, e.g. when the maintenance mode changes.
     */
    void updateStatusNow() {
        statusMessage.updateNow();
    }

    // ---- Lag ----

    /**
     * Measures the TPS and sends a warning or all-clear if needed.
     */
    private void checkLag() {
        ModConfig.Lag config = ConfigManager.get().monitoring().lag();
        if (!config.enabled()) {
            return;
        }
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        double tps = Math.min(server.tickRateManager().tickrate(), 1000.0 / Math.max(mspt, 0.001));
        lag.update(Instant.now(), tps, config.tpsThreshold(), Duration.ofSeconds(config.durationSeconds()),
                Duration.ofMinutes(config.cooldownMinutes())).ifPresent(event -> send(event.warning()
                ? new EmbedBuilder().setTitle("⚠️ Lag: " + tps(event.tps()) + " TPS")
                        .setDescription("The TPS are below " + tps(config.tpsThreshold()) + " for "
                                + duration(event.duration()) + ".")
                        .addField("Lowest", tps(event.lowest()) + " TPS", true)
                        .addField("MSPT", tps(mspt), true)
                        .setColor(EmbedColors.YELLOW).setTimestamp(Instant.now()).build()
                : new EmbedBuilder().setTitle("✅ Lag over: " + tps(event.tps()) + " TPS")
                        .setDescription("The lag lasted " + duration(event.duration()) + ".")
                        .addField("Lowest", tps(event.lowest()) + " TPS", true)
                        .setColor(EmbedColors.GREEN).setTimestamp(Instant.now()).build()));
    }

    /**
     * Formats the duration of a lag: seconds below a minute, otherwise days, hours and minutes.
     *
     * @param duration the duration
     * @return e.g. {@code 45s} or {@code 3m}
     */
    private static String duration(Duration duration) {
        return duration.toSeconds() < 60 ? duration.toSeconds() + "s" : Durations.formatCompact(duration);
    }

    /**
     * Formats a TPS or MSPT value with one decimal.
     *
     * @param value the value
     * @return e.g. {@code 12.3}
     */
    private static String tps(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    // ---- Crash ----

    /**
     * Sends the crash notice and waits for it, as the server stops right after.
     *
     * @param title     the title of the crash report
     * @param exception the exception that crashed the server
     * @param timeout   how long to wait at most
     */
    void sendCrash(String title, String exception, Duration timeout) {
        ModConfig.Crash config = ConfigManager.get().monitoring().crash();
        Optional<GuildMessageChannel> channel = channel();
        if (!config.enabled() || channel.isEmpty()) {
            return;
        }
        // Only the reason: the console lines are in the console channel and the crash report
        MessageEmbed embed = new EmbedBuilder().setTitle("💥 Server crashed")
                .setDescription(truncate("**" + DiscordText.escape(title) + "**\n" + DiscordText.escape(exception)))
                .setColor(EmbedColors.RED).setTimestamp(Instant.now())
                .build();
        try {
            channel.get().sendMessageEmbeds(SlashCommands.tag(List.of(embed)))
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .submit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Constants.LOG.error("Could not send the crash notice: {}", e.toString());
        }
    }

    // ---- Daily report ----

    /**
     * Sends the daily report if it is due.
     */
    private void checkReport() {
        ModConfig.DailyReport config = ConfigManager.get().monitoring().dailyReport();
        if (!config.enabled() || channel().isEmpty() || !report.isDue(config.time(), ZoneId.systemDefault())) {
            return;
        }
        try {
            Map<String, String> values = server.submit(this::finishReport).get(10, TimeUnit.SECONDS);
            send(new EmbedBuilder().setTitle("📅 Daily report · " + values.get("date"))
                    .setDescription(truncate(Placeholders.resolve(config.format(), values)))
                    .setColor(EmbedColors.BLURPLE).setTimestamp(Instant.now()).build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Constants.LOG.error("Could not send the daily report: {}", e.toString());
        }
    }

    /**
     * Ends the report period and collects the placeholder values. Must be called on the server thread.
     *
     * @return the values of the report placeholders
     */
    private Map<String, String> finishReport() {
        ReportTracker.Report numbers = report.finish(server.getPlayerList().getPlayers().stream()
                .map(ServerPlayer::getUUID).toList());
        Map<String, String> values = new HashMap<>();
        values.put("date", DATE.format(numbers.start().atZone(ZoneId.systemDefault())));
        values.put("unique_players", String.valueOf(numbers.players().size()));
        values.put("new_players", String.valueOf(numbers.newPlayers().size()));
        values.put("peak_players", String.valueOf(numbers.peak()));
        values.put("play_time", Durations.format(numbers.totalPlayTime()));
        String top = numbers.playSeconds().entrySet().stream().limit(TOP_PLAYERS)
                .map(entry -> DiscordText.escape(Leaderboards.playerName(server, entry.getKey())) + " ("
                        + Durations.format(Duration.ofSeconds(entry.getValue())) + ")")
                .collect(Collectors.joining(", "));
        values.put("top_players", top.isEmpty() ? "nobody" : top);
        return values;
    }

    // ---- Status message ----

    /**
     * Marks the status message as offline, when the server stops.
     *
     * @return completes when the message was edited or editing failed
     */
    CompletableFuture<?> markOffline() {
        Optional<GuildMessageChannel> channel = channel();
        OptionalLong messageId = state.statusMessage();
        if (!ConfigManager.get().monitoring().statusMessage().enabled() || channel.isEmpty()
                || messageId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        MessageEmbed embed = new EmbedBuilder().setTitle("🔴 Server offline")
                .setDescription("Stopped <t:" + Instant.now().getEpochSecond() + ":R>.")
                .setColor(EmbedColors.RED).setTimestamp(Instant.now()).build();
        return channel.get().editMessageEmbedsById(messageId.getAsLong(), SlashCommands.tag(List.of(embed)))
                .submit().exceptionally(error -> {
                    Constants.LOG.warn("Could not mark the status message as offline: {}", error.toString());
                    return null;
                });
    }

    /**
     * The status message, edited at {@code monitoring.status_message.interval_minutes}.
     */
    private final class StatusMessage extends PeriodicUpdater {

        StatusMessage(MinecraftServer server, Instant startTime, PlayerStats stats,
                      ScheduledExecutorService executor) {
            super(server, startTime, stats, executor);
        }

        @Override
        protected int intervalMinutes() {
            return ConfigManager.get().monitoring().statusMessage().intervalMinutes();
        }

        @Override
        protected void update(Map<String, String> placeholders) {
            ModConfig.StatusMessage config = ConfigManager.get().monitoring().statusMessage();
            Optional<GuildMessageChannel> channel = channel();
            if (!config.enabled() || channel.isEmpty()) {
                return;
            }
            boolean maintenance = MinecraftDiscordLink.maintenanceActive();
            MessageEmbed embed = new EmbedBuilder()
                    .setTitle(maintenance ? "🔧 Server in maintenance" : "🟢 Server online")
                    .setDescription(truncate(Placeholders.resolve(config.format(), placeholders)))
                    .setColor(maintenance ? EmbedColors.BLURPLE : EmbedColors.GREEN)
                    .setFooter("Last updated").setTimestamp(Instant.now())
                    .build();
            OptionalLong messageId = state.statusMessage();
            if (messageId.isEmpty()) {
                sendNew(channel.get(), embed);
                return;
            }
            channel.get().editMessageEmbedsById(messageId.getAsLong(), SlashCommands.tag(List.of(embed)))
                    .queue(null, error -> {
                if (error instanceof ErrorResponseException response
                        && (response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE
                        || response.getErrorResponse() == ErrorResponse.UNKNOWN_CHANNEL)) {
                    sendNew(channel.get(), embed);
                } else {
                    Constants.LOG.error("Could not update the status message: {}", error.getMessage());
                }
            });
        }

        /**
         * Sends a new status message and remembers it, e.g. when the old one was deleted.
         *
         * @param channel the monitoring channel
         * @param embed   the status
         */
        private void sendNew(GuildMessageChannel channel, MessageEmbed embed) {
            channel.sendMessageEmbeds(SlashCommands.tag(List.of(embed))).queue(
                    message -> state.setStatusMessage(OptionalLong.of(message.getIdLong())),
                    error -> Constants.LOG.error("Could not send the status message: {}", error.getMessage()));
        }
    }

    // ---- Helpers ----

    /**
     * Sends an embed to the monitoring channel.
     *
     * @param embed the embed
     */
    private void send(MessageEmbed embed) {
        channel().ifPresent(channel -> channel.sendMessageEmbeds(SlashCommands.tag(List.of(embed)))
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                .queue(null, error -> Constants.LOG.error("Could not send to the monitoring channel: {}",
                        error.getMessage())));
    }

    /**
     * Looks up the monitoring channel. A missing channel is logged once.
     *
     * @return the channel, empty if none is configured or it is not available
     */
    private Optional<GuildMessageChannel> channel() {
        OptionalLong channelId = ConfigManager.get().monitoring().channelId();
        if (channelId.isEmpty()) {
            return Optional.empty();
        }
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, channelId.getAsLong());
        if (channel == null && reportedChannel != channelId.getAsLong()) {
            reportedChannel = channelId.getAsLong();
            Constants.LOG.error("The monitoring channel {} was not found or the bot cannot see it.",
                    channelId.getAsLong());
        }
        return Optional.ofNullable(channel);
    }

    /**
     * Shortens a text to Discord's limit of an embed description.
     *
     * @param text the text
     * @return the text, at most {@link #MAX_DESCRIPTION} characters
     */
    private static String truncate(String text) {
        return text.length() <= MAX_DESCRIPTION ? text : text.substring(0, MAX_DESCRIPTION - 1) + "…";
    }
}
