package de.moritzrohleder.minecraftdiscordlink.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportsTest {

    @Test
    void readsMessageLinksAndIds() {
        Reports.MessageRef link = Reports.parseMessage(
                "https://discord.com/channels/1565279725390135000/1565279725390135296/1567667159662985420", 0, 0)
                .orElseThrow();
        assertEquals(1565279725390135296L, link.channelId());
        assertEquals(1567667159662985420L, link.messageId());

        Reports.MessageRef canary = Reports.parseMessage(
                "https://canary.discord.com/channels/111111111111111111/222222222222222222/333333333333333333", 0, 0)
                .orElseThrow();
        assertEquals(333333333333333333L, canary.messageId());

        Reports.MessageRef id = Reports.parseMessage("1567667159662985420", 11, 1565279725390135296L)
                .orElseThrow();
        assertEquals(1565279725390135296L, id.channelId());
        assertEquals("https://discord.com/channels/11/1565279725390135296/1567667159662985420", id.link());

        assertTrue(Reports.parseMessage("hello", 0, 0).isEmpty());
        assertTrue(Reports.parseMessage("https://example.com/channels/1/2/3", 0, 0).isEmpty());
    }
}
