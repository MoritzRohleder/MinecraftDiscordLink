package de.moritzrohleder.minecraftdiscordlink.text;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats and parses durations for texts shown to users, e.g. the uptime or the duration of a ban.
 */
public final class Durations {

    /** A duration typed by a user: one or more numbers with the units w, d, h and m, e.g. {@code 1d12h}. */
    private static final Pattern INPUT = Pattern.compile("(?:\\d+[wdhm])+");
    /** One part of a typed duration. */
    private static final Pattern PART = Pattern.compile("(\\d+)([wdhm])");
    /** The longest duration accepted by {@link #parse(String)}; longer bans should be permanent. */
    private static final Duration MAX = Duration.ofDays(100L * 365);

    private Durations() {
    }

    /**
     * Formats a duration with days, hours and minutes, leaving out leading zero units, e.g. {@code 2d 3h 15m},
     * {@code 3h 0m} or {@code 15m}. Seconds are cut off.
     *
     * @param duration the duration, negative durations are treated as zero
     * @return the formatted duration
     */
    public static String format(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        long days = minutes / (24 * 60);
        long hours = minutes / 60 % 24;
        long remainingMinutes = minutes % 60;

        if (days > 0) {
            return days + "d " + hours + "h " + remainingMinutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + remainingMinutes + "m";
        }
        return remainingMinutes + "m";
    }

    /**
     * Formats a duration with days, hours and minutes, leaving out all zero units, e.g. {@code 7d},
     * {@code 1d 12h} or {@code 17d}. Seconds are cut off.
     *
     * @param duration the duration, negative durations are treated as zero
     * @return the formatted duration, {@code 0m} for less than a minute
     */
    public static String formatCompact(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        long[] values = {minutes / (24 * 60), minutes / 60 % 24, minutes % 60};
        String[] units = {"d", "h", "m"};
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (values[i] > 0) {
                text.append(text.isEmpty() ? "" : " ").append(values[i]).append(units[i]);
            }
        }
        return text.isEmpty() ? "0m" : text.toString();
    }

    /**
     * Parses a duration typed by a user, e.g. {@code 30m}, {@code 12h}, {@code 7d}, {@code 2w} or {@code 1d12h}.
     *
     * @param input the typed duration, case-insensitive
     * @return the duration, empty if the input is invalid, zero or longer than 100 years
     */
    public static Optional<Duration> parse(String input) {
        String text = input.strip().toLowerCase(Locale.ROOT);
        if (!INPUT.matcher(text).matches()) {
            return Optional.empty();
        }
        Duration duration = Duration.ZERO;
        Matcher part = PART.matcher(text);
        try {
            while (part.find()) {
                long value = Long.parseLong(part.group(1));
                duration = duration.plus(switch (part.group(2)) {
                    case "w" -> Duration.ofDays(Math.multiplyExact(value, 7));
                    case "d" -> Duration.ofDays(value);
                    case "h" -> Duration.ofHours(value);
                    default -> Duration.ofMinutes(value);
                });
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return Optional.empty();
        }
        return duration.isZero() || duration.compareTo(MAX) > 0 ? Optional.empty() : Optional.of(duration);
    }
}
