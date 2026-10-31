package de.moritzrohleder.minecraftdiscordlink.monitoring;

import com.google.gson.annotations.SerializedName;
import de.moritzrohleder.minecraftdiscordlink.data.JsonFile;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Collects the numbers of the daily report: players, new players, peak and play time since the last report.
 * <p>
 * The numbers are stored in {@code minecraftdiscordlink/report.json} on every change, so a restart keeps them. Play
 * time is counted when a player leaves and, for players still online, when the report is made.
 * <p>
 * All methods are thread-safe.
 */
public final class ReportTracker {

    /** Name of the file inside the data directory. */
    public static final String FILE_NAME = "report.json";

    private final JsonFile<Data> file;
    private final Clock clock;

    private Instant periodStart;
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> newPlayers = new LinkedHashSet<>();
    private int peak;
    private final Map<UUID, Long> playSeconds = new HashMap<>();
    /** When the online players joined, or the period started for players online at its start. */
    private final Map<UUID, Instant> sessions = new HashMap<>();

    /**
     * Loads the numbers of the current period, or starts a new one.
     *
     * @param dataDirectory the mod's data directory
     * @param clock         the clock
     */
    public ReportTracker(Path dataDirectory, Clock clock) {
        this.file = new JsonFile<>(dataDirectory.resolve(FILE_NAME), Data.class);
        this.clock = clock;
        this.periodStart = now();
        load();
    }

    /**
     * Records a player who joined.
     *
     * @param player the player's UUID
     * @param isNew  whether the player joined for the first time
     * @param online the number of players online now, including the player
     */
    public synchronized void join(UUID player, boolean isNew, int online) {
        players.add(player);
        if (isNew) {
            newPlayers.add(player);
        }
        peak = Math.max(peak, online);
        sessions.put(player, now());
        save();
    }

    /**
     * Records a player who left, adding the session to the play time.
     *
     * @param player the player's UUID
     */
    public synchronized void leave(UUID player) {
        Instant start = sessions.remove(player);
        if (start != null) {
            playSeconds.merge(player, Math.max(0, Duration.between(start, now()).toSeconds()), Long::sum);
            save();
        }
    }

    /**
     * Checks whether a report is due: the report time passed since the period started.
     *
     * @param time the report time
     * @param zone the time zone of the report time
     * @return whether the report is due
     */
    public synchronized boolean isDue(LocalTime time, ZoneId zone) {
        ZonedDateTime start = periodStart.atZone(zone);
        ZonedDateTime next = start.with(time).truncatedTo(ChronoUnit.MINUTES);
        if (!next.isAfter(start)) {
            next = next.plusDays(1);
        }
        return !next.toInstant().isAfter(now());
    }

    /**
     * Ends the current period and starts a new one. Players online now count for the new period as well.
     *
     * @param online the players online now
     * @return the numbers of the ended period
     */
    public synchronized Report finish(Collection<UUID> online) {
        Instant now = now();
        sessions.forEach((player, start) ->
                playSeconds.merge(player, Math.max(0, Duration.between(start, now).toSeconds()), Long::sum));
        Map<UUID, Long> play = new LinkedHashMap<>();
        playSeconds.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .forEach(entry -> play.put(entry.getKey(), entry.getValue()));
        Report report = new Report(periodStart, now, List.copyOf(players), List.copyOf(newPlayers), peak, play);

        periodStart = now;
        players.clear();
        newPlayers.clear();
        playSeconds.clear();
        sessions.clear();
        players.addAll(online);
        online.forEach(player -> sessions.put(player, now));
        peak = online.size();
        save();
        return report;
    }

    /**
     * Returns the current time, to the second.
     *
     * @return the current time
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Reads the stored period. Sessions are not stored: after a restart nobody is online.
     */
    private void load() {
        Data data = file.read();
        if (data == null) {
            save();
            return;
        }
        try {
            periodStart = Instant.parse(data.periodStart());
        } catch (DateTimeParseException | NullPointerException e) {
            periodStart = now();
        }
        if (data.players() != null) {
            players.addAll(data.players());
        }
        if (data.newPlayers() != null) {
            newPlayers.addAll(data.newPlayers());
        }
        peak = data.peak();
        if (data.playSeconds() != null) {
            data.playSeconds().forEach((player, seconds) -> {
                try {
                    playSeconds.put(UUID.fromString(player), seconds);
                } catch (IllegalArgumentException ignored) {
                    // An invalid entry is skipped
                }
            });
        }
    }

    /**
     * Writes the current period.
     */
    private void save() {
        Map<String, Long> seconds = new LinkedHashMap<>();
        playSeconds.forEach((player, value) -> seconds.put(player.toString(), value));
        file.write(new Data(periodStart.toString(), List.copyOf(players), List.copyOf(newPlayers), peak, seconds));
    }

    /**
     * The numbers of one report period.
     *
     * @param start       when the period started
     * @param end         when the period ended
     * @param players     the distinct players
     * @param newPlayers  the players who joined for the first time
     * @param peak        the most players online at the same time
     * @param playSeconds the play time of every player in seconds, most first
     */
    public record Report(Instant start, Instant end, List<UUID> players, List<UUID> newPlayers, int peak,
                         Map<UUID, Long> playSeconds) {

        /**
         * Returns the play time of all players together.
         *
         * @return the total play time
         */
        public Duration totalPlayTime() {
            return Duration.ofSeconds(playSeconds.values().stream().mapToLong(Long::longValue).sum());
        }
    }

    /**
     * Content of the report file.
     *
     * @param periodStart when the period started, ISO-8601
     * @param players     the distinct players
     * @param newPlayers  the new players
     * @param peak        the peak
     * @param playSeconds play time in seconds by UUID
     */
    private record Data(@SerializedName("period_start") String periodStart, List<UUID> players,
                        @SerializedName("new_players") List<UUID> newPlayers, int peak,
                        @SerializedName("play_seconds") Map<String, Long> playSeconds) {
    }
}
