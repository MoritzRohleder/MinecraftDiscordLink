package de.moritzrohleder.minecraftdiscordlink.moderation;

import com.google.gson.annotations.SerializedName;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.data.JsonFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every moderation action with its number, stored in {@code minecraftdiscordlink/moderation-history.json}. The
 * numbers start at 1 and are never reused. When the file does not exist yet, the entries of an existing
 * {@code moderation.log} are taken over once, without UUIDs; they are found by the player's name.
 * <p>
 * All methods are thread-safe.
 */
public final class ModerationHistory {

    /** Name of the file inside the data directory. */
    public static final String FILE_NAME = "moderation-history.json";

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LOG_UNTIL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** A target followed by its linked Discord account, e.g. {@code Steve (@steve)} or {@code Steve (123)}. */
    private static final Pattern LINKED_TARGET = Pattern.compile("(.+) \\((@[^)]+|\\d+)\\)");
    /** The duration column of a temporary ban or mute, e.g. {@code 7d (until 2026-11-06 15:40)}. */
    private static final Pattern UNTIL = Pattern.compile(".+ \\(until (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})\\)");

    private final JsonFile<Data> file;
    private final Map<Long, ModerationEntry> entries = new LinkedHashMap<>();
    private long nextId = 1;

    /**
     * Loads the history, taking over the moderation log file if there is no history yet.
     *
     * @param dataDirectory the mod's data directory
     * @param zone          the time zone of the times in the moderation log file
     */
    public ModerationHistory(Path dataDirectory, ZoneId zone) {
        Path path = dataDirectory.resolve(FILE_NAME);
        this.file = new JsonFile<>(path, Data.class);
        if (Files.exists(path)) {
            load();
        } else {
            importLog(dataDirectory.resolve(ModerationLog.FILE_NAME), zone);
        }
    }

    /**
     * Adds an entry with the next number.
     *
     * @param entry the entry, without number
     * @return the numbered entry
     */
    public synchronized ModerationEntry add(ModerationEntry entry) {
        ModerationEntry numbered = entry.withId(nextId++);
        entries.put(numbered.id(), numbered);
        save();
        return numbered;
    }

    /**
     * Replaces an entry, e.g. with a changed reason.
     *
     * @param entry the changed entry with the number of the old one
     * @return whether an entry with that number existed
     */
    public synchronized boolean replace(ModerationEntry entry) {
        if (!entries.containsKey(entry.id())) {
            return false;
        }
        entries.put(entry.id(), entry);
        save();
        return true;
    }

    /**
     * Returns the entry with a number.
     *
     * @param id the number
     * @return the entry, empty if there is none
     */
    public synchronized Optional<ModerationEntry> get(long id) {
        return Optional.ofNullable(entries.get(id));
    }

    /**
     * Lists the entries about a player, newest first: entries with the player's UUID, and taken-over entries
     * without UUID whose target is the player's name.
     *
     * @param player the player's UUID
     * @param name   the player's current name
     * @return the entries
     */
    public synchronized List<ModerationEntry> of(UUID player, String name) {
        return entries.values().stream()
                .filter(entry -> entry.player().map(player::equals)
                        .orElseGet(() -> entry.action().hasTarget() && entry.target().equalsIgnoreCase(name)))
                .sorted(Comparator.comparingLong(ModerationEntry::id).reversed())
                .toList();
    }

    /**
     * Parses a line of the moderation log file, see {@link ModerationEntry#toLogLine(ZoneId)}. A number in the
     * line is ignored. Actions on all servers become {@link ModerationEntry.Network#ALL_SERVERS}; the linked Discord
     * account is left out, as the line only has its name.
     *
     * @param line the line
     * @param zone the time zone of the times in the line
     * @return the entry without number and UUID, empty if the line cannot be read
     */
    static Optional<ModerationEntry> parseLogLine(String line, ZoneId zone) {
        List<String> columns = new ArrayList<>(Arrays.asList(line.split(" \\| ", -1)));
        try {
            Instant time = LocalDateTime.parse(columns.removeFirst(), LOG_TIME).atZone(zone).toInstant();
            if (!columns.isEmpty() && columns.getFirst().matches("#\\d+")) {
                columns.removeFirst();
            }
            String label = columns.removeFirst();
            ModerationAction action = Arrays.stream(ModerationAction.values())
                    .filter(candidate -> candidate.label().equals(label)).findFirst().orElse(null);
            if (action == null) {
                return Optional.empty();
            }
            String target = "";
            if (action.hasTarget()) {
                target = columns.removeFirst();
                Matcher linked = LINKED_TARGET.matcher(target);
                if (linked.matches()) {
                    target = linked.group(1);
                }
            }
            String by = columns.removeFirst();
            if (!by.startsWith("by ")) {
                return Optional.empty();
            }
            Optional<Instant> until = Optional.empty();
            if (action.hasDuration()) {
                Matcher end = UNTIL.matcher(columns.removeFirst());
                if (end.matches()) {
                    // The line has the end in minutes; durations are whole minutes, so its seconds are the start's
                    until = Optional.of(LocalDateTime.parse(end.group(1), LOG_UNTIL).atZone(zone).toInstant()
                            .plusSeconds(time.atZone(zone).getSecond()));
                }
            }
            ModerationEntry.Network network = ModerationEntry.Network.LOCAL;
            if (columns.size() > 1 && columns.getFirst().equals("all servers")) {
                columns.removeFirst();
                network = ModerationEntry.Network.ALL_SERVERS;
            }
            String reason = String.join(" | ", columns);
            return Optional.of(new ModerationEntry(time, action, target, Optional.empty(), by.substring(3), until,
                    reason.isEmpty() || reason.equals(ModerationEntry.NO_REASON) ? Optional.empty()
                            : Optional.of(reason), network));
        } catch (DateTimeParseException | java.util.NoSuchElementException e) {
            return Optional.empty();
        }
    }

    /**
     * Takes over the entries of the moderation log file, numbered in their order.
     *
     * @param logFile the moderation log file
     * @param zone    the time zone of its times
     */
    private void importLog(Path logFile, ZoneId zone) {
        if (!Files.exists(logFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                parseLogLine(line, zone).ifPresent(entry -> {
                    ModerationEntry numbered = entry.withId(nextId++);
                    entries.put(numbered.id(), numbered);
                });
            }
            save();
            Constants.LOG.info("Took over {} entries of {} into {}", entries.size(), logFile.getFileName(),
                    FILE_NAME);
        } catch (IOException e) {
            Constants.LOG.error("Could not read the moderation log {}: {}", logFile, e.getMessage());
        }
    }

    /**
     * Reads the file; invalid entries are skipped.
     */
    private void load() {
        Data data = file.read();
        if (data == null || data.entries() == null) {
            return;
        }
        for (Entry entry : data.entries()) {
            try {
                ModerationEntry moderation = new ModerationEntry(Instant.parse(entry.time()),
                        ModerationAction.valueOf(entry.action()), Optional.ofNullable(entry.target()).orElse(""),
                        Optional.ofNullable(entry.uuid()).map(UUID::fromString), entry.moderator(),
                        Optional.ofNullable(entry.until()).map(Instant::parse), Optional.ofNullable(entry.reason()),
                        ModerationEntry.Network.valueOf(entry.network().toUpperCase(Locale.ROOT)),
                        Optional.ofNullable(entry.discordId()).map(id -> new ModerationEntry.LinkedAccount(id,
                                Optional.ofNullable(entry.discordName()))),
                        entry.id());
                entries.put(moderation.id(), moderation);
            } catch (IllegalArgumentException | NullPointerException | DateTimeParseException ignored) {
                // An invalid entry is skipped
            }
        }
        long highest = entries.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        nextId = Math.max(highest + 1, data.nextId());
    }

    /**
     * Writes the file.
     */
    private void save() {
        List<Entry> list = entries.values().stream().map(entry -> new Entry(entry.id(), entry.time().toString(),
                entry.action().name(), entry.target(), entry.player().map(UUID::toString).orElse(null),
                entry.moderator(), entry.until().map(Instant::toString).orElse(null), entry.reason().orElse(null),
                entry.network().name().toLowerCase(Locale.ROOT),
                entry.linked().map(ModerationEntry.LinkedAccount::id).orElse(null),
                entry.linked().flatMap(ModerationEntry.LinkedAccount::username).orElse(null))).toList();
        file.write(new Data(nextId, list));
    }

    /**
     * Content of the file.
     *
     * @param nextId  the number of the next entry
     * @param entries the entries
     */
    private record Data(@SerializedName("next_id") long nextId, List<Entry> entries) {
    }

    /**
     * An entry in the file.
     *
     * @param id          the number
     * @param time        when it happened, ISO-8601
     * @param action      the {@link ModerationAction} name
     * @param target      the target
     * @param uuid        the target player's UUID, {@code null} if unknown
     * @param moderator   who did it
     * @param until       the end of a temporary ban or mute, ISO-8601, {@code null} otherwise
     * @param reason      the reason, {@code null} if none
     * @param network     {@code local}, {@code all_servers} or {@code received}
     * @param discordId   the linked Discord account, {@code null} if none
     * @param discordName the linked Discord account's name, {@code null} if unknown
     */
    private record Entry(long id, String time, String action, String target, String uuid, String moderator,
                         String until, String reason, String network, @SerializedName("discord_id") Long discordId,
                         @SerializedName("discord_name") String discordName) {
    }
}
