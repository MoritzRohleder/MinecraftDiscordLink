package de.moritzrohleder.minecraftdiscordlink.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Detects lag from regular TPS measurements: a warning when the TPS stay below a threshold for some time, an
 * all-clear when they are back above it. Between two warnings there is a cooldown, so a server hovering around the
 * threshold does not flood the channel; an all-clear always follows its warning.
 * <p>
 * Not thread-safe; call it from one thread.
 */
public final class LagDetector {

    /** When the TPS dropped below the threshold, {@code null} while they are above. */
    private Instant belowSince;
    /** When the current lag was reported, {@code null} if no lag is reported. */
    private Instant reportedAt;
    /** When the last warning was sent. */
    private Instant lastWarning;
    /** The lowest TPS of the current lag. */
    private double lowest;

    /**
     * Creates a detector without measurements.
     */
    public LagDetector() {
    }

    /**
     * Takes a measurement.
     *
     * @param now       the time of the measurement
     * @param tps       the measured ticks per second
     * @param threshold TPS below this value count as lag
     * @param duration  how long the TPS must stay below the threshold for a warning
     * @param cooldown  minimum time between two warnings
     * @return a warning or an all-clear to send, empty if nothing changed
     */
    public Optional<Event> update(Instant now, double tps, double threshold, Duration duration, Duration cooldown) {
        if (tps >= threshold) {
            belowSince = null;
            if (reportedAt == null) {
                return Optional.empty();
            }
            Event allClear = new Event(false, tps, lowest, Duration.between(reportedAt, now));
            reportedAt = null;
            return Optional.of(allClear);
        }
        if (belowSince == null) {
            belowSince = now;
            lowest = tps;
        }
        lowest = Math.min(lowest, tps);
        boolean longEnough = !Duration.between(belowSince, now).minus(duration).isNegative();
        boolean cooledDown = lastWarning == null || !Duration.between(lastWarning, now).minus(cooldown).isNegative();
        if (reportedAt == null && longEnough && cooledDown) {
            reportedAt = belowSince;
            lastWarning = now;
            return Optional.of(new Event(true, tps, lowest, Duration.between(belowSince, now)));
        }
        return Optional.empty();
    }

    /**
     * A lag warning or all-clear.
     *
     * @param warning  {@code true} for a warning, {@code false} for an all-clear
     * @param tps      the current TPS
     * @param lowest   the lowest TPS of the lag
     * @param duration how long the lag has lasted (warning) or lasted (all-clear)
     */
    public record Event(boolean warning, double tps, double lowest, Duration duration) {
    }
}
