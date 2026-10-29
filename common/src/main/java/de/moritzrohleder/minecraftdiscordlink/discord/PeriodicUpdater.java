package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.ServerPlaceholders;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Base of the updates that show server information in Discord at a configurable interval, such as the bot status and
 * the channel topic.
 * <p>
 * Each update collects the placeholder values on the server thread, then {@link #update(Map)} sends them to Discord.
 * The interval is read again before every update, so a config reload applies from the next update on.
 */
abstract class PeriodicUpdater {

    /** Maximum time to wait for the server thread to collect the placeholder values. */
    private static final long PLACEHOLDER_TIMEOUT_SECONDS = 10;

    private final MinecraftServer server;
    private final Instant startTime;
    private final PlayerStats stats;
    private final ScheduledExecutorService executor;

    /**
     * Creates the updater; call {@link #start()} to begin.
     *
     * @param server    the running server
     * @param startTime when the server was started, for {@code {uptime}}
     * @param stats     the player statistics, for the unique player placeholders
     * @param executor  executor running the updates
     */
    PeriodicUpdater(MinecraftServer server, Instant startTime, PlayerStats stats, ScheduledExecutorService executor) {
        this.server = server;
        this.startTime = startTime;
        this.stats = stats;
        this.executor = executor;
    }

    /**
     * Updates as soon as possible and schedules the following updates. The update always runs on the executor, never
     * on the calling thread: when called on the server thread, waiting there for the placeholder values would block.
     */
    final void start() {
        try {
            executor.execute(this::run);
        } catch (RejectedExecutionException ignored) {
            // The bot is shutting down
        }
    }

    /**
     * Returns the current interval between two updates.
     *
     * @return the interval in minutes
     */
    protected abstract int intervalMinutes();

    /**
     * Sends the update to Discord.
     *
     * @param placeholders the current placeholder values
     */
    protected abstract void update(Map<String, String> placeholders);

    /**
     * Runs one update and schedules the next one. Stops silently once the executor is shut down.
     */
    private void run() {
        try {
            update(server.submit(() -> ServerPlaceholders.values(server, startTime, stats))
                    .get(PLACEHOLDER_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            Constants.LOG.error("Could not update {}: {}", getClass().getSimpleName(), e.toString());
        }

        try {
            executor.schedule(this::run, intervalMinutes(), TimeUnit.MINUTES);
        } catch (RejectedExecutionException ignored) {
            // The bot is shutting down
        }
    }
}
