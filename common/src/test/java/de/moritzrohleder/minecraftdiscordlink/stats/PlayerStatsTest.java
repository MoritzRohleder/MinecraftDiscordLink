package de.moritzrohleder.minecraftdiscordlink.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStatsTest {

    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID ALEX = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
    private static final Instant DAY_ONE = Instant.parse("2026-10-28T12:00:00Z");

    @TempDir
    Path dir;

    @Test
    void countsDistinctPlayersAndPersistsThem() {
        PlayerStats stats = new PlayerStats(dir, Clock.fixed(DAY_ONE, ZoneOffset.UTC));
        stats.recordJoin(STEVE, List.of(STEVE));
        stats.recordJoin(STEVE, List.of(STEVE));
        stats.recordJoin(ALEX, List.of(STEVE, ALEX));

        assertEquals(2, stats.sessionCount());
        assertEquals(2, stats.todayCount(List.of()));
        assertEquals(2, stats.totalCount());

        PlayerStats restarted = new PlayerStats(dir, Clock.fixed(DAY_ONE.plusSeconds(60), ZoneOffset.UTC));
        assertEquals(0, restarted.sessionCount());
        assertEquals(2, restarted.todayCount(List.of()));
        assertEquals(2, restarted.totalCount());
    }

    @Test
    void startsNewDayWithPlayersOnline() {
        MutableClock clock = new MutableClock(DAY_ONE);
        PlayerStats stats = new PlayerStats(dir, clock);
        stats.recordJoin(STEVE, List.of(STEVE));
        stats.recordJoin(ALEX, List.of(STEVE, ALEX));

        clock.advance(Duration.ofDays(1));

        assertEquals(1, stats.todayCount(List.of(ALEX)));
        assertEquals(2, stats.totalCount());
        assertEquals(2, stats.sessionCount());
    }

    @Test
    void doesNotOverwriteBrokenFile() throws IOException {
        Path file = dir.resolve(PlayerStats.FILE_NAME);
        Files.writeString(file, "{ broken");

        PlayerStats stats = new PlayerStats(dir, Clock.fixed(DAY_ONE, ZoneOffset.UTC));
        stats.recordJoin(STEVE, List.of(STEVE));

        assertEquals("{ broken", Files.readString(file));
        assertEquals(1, stats.totalCount());
    }

    @Test
    void writesSpecifiedFormat() throws IOException {
        new PlayerStats(dir, Clock.fixed(DAY_ONE, ZoneOffset.UTC)).recordJoin(STEVE, List.of(STEVE));

        String content = Files.readString(dir.resolve(PlayerStats.FILE_NAME));
        assertTrue(content.contains("\"total\""));
        assertTrue(content.contains("\"date\": \"2026-10-28\""));
        assertTrue(content.contains(STEVE.toString()));
    }

    /**
     * A clock that can be moved forward in tests.
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
