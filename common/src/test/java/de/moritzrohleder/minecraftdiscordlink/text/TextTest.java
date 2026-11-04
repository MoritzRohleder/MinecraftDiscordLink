package de.moritzrohleder.minecraftdiscordlink.text;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextTest {

    @Test
    void resolvesKnownPlaceholdersAndKeepsUnknownOnes() {
        String text = Placeholders.resolve("{online}/{max} online, {typo} $1 \\",
                Map.of("online", "3", "max", "20"));

        assertEquals("3/20 online, {typo} $1 \\", text);
    }

    @Test
    void formatsDurations() {
        assertEquals("0m", Durations.format(Duration.ofSeconds(59)));
        assertEquals("15m", Durations.format(Duration.ofMinutes(15)));
        assertEquals("3h 0m", Durations.format(Duration.ofHours(3)));
        assertEquals("2d 3h 15m", Durations.format(Duration.ofDays(2).plusHours(3).plusMinutes(15)));
        assertEquals("0m", Durations.format(Duration.ofMinutes(-5)));
    }

    @Test
    void formatsCompactDurations() {
        assertEquals("7d", Durations.formatCompact(Duration.ofDays(7)));
        assertEquals("17d", Durations.formatCompact(Duration.ofDays(17)));
        assertEquals("1d 12h", Durations.formatCompact(Duration.ofHours(36)));
        assertEquals("30m", Durations.formatCompact(Duration.ofMinutes(30)));
        assertEquals("0m", Durations.formatCompact(Duration.ofSeconds(20)));
    }

    @Test
    void parsesTypedDurations() {
        assertEquals(Optional.of(Duration.ofMinutes(30)), Durations.parse("30m"));
        assertEquals(Optional.of(Duration.ofHours(12)), Durations.parse("12h"));
        assertEquals(Optional.of(Duration.ofDays(7)), Durations.parse("7D"));
        assertEquals(Optional.of(Duration.ofDays(14)), Durations.parse("2w"));
        assertEquals(Optional.of(Duration.ofHours(36)), Durations.parse("1d12h"));
        assertEquals(Optional.empty(), Durations.parse(""));
        assertEquals(Optional.empty(), Durations.parse("7"));
        assertEquals(Optional.empty(), Durations.parse("7 days"));
        assertEquals(Optional.empty(), Durations.parse("0m"));
        assertEquals(Optional.empty(), Durations.parse("99999999999999999999d"));
        assertEquals(Optional.empty(), Durations.parse("200000w"));
    }
}
