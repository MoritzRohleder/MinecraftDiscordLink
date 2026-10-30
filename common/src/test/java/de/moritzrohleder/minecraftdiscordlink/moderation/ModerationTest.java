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

class ModerationTest {

    private static final Instant TIME = Instant.parse("2026-10-30T15:40:12Z");
    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir
    Path dir;

    @Test
    void formatsLogLines() {
        ModerationEntry ban = new ModerationEntry(TIME, ModerationAction.BAN, "Steve", Optional.of(STEVE),
                "Discord @anedhel", Optional.of(Instant.parse("2026-11-06T15:40:12Z")), Optional.of("Griefing"));
        assertEquals("2026-10-30 15:40:12 | BAN | Steve | by Discord @anedhel | 7d (until 2026-11-06 15:40) "
                + "| Griefing", ban.toLogLine(ZoneOffset.UTC));

        ModerationEntry permanent = new ModerationEntry(TIME, ModerationAction.BAN, "Steve", Optional.of(STEVE),
                "Server", Optional.empty(), Optional.of("Banned by an operator."));
        assertEquals("2026-10-30 15:40:12 | BAN | Steve | by Server | permanent | Banned by an operator.",
                permanent.toLogLine(ZoneOffset.UTC));

        ModerationEntry whitelistOn = new ModerationEntry(TIME, ModerationAction.WHITELIST_ON, "", Optional.empty(),
                "Alex", Optional.empty(), Optional.empty());
        assertEquals("2026-10-30 15:40:12 | WHITELIST ON | by Alex | no reason given",
                whitelistOn.toLogLine(ZoneOffset.UTC));

        ModerationEntry expired = new ModerationEntry(TIME, ModerationAction.BAN_EXPIRED, "Steve", Optional.of(STEVE),
                "", Optional.empty(), Optional.empty());
        assertEquals("2026-10-30 15:40:12 | BAN EXPIRED | Steve", expired.toLogLine(ZoneOffset.UTC));
    }

    @Test
    void writesLogFile() throws IOException {
        ModerationLog log = new ModerationLog(dir, ZoneOffset.UTC);
        List<ModerationEntry> sent = new java.util.ArrayList<>();
        log.setListener(sent::add);
        ModerationEntry kick = new ModerationEntry(TIME, ModerationAction.KICK, "Steve", Optional.of(STEVE),
                "Alex", Optional.empty(), Optional.of("Spam"));

        log.record(kick);
        log.record(kick);

        assertEquals(List.of(kick.toLogLine(ZoneOffset.UTC), kick.toLogLine(ZoneOffset.UTC)),
                Files.readAllLines(dir.resolve(ModerationLog.FILE_NAME)));
        assertEquals(2, sent.size());
    }

    @Test
    void scopesReplaceAndRestore() {
        assertTrue(ModerationContext.current().isEmpty());
        ModerationContext.enterCommand("Steve");
        assertEquals("Steve", ModerationContext.current().orElseThrow().moderator());

        ModerationContext.run(ModerationContext.Scope.of("Steve", " Griefing "), () ->
                assertEquals(Optional.of("Griefing"), ModerationContext.current().orElseThrow().reason()));
        assertTrue(ModerationContext.current().orElseThrow().reason().isEmpty());

        ModerationContext.exitCommand();
        assertTrue(ModerationContext.current().isEmpty());

        // A command inside an action keeps the action's moderator, e.g. from the Discord console channel
        ModerationContext.run(ModerationContext.Scope.of("Discord @anedhel (console)", null), () -> {
            ModerationContext.enterCommand("Server");
            assertEquals("Discord @anedhel (console)", ModerationContext.current().orElseThrow().moderator());
            ModerationContext.exitCommand();
            assertEquals("Discord @anedhel (console)", ModerationContext.current().orElseThrow().moderator());
        });
        assertTrue(ModerationContext.current().isEmpty());
    }
}
