package de.moritzrohleder.minecraftdiscordlink.text;

import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provides the values of the server placeholders used in the bot status and the channel topic.
 */
public final class ServerPlaceholders {

    /** Format of {@code {last_updated}}, in the server's time zone. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ServerPlaceholders() {
    }

    /**
     * Collects the current placeholder values. Must be called on the server thread, as it reads the player list.
     *
     * @param server    the running server
     * @param startTime when the server was started, for {@code {uptime}}
     * @param stats     the player statistics, for the unique player placeholders
     * @return the values by placeholder name
     */
    public static Map<String, String> values(MinecraftServer server, Instant startTime, PlayerStats stats) {
        List<UUID> online = server.getPlayerList().getPlayers().stream().map(ServerPlayer::getUUID).toList();
        Map<String, String> values = new HashMap<>();
        values.put("online", String.valueOf(online.size()));
        values.put("max", String.valueOf(server.getMaxPlayers()));
        values.put("unique_session", String.valueOf(stats.sessionCount()));
        values.put("unique_today", String.valueOf(stats.todayCount(online)));
        values.put("unique_total", String.valueOf(stats.totalCount()));
        values.put("uptime", Durations.format(Duration.between(startTime, Instant.now())));
        values.put("last_updated", LocalTime.now().format(TIME));
        values.put("version", server.getServerVersion());
        values.put("motd", server.getMotd());
        return values;
    }
}
