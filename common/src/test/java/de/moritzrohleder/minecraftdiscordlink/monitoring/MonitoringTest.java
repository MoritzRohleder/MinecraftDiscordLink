package de.moritzrohleder.minecraftdiscordlink.monitoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitoringTest {

    private static final Instant START = Instant.parse("2026-10-30T22:00:00Z");
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
    private static final UUID STEVE = new UUID(0, 1);
    private static final UUID ALEX = new UUID(0, 2);

    @TempDir
    Path dir;

    @Test
    void warnsAfterTheDurationAndClearsAfterwards() {
        LagDetector lag = new LagDetector();

        assertTrue(lag.update(START, 12, 15, MINUTE, FIVE_MINUTES).isEmpty());
        assertTrue(lag.update(START.plusSeconds(30), 10, 15, MINUTE, FIVE_MINUTES).isEmpty());
        Optional<LagDetector.Event> warning = lag.update(START.plusSeconds(60), 11, 15, MINUTE, FIVE_MINUTES);
        assertTrue(warning.orElseThrow().warning());
        assertEquals(10, warning.get().lowest());
        assertTrue(lag.update(START.plusSeconds(65), 9, 15, MINUTE, FIVE_MINUTES).isEmpty());

        Optional<LagDetector.Event> allClear = lag.update(START.plusSeconds(90), 20, 15, MINUTE, FIVE_MINUTES);
        assertFalse(allClear.orElseThrow().warning());
        assertEquals(9, allClear.get().lowest());
        assertEquals(Duration.ofSeconds(90), allClear.get().duration());
    }

    @Test
    void ignoresShortLagAndKeepsTheCooldown() {
        LagDetector lag = new LagDetector();
        lag.update(START, 10, 15, MINUTE, FIVE_MINUTES);
        assertTrue(lag.update(START.plusSeconds(30), 20, 15, MINUTE, FIVE_MINUTES).isEmpty());

        lag.update(START.plusSeconds(100), 10, 15, MINUTE, FIVE_MINUTES);
        assertTrue(lag.update(START.plusSeconds(160), 10, 15, MINUTE, FIVE_MINUTES).orElseThrow().warning());
        lag.update(START.plusSeconds(170), 20, 15, MINUTE, FIVE_MINUTES);

        // New lag within the cooldown of five minutes after the warning
        lag.update(START.plusSeconds(200), 10, 15, MINUTE, FIVE_MINUTES);
        assertTrue(lag.update(START.plusSeconds(300), 10, 15, MINUTE, FIVE_MINUTES).isEmpty());
        assertTrue(lag.update(START.plusSeconds(460), 10, 15, MINUTE, FIVE_MINUTES).orElseThrow().warning());
    }

    @Test
    void collectsTheReportNumbers() {
        MutableClock clock = new MutableClock(START);
        ReportTracker tracker = new ReportTracker(dir, clock);
        tracker.join(STEVE, false, 1);
        clock.now = START.plusSeconds(600);
        tracker.join(ALEX, true, 2);
        clock.now = START.plusSeconds(1200);
        tracker.leave(STEVE);

        // Survives a restart, except for the sessions
        ReportTracker restarted = new ReportTracker(dir, clock);
        restarted.join(ALEX, false, 1);
        clock.now = START.plusSeconds(1800);
        ReportTracker.Report report = restarted.finish(List.of(ALEX));

        assertEquals(List.of(STEVE, ALEX), report.players());
        assertEquals(List.of(ALEX), report.newPlayers());
        assertEquals(2, report.peak());
        assertEquals(List.of(STEVE, ALEX), List.copyOf(report.playSeconds().keySet()));
        assertEquals(1200L, report.playSeconds().get(STEVE));
        assertEquals(600L, report.playSeconds().get(ALEX));

        clock.now = START.plusSeconds(2400);
        ReportTracker.Report next = restarted.finish(List.of());
        assertEquals(List.of(ALEX), next.players());
        assertEquals(1, next.peak());
        assertEquals(600L, next.playSeconds().get(ALEX));
    }

    @Test
    void reportsOnceTheReportTimePassed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-30T22:00:00Z"));
        ReportTracker tracker = new ReportTracker(dir, clock);
        ZoneId zone = ZoneOffset.UTC;

        assertFalse(tracker.isDue(LocalTime.MIDNIGHT, zone));
        clock.now = Instant.parse("2026-10-30T23:59:00Z");
        assertFalse(tracker.isDue(LocalTime.MIDNIGHT, zone));
        clock.now = Instant.parse("2026-10-31T00:00:00Z");
        assertTrue(tracker.isDue(LocalTime.MIDNIGHT, zone));
        tracker.finish(List.of());
        assertFalse(tracker.isDue(LocalTime.MIDNIGHT, zone));
        clock.now = Instant.parse("2026-11-01T00:00:00Z");
        assertTrue(tracker.isDue(LocalTime.MIDNIGHT, zone));
    }

    /**
     * A clock that can be set in tests.
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
