package de.moritzrohleder.minecraftdiscordlink.text;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

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
}
