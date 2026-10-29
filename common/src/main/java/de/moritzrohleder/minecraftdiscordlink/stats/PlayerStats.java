package de.moritzrohleder.minecraftdiscordlink.stats;

import de.moritzrohleder.minecraftdiscordlink.data.JsonFile;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Counts the distinct players for the placeholders {@code {unique_session}}, {@code {unique_today}} and
 * {@code {unique_total}}.
 * <p>
 * Players since the server start are only kept in memory. Today's and all players ever are stored in
 * {@code minecraftdiscordlink/stats.json} (see {@link JsonFile}) and written on every new player, so nothing is lost
 * on a crash. "Today" starts at midnight in the server's time zone; players online at midnight count for the new
 * day.
 * <p>
 * All methods are thread-safe.
 */
public final class PlayerStats {

    /** Name of the statistics file inside the data directory. */
    public static final String FILE_NAME = "stats.json";

    private final JsonFile<Data> file;
    private final Clock clock;

    private final Set<UUID> session = new HashSet<>();
    private final Set<UUID> today = new LinkedHashSet<>();
    private final Set<UUID> total = new LinkedHashSet<>();
    private LocalDate date;

    /**
     * Loads the statistics from the data directory.
     *
     * @param dataDirectory the mod's data directory, created if needed
     * @param clock         the clock defining "today", normally {@link Clock#systemDefaultZone()}
     */
    public PlayerStats(Path dataDirectory, Clock clock) {
        this.file = new JsonFile<>(dataDirectory.resolve(FILE_NAME), Data.class);
        this.clock = clock;
        this.date = LocalDate.now(clock);
        load();
    }

    /**
     * Records a player who joined the server.
     *
     * @param player the player's UUID
     * @param online the UUIDs of all players online now, counted for a new day if the day changed
     */
    public synchronized void recordJoin(UUID player, Collection<UUID> online) {
        boolean changed = rollOver(online);
        session.add(player);
        changed |= today.add(player);
        changed |= total.add(player);
        if (changed) {
            save();
        }
    }

    /**
     * Returns the number of distinct players since the server start.
     *
     * @return the number of players
     */
    public synchronized int sessionCount() {
        return session.size();
    }

    /**
     * Returns the number of distinct players today.
     *
     * @param online the UUIDs of all players online now, counted for a new day if the day changed
     * @return the number of players
     */
    public synchronized int todayCount(Collection<UUID> online) {
        if (rollOver(online)) {
            save();
        }
        return today.size();
    }

    /**
     * Returns the number of distinct players ever.
     *
     * @return the number of players
     */
    public synchronized int totalCount() {
        return total.size();
    }

    /**
     * Starts a new day if the date changed since the last call. The players online at that moment count for the new
     * day, as they are on the server today.
     *
     * @param online the UUIDs of all players online now
     * @return whether a new day was started
     */
    private boolean rollOver(Collection<UUID> online) {
        LocalDate now = LocalDate.now(clock);
        if (now.equals(date)) {
            return false;
        }
        date = now;
        today.clear();
        today.addAll(online);
        return true;
    }

    /**
     * Reads the statistics file. Today's players are only taken if the file is from today.
     */
    private void load() {
        Data data = file.read();
        if (data == null) {
            return;
        }
        if (data.total() != null) {
            total.addAll(data.total());
        }
        if (data.today() != null && date.toString().equals(data.today().date()) && data.today().players() != null) {
            today.addAll(data.today().players());
        }
    }

    /**
     * Writes the statistics file.
     */
    private void save() {
        file.write(new Data(List.copyOf(total), new Today(date.toString(), List.copyOf(today))));
    }

    /**
     * Content of the statistics file.
     *
     * @param total all players ever
     * @param today the players of one day
     */
    private record Data(List<UUID> total, Today today) {
    }

    /**
     * The players of one day.
     *
     * @param date    the day, ISO format ({@code 2026-10-28})
     * @param players the players of that day
     */
    private record Today(String date, List<UUID> players) {
    }
}
