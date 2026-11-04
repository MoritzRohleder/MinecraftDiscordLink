package de.moritzrohleder.minecraftdiscordlink.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModerationHistoryTest {

    private static final Instant TIME = Instant.parse("2026-10-30T15:40:12Z");
    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID ALEX = UUID.fromString("61699b2e-d327-4a01-9f1e-0ea8c3f06bc6");

    @TempDir
    Path dir;

    @Test
    void parsesLogLines() {
        ModerationEntry ban = ModerationHistory.parseLogLine("2026-10-30 15:40:12 | BAN | Steve (@steve) "
                + "| by Discord @anedhel | 7d (until 2026-11-06 15:40) | all servers | Griefing | again",
                ZoneOffset.UTC).orElseThrow();
        assertEquals(TIME, ban.time());
        assertEquals(ModerationAction.BAN, ban.action());
        assertEquals("Steve", ban.target());
        assertEquals("Discord @anedhel", ban.moderator());
        assertEquals(Optional.of(Instant.parse("2026-11-06T15:40:12Z")), ban.until());
        assertEquals("7d", ban.durationText(ZoneOffset.UTC).split(" ")[0]);
        assertEquals(ModerationEntry.Network.ALL_SERVERS, ban.network());
        assertEquals(Optional.of("Griefing | again"), ban.reason());

        ModerationEntry permanent = ModerationHistory.parseLogLine("2026-10-30 15:40:12 | #7 | BAN | Steve "
                + "| by Server | permanent | no reason given", ZoneOffset.UTC).orElseThrow();
        assertTrue(permanent.until().isEmpty());
        assertTrue(permanent.reason().isEmpty());

        ModerationEntry whitelistOn = ModerationHistory.parseLogLine("2026-10-30 15:40:12 | WHITELIST ON | by Alex "
                + "| Event", ZoneOffset.UTC).orElseThrow();
        assertEquals("", whitelistOn.target());
        assertEquals(Optional.of("Event"), whitelistOn.reason());

        assertTrue(ModerationHistory.parseLogLine("not a log line", ZoneOffset.UTC).isEmpty());
        assertTrue(ModerationHistory.parseLogLine("2026-10-30 15:40:12 | UNKNOWN | Steve | by Alex | x",
                ZoneOffset.UTC).isEmpty());
    }

    @Test
    void takesOverTheLogFileOnce() throws IOException {
        Files.writeString(dir.resolve(ModerationLog.FILE_NAME), """
                2026-10-30 15:40:12 | KICK | Steve | by Alex | Spam
                garbage
                2026-10-31 10:00:00 | MUTE | Steve | by Server | 1d (until 2026-11-01 10:00) | Spam
                """);
        ModerationHistory history = new ModerationHistory(dir, ZoneOffset.UTC);

        List<ModerationEntry> entries = history.of(STEVE, "steve");
        assertEquals(List.of(2L, 1L), entries.stream().map(ModerationEntry::id).toList());
        assertEquals(3, history.add(kick(STEVE, "Steve")).id());

        // The history file exists now: the log is not taken over again, the numbers go on
        ModerationHistory reloaded = new ModerationHistory(dir, ZoneOffset.UTC);
        assertEquals(3, reloaded.of(STEVE, "Steve").size());
        assertEquals(4, reloaded.add(kick(STEVE, "Steve")).id());
    }

    @Test
    void findsEntriesByUuidAfterARename() {
        ModerationHistory history = new ModerationHistory(dir, ZoneOffset.UTC);
        history.add(kick(STEVE, "Steve"));
        history.add(kick(ALEX, "Alex"));
        ModerationEntry ban = history.add(new ModerationEntry(TIME, ModerationAction.BAN, "Steve",
                Optional.of(STEVE), "Server", Optional.empty(), Optional.of("Griefing")));

        assertEquals(List.of(3L, 1L), history.of(STEVE, "Steve2").stream().map(ModerationEntry::id).toList());
        assertTrue(history.replace(ban.withReason(Optional.of("X-Ray"))));
        assertEquals(Optional.of("X-Ray"), new ModerationHistory(dir, ZoneOffset.UTC).get(3).orElseThrow().reason());
    }

    private static ModerationEntry kick(UUID player, String name) {
        return new ModerationEntry(TIME, ModerationAction.KICK, name, Optional.of(player), "Alex", Optional.empty(),
                Optional.of("Spam"));
    }
}
