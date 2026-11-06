package de.moritzrohleder.minecraftdiscordlink.reports;

import com.google.gson.annotations.SerializedName;
import de.moritzrohleder.minecraftdiscordlink.data.JsonFile;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The reports of players, stored in {@code minecraftdiscordlink/reports.json} on every change. Reports are numbered
 * from 1. The time of a reporter's last report is kept in memory only, for the cooldown.
 * <p>
 * All methods are thread-safe.
 */
public final class ReportStore {

    /** Name of the file inside the data directory. */
    public static final String FILE_NAME = "reports.json";

    private final JsonFile<Data> file;
    private final Map<Long, Report> reports = new LinkedHashMap<>();
    private final Map<String, Instant> lastReports = new HashMap<>();
    private long nextId = 1;

    /**
     * Loads the reports.
     *
     * @param dataDirectory the mod's data directory
     */
    public ReportStore(Path dataDirectory) {
        this.file = new JsonFile<>(dataDirectory.resolve(FILE_NAME), Data.class);
        load();
    }

    /**
     * Checks the cooldown of a reporter and, if it is over, starts it again.
     *
     * @param reporter the reporter, e.g. {@code mc:<uuid>} or {@code discord:<id>}
     * @param cooldown how long a reporter has to wait between two reports
     * @param now      the current time
     * @return when the reporter may report again, empty if the report may be sent now
     */
    public synchronized Optional<Instant> claimCooldown(String reporter, Duration cooldown, Instant now) {
        Instant last = lastReports.get(reporter);
        if (last != null && last.plus(cooldown).isAfter(now)) {
            return Optional.of(last.plus(cooldown));
        }
        lastReports.put(reporter, now);
        return Optional.empty();
    }

    /**
     * Stores a new report with the next number.
     *
     * @param report the report; its number is ignored
     * @return the numbered report
     */
    public synchronized Report add(Report report) {
        Report numbered = report.withId(nextId++);
        reports.put(numbered.id(), numbered);
        save();
        return numbered;
    }

    /**
     * Returns a report.
     *
     * @param id the number
     * @return the report, empty if there is none
     */
    public synchronized Optional<Report> get(long id) {
        return Optional.ofNullable(reports.get(id));
    }

    /**
     * Sets the message of a report in the report channel.
     *
     * @param id        the number
     * @param channelId the channel
     * @param messageId the message
     */
    public synchronized void setMessage(long id, long channelId, long messageId) {
        Report report = reports.get(id);
        if (report != null) {
            reports.put(id, report.withMessage(channelId, messageId));
            save();
        }
    }

    /**
     * Closes an open report.
     *
     * @param id      the number
     * @param status  {@link Status#HANDLED} or {@link Status#DISMISSED}
     * @param by      who closed it, e.g. {@code Discord @anedhel}
     * @param outcome what was done, e.g. {@code Muted Steve for 1h (#42)}, empty for none
     * @return the closed report, empty if it was not open
     */
    public synchronized Optional<Report> close(long id, Status status, String by, Optional<String> outcome) {
        Report report = reports.get(id);
        if (report == null || report.status() != Status.OPEN) {
            return Optional.empty();
        }
        Report closed = report.closed(status, by, outcome);
        reports.put(id, closed);
        save();
        return Optional.of(closed);
    }

    /**
     * Reads the file; invalid entries are skipped.
     */
    private void load() {
        Data data = file.read();
        if (data == null || data.reports() == null) {
            return;
        }
        for (Entry entry : data.reports()) {
            try {
                Report report = new Report(entry.id(), Instant.parse(entry.time()), entry.reporter(),
                        entry.reporterDiscordId() == null ? OptionalLong.empty()
                                : OptionalLong.of(entry.reporterDiscordId()),
                        UUID.fromString(entry.uuid()), entry.name(), entry.reason(),
                        Optional.ofNullable(entry.message()),
                        Status.valueOf(entry.status().toUpperCase(Locale.ROOT)), Optional.ofNullable(entry.closedBy()),
                        Optional.ofNullable(entry.outcome()), entry.channel(), entry.messageId());
                reports.put(report.id(), report);
            } catch (IllegalArgumentException | NullPointerException | DateTimeParseException ignored) {
                // An invalid entry is skipped
            }
        }
        long highest = reports.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
        nextId = Math.max(highest + 1, data.nextId());
    }

    /**
     * Writes the file.
     */
    private void save() {
        List<Entry> entries = new ArrayList<>();
        reports.values().forEach(report -> entries.add(new Entry(report.id(), report.time().toString(),
                report.reporter(), report.reporterDiscordId().isPresent()
                ? report.reporterDiscordId().getAsLong() : null,
                report.player().toString(), report.name(), report.reason(), report.message().orElse(null),
                report.status().name().toLowerCase(Locale.ROOT), report.closedBy().orElse(null),
                report.outcome().orElse(null), report.channelId(), report.messageId())));
        file.write(new Data(nextId, entries));
    }

    /**
     * The state of a report.
     */
    public enum Status {
        /** Waiting for the staff. */
        OPEN,
        /** The staff acted on it or marked it as done. */
        HANDLED,
        /** The staff dismissed it. */
        DISMISSED
    }

    /**
     * A report.
     *
     * @param id                the number
     * @param time              when it was sent
     * @param reporter          who sent it, e.g. {@code Alex} or {@code Discord @anedhel}
     * @param reporterDiscordId the reporter's Discord ID if sent from Discord
     * @param player            the reported player's UUID
     * @param name              the reported player's name
     * @param reason            the reason
     * @param message           the link of a reported Discord message
     * @param status            the state
     * @param closedBy          who closed it
     * @param outcome           what was done
     * @param channelId         the channel of its message, 0 if none
     * @param messageId         its message in the report channel, 0 if none
     */
    public record Report(long id, Instant time, String reporter, OptionalLong reporterDiscordId, UUID player,
                         String name, String reason, Optional<String> message, Status status,
                         Optional<String> closedBy, Optional<String> outcome, long channelId, long messageId) {

        /**
         * Creates a new open report without number.
         *
         * @param time              when it was sent
         * @param reporter          who sent it
         * @param reporterDiscordId the reporter's Discord ID if sent from Discord
         * @param player            the reported player's UUID
         * @param name              the reported player's name
         * @param reason            the reason
         * @param message           the link of a reported Discord message
         * @return the report
         */
        public static Report open(Instant time, String reporter, OptionalLong reporterDiscordId, UUID player,
                                  String name, String reason, Optional<String> message) {
            return new Report(0, time, reporter, reporterDiscordId, player, name, reason, message, Status.OPEN,
                    Optional.empty(), Optional.empty(), 0, 0);
        }

        private Report withId(long newId) {
            return new Report(newId, time, reporter, reporterDiscordId, player, name, reason, message, status,
                    closedBy, outcome, channelId, messageId);
        }

        private Report withMessage(long newChannelId, long newMessageId) {
            return new Report(id, time, reporter, reporterDiscordId, player, name, reason, message, status, closedBy,
                    outcome, newChannelId, newMessageId);
        }

        private Report closed(Status newStatus, String by, Optional<String> newOutcome) {
            return new Report(id, time, reporter, reporterDiscordId, player, name, reason, message, newStatus,
                    Optional.of(by), newOutcome, channelId, messageId);
        }
    }

    /**
     * Content of the file.
     *
     * @param nextId  the number of the next report
     * @param reports the reports
     */
    private record Data(@SerializedName("next_id") long nextId, List<Entry> reports) {
    }

    /**
     * A report in the file.
     *
     * @param id                the number
     * @param time              when it was sent, ISO-8601
     * @param reporter          who sent it
     * @param reporterDiscordId the reporter's Discord ID, {@code null} if sent from Minecraft
     * @param uuid              the reported player's UUID
     * @param name              the reported player's name
     * @param reason            the reason
     * @param message           the link of a reported Discord message, {@code null} if none
     * @param status            {@code open}, {@code handled} or {@code dismissed}
     * @param closedBy          who closed it, {@code null} if open
     * @param outcome           what was done, {@code null} if nothing
     * @param channel           the channel of its message, 0 if none
     * @param messageId         its message, 0 if none
     */
    private record Entry(long id, String time, String reporter,
                         @SerializedName("reporter_discord_id") Long reporterDiscordId, String uuid, String name,
                         String reason, String message, String status, @SerializedName("closed_by") String closedBy,
                         String outcome, long channel, @SerializedName("message_id") long messageId) {
    }
}
