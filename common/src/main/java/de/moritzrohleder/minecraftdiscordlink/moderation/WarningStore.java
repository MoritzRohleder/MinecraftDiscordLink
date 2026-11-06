package de.moritzrohleder.minecraftdiscordlink.moderation;

import com.google.gson.annotations.SerializedName;
import de.moritzrohleder.minecraftdiscordlink.data.JsonFile;

import java.nio.file.Path;
import java.time.Duration;
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
 * The warnings of players, stored in {@code minecraftdiscordlink/warnings.json} on every change. A warning has the
 * number of its entry in the moderation history. Lifted and expired warnings are kept, so they can be reactivated.
 * <p>
 * All methods are thread-safe.
 */
public final class WarningStore {

    /** Name of the file inside the data directory. */
    public static final String FILE_NAME = "warnings.json";

    private final JsonFile<Data> file;
    private final Map<Long, Warning> warnings = new LinkedHashMap<>();

    /**
     * Loads the warnings.
     *
     * @param dataDirectory the mod's data directory
     */
    public WarningStore(Path dataDirectory) {
        this.file = new JsonFile<>(dataDirectory.resolve(FILE_NAME), Data.class);
        load();
    }

    /**
     * Stores a new warning.
     *
     * @param warning the warning
     */
    public synchronized void add(Warning warning) {
        warnings.put(warning.id(), warning);
        save();
    }

    /**
     * Returns a warning.
     *
     * @param id the number of the warning
     * @return the warning, empty if there is none with that number
     */
    public synchronized Optional<Warning> get(long id) {
        return Optional.ofNullable(warnings.get(id));
    }

    /**
     * Finds a warning received from another server.
     *
     * @param origin   the name of the server the warning was made on
     * @param originId the number of the warning there
     * @return the warning, empty if it was not received
     */
    public synchronized Optional<Warning> received(String origin, long originId) {
        return warnings.values().stream()
                .filter(warning -> warning.origin().map(origin::equalsIgnoreCase).orElse(false)
                        && warning.originId() == originId)
                .findFirst();
    }

    /**
     * Lists the warnings of a player, newest first.
     *
     * @param player the player's UUID
     * @return the warnings
     */
    public synchronized List<Warning> of(UUID player) {
        return warnings.values().stream().filter(warning -> warning.player().equals(player))
                .sorted(Comparator.comparingLong(Warning::id).reversed()).toList();
    }

    /**
     * Replaces a warning, e.g. after it was lifted.
     *
     * @param warning the changed warning
     */
    public synchronized void replace(Warning warning) {
        warnings.put(warning.id(), warning);
        save();
    }

    /**
     * Reads the file; invalid entries are skipped.
     */
    private void load() {
        Data data = file.read();
        if (data == null || data.warnings() == null) {
            return;
        }
        for (Entry entry : data.warnings()) {
            try {
                Warning warning = new Warning(entry.id(), UUID.fromString(entry.uuid()), entry.name(),
                        Instant.parse(entry.time()), entry.moderator(), entry.reason(),
                        ModerationEntry.Network.valueOf(entry.network().toUpperCase(Locale.ROOT)), entry.lifted(),
                        Optional.ofNullable(entry.reactivated()).map(Instant::parse),
                        Optional.ofNullable(entry.origin()), entry.originId());
                warnings.put(warning.id(), warning);
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
        warnings.values().forEach(warning -> entries.add(new Entry(warning.id(), warning.player().toString(),
                warning.name(), warning.time().toString(), warning.moderator(), warning.reason(),
                warning.network().name().toLowerCase(Locale.ROOT), warning.lifted(),
                warning.reactivated().map(Instant::toString).orElse(null), warning.origin().orElse(null),
                warning.originId())));
        file.write(new Data(entries));
    }

    /**
     * The state of a warning.
     */
    public enum Status {
        /** Counts for the rules. */
        ACTIVE,
        /** Older than {@code moderation.warning_expire_after}. */
        EXPIRED,
        /** Lifted by a moderator. */
        LIFTED
    }

    /**
     * A warning.
     *
     * @param id          the number of its entry in the moderation history
     * @param player      the player's UUID
     * @param name        the player's name
     * @param time        when it was given
     * @param moderator   who gave it
     * @param reason      the reason
     * @param network     whether it applies to all servers, made here or received
     * @param lifted      whether a moderator lifted it
     * @param reactivated when it was reactivated; it counts from then on
     * @param origin      the server a received warning was made on
     * @param originId    the number of a received warning on its server, 0 otherwise
     */
    public record Warning(long id, UUID player, String name, Instant time, String moderator, String reason,
                          ModerationEntry.Network network, boolean lifted, Optional<Instant> reactivated,
                          Optional<String> origin, long originId) {

        /**
         * Returns the time the warning counts from: when it was given or reactivated.
         *
         * @return the time
         */
        public Instant countsFrom() {
            return reactivated.orElse(time);
        }

        /**
         * Determines the state of the warning.
         *
         * @param now         the current time
         * @param expireAfter how long warnings count, empty if forever
         * @return the state
         */
        public Status status(Instant now, Optional<Duration> expireAfter) {
            if (lifted) {
                return Status.LIFTED;
            }
            return expireAfter.filter(duration -> !countsFrom().plus(duration).isAfter(now)).isPresent()
                    ? Status.EXPIRED : Status.ACTIVE;
        }

        /**
         * Returns this warning lifted.
         *
         * @return the lifted warning
         */
        public Warning asLifted() {
            return new Warning(id, player, name, time, moderator, reason, network, true, reactivated, origin,
                    originId);
        }

        /**
         * Returns this warning reactivated: it is no longer lifted and counts from the given time.
         *
         * @param now the time of the reactivation
         * @return the reactivated warning
         */
        public Warning reactivatedAt(Instant now) {
            return new Warning(id, player, name, time, moderator, reason, network, false, Optional.of(now), origin,
                    originId);
        }

        /**
         * Returns this warning with another reason.
         *
         * @param newReason the reason
         * @return the changed warning
         */
        public Warning withReason(String newReason) {
            return new Warning(id, player, name, time, moderator, newReason, network, lifted, reactivated, origin,
                    originId);
        }
    }

    /**
     * Content of the file.
     *
     * @param warnings the warnings
     */
    private record Data(List<Entry> warnings) {
    }

    /**
     * A warning in the file.
     *
     * @param id          the number
     * @param uuid        the player's UUID
     * @param name        the player's name
     * @param time        when it was given, ISO-8601
     * @param moderator   who gave it
     * @param reason      the reason
     * @param network     {@code local}, {@code all_servers} or {@code received}
     * @param lifted      whether it was lifted
     * @param reactivated when it was reactivated, ISO-8601, {@code null} if never
     * @param origin      the server a received warning was made on, {@code null} otherwise
     * @param originId    the number of a received warning on its server, 0 otherwise
     */
    private record Entry(long id, String uuid, String name, String time, String moderator, String reason,
                         String network, boolean lifted, String reactivated, String origin,
                         @SerializedName("origin_id") long originId) {
    }
}
