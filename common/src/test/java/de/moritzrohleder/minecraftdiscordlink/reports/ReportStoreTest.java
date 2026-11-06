package de.moritzrohleder.minecraftdiscordlink.reports;

import de.moritzrohleder.minecraftdiscordlink.reports.ReportStore.Report;
import de.moritzrohleder.minecraftdiscordlink.reports.ReportStore.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    @TempDir
    Path dir;

    @Test
    void limitsReportsPerReporter() {
        ReportStore store = new ReportStore(dir);
        assertTrue(store.claimCooldown("mc:alex", FIVE_MINUTES, NOW).isEmpty());
        assertEquals(Optional.of(NOW.plus(FIVE_MINUTES)), store.claimCooldown("mc:alex", FIVE_MINUTES,
                NOW.plusSeconds(60)));
        assertTrue(store.claimCooldown("discord:1", FIVE_MINUTES, NOW.plusSeconds(60)).isEmpty());
        assertTrue(store.claimCooldown("mc:alex", FIVE_MINUTES, NOW.plus(FIVE_MINUTES)).isEmpty());
        assertTrue(store.claimCooldown("mc:bob", Duration.ZERO, NOW).isEmpty());
        assertTrue(store.claimCooldown("mc:bob", Duration.ZERO, NOW).isEmpty());
    }

    @Test
    void numbersClosesAndKeepsReports() {
        ReportStore store = new ReportStore(dir);
        Report first = store.add(Report.open(NOW, "Alex", OptionalLong.empty(), STEVE, "Steve", "Griefing",
                Optional.empty()));
        Report second = store.add(Report.open(NOW, "Discord @anedhel", OptionalLong.of(42), STEVE, "Steve",
                "Insults", Optional.of("https://discord.com/channels/1/2/3")));
        assertEquals(1, first.id());
        assertEquals(2, second.id());
        store.setMessage(2, 10, 20);

        assertTrue(store.close(2, Status.HANDLED, "Discord @mod", Optional.of("Muted Steve for 1h")).isPresent());
        // A closed report cannot be closed again
        assertTrue(store.close(2, Status.DISMISSED, "Discord @other", Optional.empty()).isEmpty());

        ReportStore reloaded = new ReportStore(dir);
        Report kept = reloaded.get(2).orElseThrow();
        assertEquals(Status.HANDLED, kept.status());
        assertEquals(Optional.of("Muted Steve for 1h"), kept.outcome());
        assertEquals(OptionalLong.of(42), kept.reporterDiscordId());
        assertEquals(20, kept.messageId());
        assertEquals(Status.OPEN, reloaded.get(1).orElseThrow().status());
        assertEquals(3, reloaded.add(Report.open(NOW, "Alex", OptionalLong.empty(), STEVE, "Steve", "Again",
                Optional.empty())).id());
    }
}
