package de.moritzrohleder.minecraftdiscordlink.moderation;

import de.moritzrohleder.minecraftdiscordlink.text.Durations;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * One entry of the moderation log.
 *
 * @param time      when the action happened
 * @param action    the kind of action
 * @param target    the player name, IP address or role, empty for switching a whitelist on or off
 * @param player    the UUID of the target player, if the target is a player
 * @param moderator who did it, e.g. {@code Discord @anedhel}, {@code Steve} or {@code Server}
 * @param until     when a temporary ban ends, empty otherwise
 * @param reason    the reason, empty if none was given
 */
public record ModerationEntry(Instant time, ModerationAction action, String target, Optional<UUID> player,
                              String moderator, Optional<Instant> until, Optional<String> reason) {

    /** Format of times in the log file, in the server's time zone. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** Format of the end of a temporary ban, in the server's time zone. */
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** Shown instead of a missing reason. */
    public static final String NO_REASON = "no reason given";

    /**
     * Returns the duration of a temporary ban, rounded to minutes.
     *
     * @return the duration, empty if the ban is permanent or the action is no ban
     */
    public Optional<Duration> duration() {
        // Rounded, as the end of a ban is stored with seconds and the start is a little later than typed
        return until.map(end -> Duration.ofMinutes(Math.round(Duration.between(time, end).toSeconds() / 60.0)));
    }

    /**
     * Formats the line of the moderation log file, e.g.
     * {@code 2026-10-30 15:40:12 | BAN | Steve | by Discord @anedhel | 7d (until 2026-11-06 15:40) | Griefing}.
     * Permanent bans show {@code permanent}; actions without duration leave that column out. Expired bans have
     * neither moderator nor reason: {@code 2026-11-06 15:40:30 | BAN EXPIRED | Steve}.
     *
     * @param zone the time zone of the server
     * @return the line, without line break
     */
    public String toLogLine(ZoneId zone) {
        StringBuilder line = new StringBuilder(TIME.format(time.atZone(zone)))
                .append(" | ").append(action.label());
        if (action.hasTarget()) {
            line.append(" | ").append(target);
        }
        if (action == ModerationAction.BAN_EXPIRED) {
            return line.toString();
        }
        line.append(" | by ").append(moderator);
        if (action == ModerationAction.BAN || action == ModerationAction.IP_BAN) {
            line.append(" | ").append(durationText(zone));
        }
        return line.append(" | ").append(reason.orElse(NO_REASON)).toString();
    }

    /**
     * Describes how long a ban lasts.
     *
     * @param zone the time zone of the server
     * @return e.g. {@code 7d (until 2026-11-06 15:40)} or {@code permanent}
     */
    public String durationText(ZoneId zone) {
        return until.map(end -> Durations.formatCompact(duration().orElseThrow()) + " (until "
                + UNTIL.format(end.atZone(zone)) + ")").orElse("permanent");
    }
}
