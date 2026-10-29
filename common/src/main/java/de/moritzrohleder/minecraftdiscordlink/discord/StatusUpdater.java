package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.text.Placeholders;
import de.moritzrohleder.minecraftdiscordlink.text.ServerPlaceholders;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically updates the bot's activity ("status") from {@code [discord.status]}.
 * <p>
 * The config is read again before every update, so changes from a reload apply from the next update on, including
 * the interval. A disabled status removes the activity.
 */
final class StatusUpdater {

    /** Discord's maximum length of an activity text. */
    private static final int MAX_LENGTH = 128;

    private final JDA jda;
    private final MinecraftServer server;
    private final Instant startTime;
    private final ScheduledExecutorService executor;

    /**
     * Creates a status updater; call {@link #start()} to begin.
     *
     * @param jda       the connected bot
     * @param server    the running server, for the placeholders
     * @param startTime when the server was started, for {@code {uptime}}
     * @param executor  executor running the updates
     */
    StatusUpdater(JDA jda, MinecraftServer server, Instant startTime, ScheduledExecutorService executor) {
        this.jda = jda;
        this.server = server;
        this.startTime = startTime;
        this.executor = executor;
    }

    /**
     * Updates the status now and schedules the following updates.
     */
    void start() {
        update();
    }

    /**
     * Updates the status and schedules the next update. Stops silently once the executor is shut down.
     */
    private void update() {
        ModConfig.Status config = ConfigManager.get().discord().status();
        try {
            jda.getPresence().setActivity(config.enabled() ? activity(config) : null);
        } catch (RuntimeException e) {
            Constants.LOG.error("Could not update the bot status: {}", e.getMessage());
        }

        try {
            executor.schedule(this::update, config.intervalMinutes(), TimeUnit.MINUTES);
        } catch (RejectedExecutionException ignored) {
            // The bot is shutting down
        }
    }

    /**
     * Creates the activity from the configured type and format.
     *
     * @param config the status settings
     * @return the activity, or {@code null} if the resolved text is blank
     */
    private Activity activity(ModConfig.Status config) {
        String text = Placeholders.resolve(config.format(), ServerPlaceholders.values(server, startTime)).strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        return switch (config.type()) {
            case PLAYING -> Activity.playing(text);
            case WATCHING -> Activity.watching(text);
            case LISTENING -> Activity.listening(text);
            case COMPETING -> Activity.competing(text);
            case CUSTOM -> Activity.customStatus(text);
        };
    }
}
