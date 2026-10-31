package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.Placeholders;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Periodically updates the topic (description) of the chat channel from {@code [chat.topic]}.
 * <p>
 * Discord allows about two topic changes per ten minutes per channel, hence the minimum interval of five minutes.
 * Changing the topic requires the "Manage Channels" permission; a missing permission is logged once until an update
 * succeeds again.
 */
final class TopicUpdater extends PeriodicUpdater {

    /** Discord's maximum length of a channel topic. */
    private static final int MAX_LENGTH = 1024;

    /** The chat channel; changed when a reload changes the chat channel. */
    private volatile StandardGuildMessageChannel channel;
    /** Whether the missing permission has already been logged. */
    private boolean permissionErrorLogged;

    /**
     * Creates a topic updater; call {@link #start()} to begin.
     *
     * @param channel   the chat channel
     * @param server    the running server, for the placeholders
     * @param startTime when the server was started, for {@code {uptime}}
     * @param stats     the player statistics, for the unique player placeholders
     * @param executor  executor running the updates
     */
    TopicUpdater(StandardGuildMessageChannel channel, MinecraftServer server, Instant startTime, PlayerStats stats,
                 ScheduledExecutorService executor) {
        super(server, startTime, stats, executor);
        this.channel = channel;
    }

    /**
     * Uses another chat channel, after a reload changed it.
     *
     * @param channel the new chat channel
     */
    void setChannel(StandardGuildMessageChannel channel) {
        this.channel = channel;
    }

    @Override
    protected int intervalMinutes() {
        return ConfigManager.get().chat().topic().intervalMinutes();
    }

    @Override
    protected void update(Map<String, String> placeholders) {
        ModConfig.Topic config = ConfigManager.get().chat().topic();
        if (!config.enabled() || !ConfigManager.get().chat().enabled()) {
            return;
        }
        String topic = Placeholders.resolve(MinecraftDiscordLink.maintenanceActive() ? config.maintenanceFormat()
                : config.format(), placeholders).strip();
        if (topic.length() > MAX_LENGTH) {
            topic = topic.substring(0, MAX_LENGTH);
        }
        try {
            channel.getManager().setTopic(topic).queue(
                    success -> permissionErrorLogged = false,
                    error -> Constants.LOG.error("Could not update the chat channel topic: {}", error.getMessage()));
        } catch (InsufficientPermissionException e) {
            if (!permissionErrorLogged) {
                Constants.LOG.error("Could not update the chat channel topic: the bot needs the 'Manage Channels' "
                        + "permission in the chat channel.");
                permissionErrorLogged = true;
            }
        }
    }
}
