package de.moritzrohleder.minecraftdiscordlink.applications;

import com.google.gson.annotations.SerializedName;
import de.moritzrohleder.minecraftdiscordlink.data.JsonFile;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The whitelist applications, stored in {@code minecraftdiscordlink/applications.json} on every change: the latest
 * application of every Discord member and the message with the apply button.
 * <p>
 * All methods are thread-safe.
 */
public final class ApplicationStore {

    /** Name of the file inside the data directory. */
    public static final String FILE_NAME = "applications.json";

    private final JsonFile<Data> file;
    private final Map<Long, Application> applications = new LinkedHashMap<>();
    private long buttonChannel;
    private long buttonMessage;

    /**
     * Loads the applications.
     *
     * @param dataDirectory the mod's data directory
     */
    public ApplicationStore(Path dataDirectory) {
        this.file = new JsonFile<>(dataDirectory.resolve(FILE_NAME), Data.class);
        load();
    }

    /**
     * Returns the latest application of a Discord member.
     *
     * @param discordId the member
     * @return the application, empty if the member never applied
     */
    public synchronized Optional<Application> latest(long discordId) {
        return Optional.ofNullable(applications.get(discordId));
    }

    /**
     * Checks whether a Discord member may apply now.
     *
     * @param discordId    the member
     * @param reapplyAfter how long a denied member has to wait
     * @param now          the current time
     * @return why the member may not apply, empty if they may
     */
    public synchronized Optional<Refusal> refusal(long discordId, Duration reapplyAfter, Instant now) {
        Application latest = applications.get(discordId);
        if (latest == null) {
            return Optional.empty();
        }
        return switch (latest.status()) {
            case OPEN -> Optional.of(new Refusal(Refusal.Kind.OPEN, Optional.empty()));
            case ACCEPTED -> Optional.of(new Refusal(Refusal.Kind.ACCEPTED, Optional.empty()));
            case DENIED -> {
                Instant again = latest.decided().orElse(latest.submitted()).plus(reapplyAfter);
                yield again.isAfter(now) ? Optional.of(new Refusal(Refusal.Kind.WAIT, Optional.of(again)))
                        : Optional.empty();
            }
        };
    }

    /**
     * Stores a new application, replacing the member's previous one.
     *
     * @param application the application
     */
    public synchronized void submit(Application application) {
        applications.put(application.discordId(), application);
        save();
    }

    /**
     * Sets the review message of an open application.
     *
     * @param discordId the applicant
     * @param channelId the channel of the review message
     * @param messageId the review message
     */
    public synchronized void setReviewMessage(long discordId, long channelId, long messageId) {
        Application application = applications.get(discordId);
        if (application != null) {
            applications.put(discordId, application.withReviewMessage(channelId, messageId));
            save();
        }
    }

    /**
     * Decides an open application.
     *
     * @param discordId the applicant
     * @param accepted  whether it was accepted
     * @param moderator who decided, e.g. {@code Discord @anedhel}
     * @param reason    the reason of a denial
     * @param now       the current time
     * @return the decided application, empty if the member has no open application
     */
    public synchronized Optional<Application> decide(long discordId, boolean accepted, String moderator,
                                                     Optional<String> reason, Instant now) {
        Application application = applications.get(discordId);
        if (application == null || application.status() != Status.OPEN) {
            return Optional.empty();
        }
        Application decided = application.decided(accepted ? Status.ACCEPTED : Status.DENIED, moderator, reason,
                now);
        applications.put(discordId, decided);
        save();
        return Optional.of(decided);
    }

    /**
     * Returns the message with the apply button.
     *
     * @return the channel and message ID, empty if none was sent
     */
    public synchronized Optional<long[]> buttonMessage() {
        return buttonMessage == 0 ? Optional.empty() : Optional.of(new long[]{buttonChannel, buttonMessage});
    }

    /**
     * Sets the message with the apply button.
     *
     * @param channelId the channel, 0 to forget the message
     * @param messageId the message, 0 to forget it
     */
    public synchronized void setButtonMessage(long channelId, long messageId) {
        buttonChannel = channelId;
        buttonMessage = messageId;
        save();
    }

    /**
     * Reads the file; invalid entries are skipped.
     */
    private void load() {
        Data data = file.read();
        if (data == null) {
            return;
        }
        buttonChannel = data.buttonChannel();
        buttonMessage = data.buttonMessage();
        if (data.applications() == null) {
            return;
        }
        for (Entry entry : data.applications()) {
            try {
                List<Answer> answers = entry.answers() == null ? List.of() : List.copyOf(entry.answers());
                Application application = new Application(entry.discordId(), UUID.fromString(entry.uuid()),
                        entry.name(), answers, Status.valueOf(entry.status().toUpperCase(Locale.ROOT)),
                        Instant.parse(entry.submitted()), Optional.ofNullable(entry.decided()).map(Instant::parse),
                        Optional.ofNullable(entry.decidedBy()), Optional.ofNullable(entry.reason()),
                        optional(entry.reviewChannel()), optional(entry.reviewMessage()));
                applications.put(application.discordId(), application);
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
        applications.values().forEach(application -> entries.add(new Entry(application.discordId(),
                application.player().toString(), application.name(), application.answers(),
                application.status().name().toLowerCase(Locale.ROOT), application.submitted().toString(),
                application.decided().map(Instant::toString).orElse(null), application.decidedBy().orElse(null),
                application.reason().orElse(null), application.reviewChannel().orElse(0),
                application.reviewMessage().orElse(0))));
        file.write(new Data(buttonChannel, buttonMessage, entries));
    }

    private static OptionalLong optional(long id) {
        return id == 0 ? OptionalLong.empty() : OptionalLong.of(id);
    }

    /**
     * The state of an application.
     */
    public enum Status {
        /** Waiting for a decision. */
        OPEN,
        /** Accepted: the applicant is whitelisted. */
        ACCEPTED,
        /** Denied. */
        DENIED
    }

    /**
     * Why a member may not apply.
     *
     * @param kind  the reason
     * @param until when a denied member may apply again
     */
    public record Refusal(Kind kind, Optional<Instant> until) {

        /**
         * Reasons a member may not apply.
         */
        public enum Kind {
            /** The member has an open application. */
            OPEN,
            /** The member's application was accepted already. */
            ACCEPTED,
            /** The member was denied recently. */
            WAIT
        }
    }

    /**
     * A question of the form and its answer.
     *
     * @param question the question
     * @param answer   the answer
     */
    public record Answer(String question, String answer) {
    }

    /**
     * An application.
     *
     * @param discordId     the applicant's Discord ID
     * @param player        the applicant's Minecraft UUID
     * @param name          the applicant's Minecraft name
     * @param answers       the answers to the questions
     * @param status        the state
     * @param submitted     when it was sent
     * @param decided       when it was accepted or denied
     * @param decidedBy     who accepted or denied it
     * @param reason        the reason of a denial
     * @param reviewChannel the channel of the review message
     * @param reviewMessage the review message
     */
    public record Application(long discordId, UUID player, String name, List<Answer> answers, Status status,
                              Instant submitted, Optional<Instant> decided, Optional<String> decidedBy,
                              Optional<String> reason, OptionalLong reviewChannel, OptionalLong reviewMessage) {

        /**
         * Creates a new open application.
         *
         * @param discordId the applicant's Discord ID
         * @param player    the applicant's Minecraft UUID
         * @param name      the applicant's Minecraft name
         * @param answers   the answers to the questions
         * @param submitted when it was sent
         * @return the application
         */
        public static Application open(long discordId, UUID player, String name, List<Answer> answers,
                                       Instant submitted) {
            return new Application(discordId, player, name, List.copyOf(answers), Status.OPEN, submitted,
                    Optional.empty(), Optional.empty(), Optional.empty(), OptionalLong.empty(), OptionalLong.empty());
        }

        private Application withReviewMessage(long channelId, long messageId) {
            return new Application(discordId, player, name, answers, status, submitted, decided, decidedBy, reason,
                    OptionalLong.of(channelId), OptionalLong.of(messageId));
        }

        private Application decided(Status newStatus, String moderator, Optional<String> denialReason,
                                    Instant now) {
            return new Application(discordId, player, name, answers, newStatus, submitted, Optional.of(now),
                    Optional.of(moderator), denialReason, reviewChannel, reviewMessage);
        }
    }

    /**
     * Content of the file.
     *
     * @param buttonChannel the channel of the message with the apply button, 0 if none
     * @param buttonMessage the message with the apply button, 0 if none
     * @param applications  the latest application of every member
     */
    private record Data(@SerializedName("button_channel") long buttonChannel,
                        @SerializedName("button_message") long buttonMessage, List<Entry> applications) {
    }

    /**
     * An application in the file.
     *
     * @param discordId     the applicant's Discord ID
     * @param uuid          the applicant's Minecraft UUID
     * @param name          the applicant's Minecraft name
     * @param answers       the answers
     * @param status        {@code open}, {@code accepted} or {@code denied}
     * @param submitted     when it was sent, ISO-8601
     * @param decided       when it was decided, ISO-8601, {@code null} if open
     * @param decidedBy     who decided, {@code null} if open
     * @param reason        the reason of a denial, {@code null} otherwise
     * @param reviewChannel the channel of the review message, 0 if none
     * @param reviewMessage the review message, 0 if none
     */
    private record Entry(@SerializedName("discord_id") long discordId, String uuid, String name,
                         List<Answer> answers, String status, String submitted, String decided,
                         @SerializedName("decided_by") String decidedBy, String reason,
                         @SerializedName("review_channel") long reviewChannel,
                         @SerializedName("review_message") long reviewMessage) {
    }
}
