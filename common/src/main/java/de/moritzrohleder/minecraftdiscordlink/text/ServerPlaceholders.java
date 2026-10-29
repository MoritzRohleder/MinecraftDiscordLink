package de.moritzrohleder.minecraftdiscordlink.text;

import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Provides the values of the server placeholders used in the bot status and the channel topic.
 * <p>
 * The unique player placeholders ({@code {unique_session}}, {@code {unique_today}}, {@code {unique_total}}) are
 * empty until the player statistics are implemented.
 */
public final class ServerPlaceholders {

    /** Format of {@code {last_updated}}, in the server's time zone. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private ServerPlaceholders() {
    }

    /**
     * Collects the current placeholder values.
     * <p>
     * May be called from any thread. The player count is read without synchronizing with the server thread, so it
     * may be off by one while a player joins or leaves, which is fine for a status text.
     *
     * @param server    the running server
     * @param startTime when the server was started, for {@code {uptime}}
     * @return the values by placeholder name
     */
    public static Map<String, String> values(MinecraftServer server, Instant startTime) {
        Map<String, String> values = new HashMap<>();
        values.put("online", String.valueOf(server.getPlayerCount()));
        values.put("max", String.valueOf(server.getMaxPlayers()));
        values.put("unique_session", "");
        values.put("unique_today", "");
        values.put("unique_total", "");
        values.put("uptime", Durations.format(Duration.between(startTime, Instant.now())));
        values.put("last_updated", LocalTime.now().format(TIME));
        values.put("version", server.getServerVersion());
        values.put("motd", server.getMotd());
        return values;
    }
}
