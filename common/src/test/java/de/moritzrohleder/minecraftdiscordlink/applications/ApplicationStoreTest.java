package de.moritzrohleder.minecraftdiscordlink.applications;

import de.moritzrohleder.minecraftdiscordlink.applications.ApplicationStore.Answer;
import de.moritzrohleder.minecraftdiscordlink.applications.ApplicationStore.Application;
import de.moritzrohleder.minecraftdiscordlink.applications.ApplicationStore.Refusal;
import de.moritzrohleder.minecraftdiscordlink.applications.ApplicationStore.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationStoreTest {

    private static final Instant TIME = Instant.parse("2026-11-04T15:00:00Z");
    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final long MEMBER = 332800094808178688L;
    private static final Duration WEEK = Duration.ofDays(7);

    @TempDir
    Path dir;

    @Test
    void refusesWhileOpenAndAfterADenial() {
        ApplicationStore store = new ApplicationStore(dir);
        assertTrue(store.refusal(MEMBER, WEEK, TIME).isEmpty());

        store.submit(Application.open(MEMBER, STEVE, "Steve", List.of(new Answer("Why?", "Building")), TIME));
        assertEquals(Refusal.Kind.OPEN, store.refusal(MEMBER, WEEK, TIME).orElseThrow().kind());

        Application denied = store.decide(MEMBER, false, "Discord @anedhel", Optional.of("Too young"),
                TIME.plusSeconds(60)).orElseThrow();
        assertEquals(Status.DENIED, denied.status());
        Refusal wait = store.refusal(MEMBER, WEEK, TIME.plusSeconds(120)).orElseThrow();
        assertEquals(Refusal.Kind.WAIT, wait.kind());
        assertEquals(Optional.of(TIME.plusSeconds(60).plus(WEEK)), wait.until());
        assertTrue(store.refusal(MEMBER, WEEK, TIME.plus(WEEK).plusSeconds(61)).isEmpty());
        assertTrue(store.refusal(MEMBER, Duration.ZERO, TIME.plusSeconds(61)).isEmpty());

        // A decided application cannot be decided again
        assertTrue(store.decide(MEMBER, true, "Discord @other", Optional.empty(), TIME).isEmpty());
    }

    @Test
    void keepsApplicationsAndTheButtonMessage() {
        ApplicationStore store = new ApplicationStore(dir);
        store.submit(Application.open(MEMBER, STEVE, "Steve", List.of(new Answer("Why?", "Building")), TIME));
        store.setReviewMessage(MEMBER, 11, 22);
        store.decide(MEMBER, true, "Discord @anedhel", Optional.empty(), TIME.plusSeconds(60));
        store.setButtonMessage(33, 44);

        ApplicationStore reloaded = new ApplicationStore(dir);
        Application application = reloaded.latest(MEMBER).orElseThrow();
        assertEquals(Status.ACCEPTED, application.status());
        assertEquals("Steve", application.name());
        assertEquals(STEVE, application.player());
        assertEquals(List.of(new Answer("Why?", "Building")), application.answers());
        assertEquals(Optional.of("Discord @anedhel"), application.decidedBy());
        assertEquals(22, application.reviewMessage().getAsLong());
        assertEquals(Refusal.Kind.ACCEPTED, reloaded.refusal(MEMBER, WEEK, TIME).orElseThrow().kind());
        assertArrayEquals(new long[]{33, 44}, reloaded.buttonMessage().orElseThrow());
    }
}
