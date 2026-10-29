package de.moritzrohleder.minecraftdiscordlink.text;

import java.time.Duration;

/**
 * Formats durations for texts shown to users, e.g. the uptime.
 */
public final class Durations {

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
}
