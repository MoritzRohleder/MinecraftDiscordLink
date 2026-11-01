package de.moritzrohleder.minecraftdiscordlink.moderation;

import com.google.gson.annotations.SerializedName;
import de.moritzrohleder.minecraftdiscordlink.data.JsonFile;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The mutes of players, stored in {@code minecraftdiscordlink/mutes.json} on every change. Minecraft itself has no
 * mutes. A mute always ends; expired mutes are removed by {@link #removeExpired(Instant)}.
 * <p>
 * All methods are thread-safe.
 */
public final class MuteStore {

    /** Name of the file inside the data directory. */
    public static final String FILE_NAME = "mutes.json";

    private final JsonFile<Data> file;
    private final Map<UUID, Mute> mutes = new LinkedHashMap<>();

    /**
     * Loads the mutes.
     *
     * @param dataDirectory the mod's data directory
     */
    public MuteStore(Path dataDirectory) {
        this.file = new JsonFile<>(dataDirectory.resolve(FILE_NAME), Data.class);
        load();
    }

    /**
     * Mutes a player, replacing an existing mute.
     *
     * @param mute the mute
     * @return whether an existing mute was replaced
     */
    public synchronized boolean put(Mute mute) {
        boolean replaced = mutes.put(mute.player(), mute) != null;
        save();
        return replaced;
    }

    /**
     * Lifts the mute of a player.
     *
     * @param player the player's UUID
     * @return the lifted mute, empty if the player was not muted
     */
    public synchronized Optional<Mute> remove(UUID player) {
        Mute removed = mutes.remove(player);
        if (removed != null) {
            save();
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Returns the mute of a player, if it has not ended yet.
     *
     * @param player the player's UUID
     * @param now    the current time
     * @return the mute, empty if the player is not muted
     */
    public synchronized Optional<Mute> get(UUID player, Instant now) {
        return Optional.ofNullable(mutes.get(player)).filter(mute -> mute.until().isAfter(now));
    }

    /**
     * Lists the mutes, sorted by name.
     *
     * @return the mutes
     */
    public synchronized List<Mute> all() {
        return mutes.values().stream()
                .sorted(Comparator.comparing(Mute::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Removes the mutes that have ended.
     *
     * @param now the current time
     * @return the removed mutes
     */
    public synchronized List<Mute> removeExpired(Instant now) {
        List<Mute> expired = mutes.values().stream().filter(mute -> !mute.until().isAfter(now)).toList();
        if (!expired.isEmpty()) {
            expired.forEach(mute -> mutes.remove(mute.player()));
            save();
        }
        return expired;
    }

    /**
     * Reads the file; invalid entries are skipped.
     */
    private void load() {
        Data data = file.read();
        if (data == null || data.mutes() == null) {
            return;
        }
        for (Entry entry : data.mutes()) {
            try {
                Mute mute = new Mute(UUID.fromString(entry.uuid()), entry.name(), Instant.parse(entry.until()),
                        entry.reason(), entry.moderator(), ModerationEntry.Network.valueOf(
                        Optional.ofNullable(entry.network()).orElse("local").toUpperCase(Locale.ROOT)));
                mutes.put(mute.player(), mute);
            } catch (IllegalArgumentException | NullPointerException | DateTimeParseException ignored) {
                // An invalid entry is skipped
            }
        }
    }

    /**
     * Writes the file.
     */
    private void save() {
        List<Entry> entries = new ArrayList<>();
        mutes.values().forEach(mute -> entries.add(new Entry(mute.player().toString(), mute.name(),
                mute.until().toString(), mute.reason(), mute.moderator(),
                mute.network().name().toLowerCase(Locale.ROOT))));
        file.write(new Data(entries));
    }

    /**
     * A mute.
     *
     * @param player    the player's UUID
     * @param name      the player's name
     * @param until     when the mute ends
     * @param reason    the reason
     * @param moderator who muted the player
     * @param network   whether the mute applies to all servers, made here or received
     */
    public record Mute(UUID player, String name, Instant until, String reason, String moderator,
                       ModerationEntry.Network network) {
    }

    /**
     * Content of the file.
     *
     * @param mutes the mutes
     */
    private record Data(List<Entry> mutes) {
    }

    /**
     * A mute in the file.
     *
     * @param uuid      the player's UUID
     * @param name      the player's name
     * @param until     the end, ISO-8601
     * @param reason    the reason
     * @param moderator who muted the player
     * @param network   {@code local}, {@code all_servers} or {@code received}
     */
    private record Entry(String uuid, String name, String until, String reason, String moderator,
                         @SerializedName("network") String network) {
    }
}
