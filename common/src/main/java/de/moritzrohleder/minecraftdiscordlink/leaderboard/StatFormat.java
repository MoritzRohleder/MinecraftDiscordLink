package de.moritzrohleder.minecraftdiscordlink.leaderboard;

import de.moritzrohleder.minecraftdiscordlink.text.Durations;

import java.time.Duration;
import java.util.Locale;

/**
 * How values of a statistic are shown, matching the unit of the vanilla statistic.
 */
public enum StatFormat {

    /** A plain number, e.g. {@code 1,234}. */
    NUMBER,
    /** Tenths, e.g. damage in half hearts: {@code 12.5}. */
    TENTHS,
    /** A distance in centimeters, shown as {@code 38 cm}, {@code 850 m} or {@code 12.3 km}. */
    DISTANCE,
    /** A time in ticks, shown as {@code 42s} below a minute, otherwise as {@code 2d 3h 15m}. */
    TIME;

    /** Ticks per second of the time statistics. */
    private static final int TICKS_PER_SECOND = 20;
    /** Seconds per minute. */
    private static final int SECONDS_PER_MINUTE = 60;
    /** Centimeters per meter. */
    private static final double CM_PER_METER = 100;
    /** Meters per kilometer. */
    private static final double METERS_PER_KM = 1000;

    /**
     * Formats a value of a statistic. Small times and distances are shown in a smaller unit, so they are not
     * rounded down to 0.
     *
     * @param value the raw value of the statistic
     * @return the formatted value
     */
    public String format(long value) {
        return switch (this) {
            case NUMBER -> String.format(Locale.ROOT, "%,d", value);
            case TENTHS -> String.format(Locale.ROOT, "%,.1f", value / 10.0);
            case DISTANCE -> {
                double meters = value / CM_PER_METER;
                yield value < CM_PER_METER ? value + " cm"
                        : meters < METERS_PER_KM ? String.format(Locale.ROOT, "%d m", (long) meters)
                        : String.format(Locale.ROOT, "%,.1f km", meters / METERS_PER_KM);
            }
            case TIME -> value < SECONDS_PER_MINUTE * TICKS_PER_SECOND ? value / TICKS_PER_SECOND + "s"
                    : Durations.format(Duration.ofSeconds(value / TICKS_PER_SECOND));
        };
    }
}
