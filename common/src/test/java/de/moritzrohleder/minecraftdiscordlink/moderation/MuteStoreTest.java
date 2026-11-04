package de.moritzrohleder.minecraftdiscordlink.moderation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuteStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final UUID STEVE = new UUID(0, 1);
    private static final UUID ALEX = new UUID(0, 2);

    @TempDir
    Path dir;

    @Test
    void storesAndReplacesMutes() {
        MuteStore store = new MuteStore(dir);
        MuteStore.Mute mute = new MuteStore.Mute(STEVE, "Steve", NOW.plusSeconds(600), "Spam", "Server",
                ModerationEntry.Network.ALL_SERVERS);

        assertFalse(store.put(mute));
        assertTrue(store.put(mute));

        MuteStore loaded = new MuteStore(dir);
        assertEquals(mute, loaded.get(STEVE, NOW).orElseThrow());
        assertTrue(loaded.get(STEVE, NOW.plusSeconds(600)).isEmpty());
        assertEquals(mute, loaded.remove(STEVE).orElseThrow());
        assertTrue(new MuteStore(dir).all().isEmpty());
    }

    @Test
    void removesExpiredMutes() {
        MuteStore store = new MuteStore(dir);
        MuteStore.Mute ended = new MuteStore.Mute(STEVE, "Steve", NOW.minusSeconds(1), "Spam", "Server",
                ModerationEntry.Network.LOCAL);
        MuteStore.Mute running = new MuteStore.Mute(ALEX, "Alex", NOW.plusSeconds(60), "Spam", "Server",
                ModerationEntry.Network.LOCAL);
        store.put(ended);
        store.put(running);

        assertEquals(List.of(ended), store.removeExpired(NOW));
        assertEquals(List.of(running), new MuteStore(dir).all());
    }
}
