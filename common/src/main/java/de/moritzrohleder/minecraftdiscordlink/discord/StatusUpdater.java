package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.Placeholders;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Periodically updates the bot's activity ("status") from {@code [discord.status]}. A disabled status removes the
 * activity.
 */
final class StatusUpdater extends PeriodicUpdater {

    /** Discord's maximum length of an activity text. */
    private static final int MAX_LENGTH = 128;

    private final JDA jda;

    /**
     * Creates a status updater; call {@link #start()} to begin.
     *
     * @param jda       the connected bot
     * @param server    the running server, for the placeholders
     * @param startTime when the server was started, for {@code {uptime}}
     * @param stats     the player statistics, for the unique player placeholders
     * @param executor  executor running the updates
     */
    StatusUpdater(JDA jda, MinecraftServer server, Instant startTime, PlayerStats stats,
                  ScheduledExecutorService executor) {
        super(server, startTime, stats, executor);
        this.jda = jda;
    }

    @Override
    protected int intervalMinutes() {
        return ConfigManager.get().discord().status().intervalMinutes();
    }

    @Override
    protected void update(Map<String, String> placeholders) {
        ModConfig.Status config = ConfigManager.get().discord().status();
        jda.getPresence().setActivity(config.enabled() ? activity(config, placeholders) : null);
    }

    /**
     * Creates the activity from the configured type and format.
     *
     * @param config       the status settings
     * @param placeholders the current placeholder values
     * @return the activity, or {@code null} if the resolved text is blank
     */
    private static Activity activity(ModConfig.Status config, Map<String, String> placeholders) {
        String text = Placeholders.resolve(MinecraftDiscordLink.maintenanceActive() ? config.maintenanceFormat()
                : config.format(), placeholders).strip();
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
