package de.moritzrohleder.minecraftdiscordlink.stats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import de.moritzrohleder.minecraftdiscordlink.Constants;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * {@code minecraftdiscordlink/stats.json} and written on every new player, so nothing is lost on a crash. "Today"
 * starts at midnight in the server's time zone; players online at midnight count for the new day.
 * <p>
 * If the file exists but cannot be read, an error is logged and the file is never overwritten, so the stored players
 * are not lost; the counts start from zero until the file is fixed.
 * <p>
 * All methods are thread-safe.
 */
public final class PlayerStats {

    /** Name of the statistics file inside the data directory. */
    public static final String FILE_NAME = "stats.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Clock clock;
    /** Whether the file may be written; {@code false} if it exists but could not be read. */
    private final boolean writable;

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
        this.file = dataDirectory.resolve(FILE_NAME);
        this.clock = clock;
        this.date = LocalDate.now(clock);
        this.writable = load();
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
     * Reads the statistics file. A missing file is fine; a broken one is logged.
     *
     * @return whether the file may be written
     */
    private boolean load() {
        if (!Files.exists(file)) {
            return true;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data == null) {
                return true;
            }
            if (data.total() != null) {
                total.addAll(data.total());
            }
            if (data.today() != null && date.toString().equals(data.today().date()) && data.today().players() != null) {
                today.addAll(data.today().players());
            }
            return true;
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            Constants.LOG.error("Could not read {}: {}. The player statistics start from zero and the file is not "
                    + "changed until it is fixed or removed.", file, e.getMessage());
            return false;
        }
    }

    /**
     * Writes the statistics file atomically, so a crash never leaves a half-written file. Errors are logged.
     */
    private void save() {
        if (!writable) {
            return;
        }
        Data data = new Data(List.copyOf(total), new Today(date.toString(), List.copyOf(today)));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Constants.LOG.error("Could not write {}: {}", file, e.getMessage());
        }
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
