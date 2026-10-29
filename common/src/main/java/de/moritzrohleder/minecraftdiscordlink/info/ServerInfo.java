package de.moritzrohleder.minecraftdiscordlink.info;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Information about the running server for the commands {@code online}, {@code status} and {@code playtime}.
 * <p>
 * All methods must be called on the server thread, as they read the player list and the statistics.
 */
public final class ServerInfo {

    /** Ticks per second of the play time statistic. */
    private static final int TICKS_PER_SECOND = 20;

    private ServerInfo() {
    }

    /**
     * Collects the online players.
     *
     * @param server the running server
     * @return the online players and the player limit
     */
    public static Online online(MinecraftServer server) {
        List<String> names = server.getPlayerList().getPlayers().stream()
                .map(player -> player.getGameProfile().getName())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new Online(names, server.getMaxPlayers());
    }

    /**
     * Collects the server status.
     *
     * @param server    the running server
     * @param startTime when the server was started
     * @return the status
     */
    public static Status status(MinecraftServer server, Instant startTime) {
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        double tps = Math.min(server.tickRateManager().tickrate(), 1000.0 / Math.max(mspt, 0.001));
        return new Status(Durations.format(Duration.between(startTime, Instant.now())), tps, mspt,
                server.getPlayerCount(), server.getMaxPlayers(), server.getServerVersion());
    }

    /**
     * Reads the play time of a player from the vanilla statistics. For online players the live value is used,
     * otherwise the player's statistics file of the world.
     *
     * @param server  the running server
     * @param profile the player
     * @return the play time, empty if the player has never played on this world
     */
    public static Optional<Duration> playtime(MinecraftServer server, GameProfile profile) {
        ServerPlayer online = server.getPlayerList().getPlayer(profile.getId());
        ServerStatsCounter stats;
        if (online != null) {
            stats = online.getStats();
        } else {
            File file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(profile.getId() + ".json")
                    .toFile();
            if (!file.isFile()) {
                return Optional.empty();
            }
            stats = new ServerStatsCounter(server, file);
        }
        int ticks = stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        return Optional.of(Duration.ofSeconds(ticks / TICKS_PER_SECOND));
    }

    /**
     * Checks whether a player has ever played on this world: online now or with a statistics file.
     *
     * @param server the running server
     * @param player the player's UUID
     * @return whether the player has played here
     */
    public static boolean hasPlayed(MinecraftServer server, UUID player) {
        return server.getPlayerList().getPlayer(player) != null
                || server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(player + ".json").toFile().isFile();
    }

    /**
     * The online players.
     *
     * @param names the names of the online players, sorted
     * @param max   the player limit
     */
    public record Online(List<String> names, int max) {
    }

    /**
     * The server status.
     *
     * @param uptime  the formatted time since server start
     * @param tps     ticks per second, at most the target tick rate
     * @param mspt    average milliseconds per tick
     * @param players the number of online players
     * @param max     the player limit
     * @param version the Minecraft version
     */
    public record Status(String uptime, double tps, double mspt, int players, int max, String version) {

        /**
         * Formats the ticks per second with one decimal.
         *
         * @return e.g. {@code 20.0}
         */
        public String formattedTps() {
            return String.format(Locale.ROOT, "%.1f", tps);
        }

        /**
         * Formats the milliseconds per tick with one decimal.
         *
         * @return e.g. {@code 3.2}
         */
        public String formattedMspt() {
            return String.format(Locale.ROOT, "%.1f", mspt);
        }
    }
}
