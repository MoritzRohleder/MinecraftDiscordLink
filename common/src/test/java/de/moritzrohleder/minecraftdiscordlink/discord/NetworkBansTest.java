package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationAction;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkBansTest {

    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final Instant TIME = Instant.parse("2026-10-30T15:40:12Z");

    @TempDir
    Path dir;

    @Test
    void carriesBansOnAllServersInTheUrl() {
        ConfigManager.load(dir);
        ModerationEntry ban = new ModerationEntry(TIME, ModerationAction.BAN, "Steve", Optional.of(STEVE),
                "Discord @anedhel", Optional.of(TIME.plusSeconds(3600)), Optional.of("Griefing & more?"),
                ModerationEntry.Network.ALL_SERVERS);

        String url = NetworkBans.dataUrl(ban).orElseThrow();
        Map<String, String> values = NetworkBans.parse(url);

        assertTrue(url.startsWith(NetworkBans.DATA_URL + "?"));
        assertEquals("ban", values.get("action"));
        assertEquals(STEVE.toString(), values.get("uuid"));
        assertEquals("Steve", values.get("name"));
        assertEquals(String.valueOf(TIME.plusSeconds(3600).getEpochSecond()), values.get("until"));
        assertEquals("Discord @anedhel", values.get("moderator"));
        assertEquals("Griefing & more?", values.get("reason"));
    }

    @Test
    void sendsOnlyBansOnAllServersMadeHere() {
        ConfigManager.load(dir);
        ModerationEntry local = new ModerationEntry(TIME, ModerationAction.BAN, "Steve", Optional.of(STEVE),
                "Server", Optional.empty(), Optional.of("Test"));
        ModerationEntry received = new ModerationEntry(TIME, ModerationAction.UNBAN, "Steve", Optional.of(STEVE),
                "Server via Survival", Optional.empty(), Optional.of("Test"), ModerationEntry.Network.RECEIVED);

        assertTrue(NetworkBans.dataUrl(local).isEmpty());
        assertTrue(NetworkBans.dataUrl(received).isEmpty());
    }
}
