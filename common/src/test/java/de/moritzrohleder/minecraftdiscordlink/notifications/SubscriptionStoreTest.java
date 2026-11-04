package de.moritzrohleder.minecraftdiscordlink.notifications;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionStoreTest {

    private static final long ANNA = 123456789012345678L;
    private static final long BEN = 223456789012345678L;
    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir
    Path dir;

    @Test
    void storesSubscriptions() throws IOException {
        SubscriptionStore store = new SubscriptionStore(dir);
        assertTrue(store.subscribe(ANNA, NotificationEvent.SERVER_START));
        assertFalse(store.subscribe(ANNA, NotificationEvent.SERVER_START));
        assertTrue(store.subscribe(ANNA, STEVE));
        assertTrue(store.subscribe(BEN, STEVE));

        SubscriptionStore loaded = new SubscriptionStore(dir);
        assertEquals(List.of(ANNA), loaded.subscribers(NotificationEvent.SERVER_START));
        assertEquals(List.of(ANNA, BEN), loaded.subscribers(STEVE));
        assertEquals(Set.of(NotificationEvent.SERVER_START), loaded.of(ANNA).events());
        assertTrue(Files.readString(dir.resolve(SubscriptionStore.FILE_NAME)).contains("\"server_start\""));
    }

    @Test
    void removesSubscriptions() {
        SubscriptionStore store = new SubscriptionStore(dir);
        store.subscribe(ANNA, STEVE);

        assertFalse(store.unsubscribe(ANNA, NotificationEvent.MAINTENANCE));
        assertTrue(store.unsubscribe(ANNA, STEVE));
        assertFalse(store.unsubscribe(ANNA, STEVE));
        assertTrue(new SubscriptionStore(dir).of(ANNA).players().isEmpty());
        assertTrue(store.subscribers(STEVE).isEmpty());
    }
}
