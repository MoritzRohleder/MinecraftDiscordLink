package de.moritzrohleder.minecraftdiscordlink.linking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkingTest {

    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID ALEX = UUID.fromString("853c80ef-3c37-49fd-aa49-938b674adae6");
    private static final long USER = 123456789012345678L;
    private static final long OTHER_USER = 223456789012345678L;

    @TempDir
    Path dir;

    @Test
    void linksOneToOneAndPersists() throws IOException {
        LinkStore links = new LinkStore(dir);

        assertTrue(links.link(STEVE, USER));
        assertFalse(links.link(STEVE, OTHER_USER));
        assertFalse(links.link(ALEX, USER));
        assertTrue(Files.readString(dir.resolve(LinkStore.FILE_NAME)).contains("\"discord_id\": \"" + USER + "\""));

        LinkStore reloaded = new LinkStore(dir);
        assertEquals(OptionalLong.of(USER), reloaded.discordId(STEVE));
        assertEquals(links.linkedAt(STEVE), reloaded.linkedAt(STEVE));
        assertTrue(reloaded.linkedAt(STEVE).isPresent());
        assertEquals(Optional.of(STEVE), reloaded.player(USER));

        assertEquals(Optional.of(STEVE), reloaded.unlinkDiscord(USER));
        assertTrue(reloaded.discordId(STEVE).isEmpty());
        assertTrue(new LinkStore(dir).player(USER).isEmpty());
    }

    @Test
    void redeemsDmCodesOnlyForTheRequestingPlayer() {
        LinkCodes codes = new LinkCodes(Clock.systemUTC(), () -> Duration.ofHours(1));
        String code = codes.createDmCode(STEVE, USER);

        assertEquals(6, code.length());
        assertTrue(codes.redeemDmCode(ALEX, code).isEmpty());
        assertEquals(Optional.of(USER), codes.redeemDmCode(STEVE, code.toLowerCase()));
        assertTrue(codes.redeemDmCode(STEVE, code).isEmpty());
    }

    @Test
    void refreshReplacesTheDmCode() {
        LinkCodes codes = new LinkCodes(Clock.systemUTC(), () -> Duration.ofHours(1));
        String code = codes.createDmCode(STEVE, USER);

        LinkCodes.RefreshedCode refreshedCode = codes.refreshDmCode(USER).orElseThrow();
        String refreshed = refreshedCode.code();

        assertEquals(STEVE, refreshedCode.player());
        assertNotEquals(code, refreshed);
        assertTrue(codes.redeemDmCode(STEVE, code).isEmpty());
        assertEquals(Optional.of(USER), codes.redeemDmCode(STEVE, refreshed));
        assertTrue(codes.refreshDmCode(OTHER_USER).isEmpty());
    }

    @Test
    void reusesKickCodesUntilTheyExpire() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-29T12:00:00Z"));
        LinkCodes codes = new LinkCodes(clock, () -> Duration.ofMinutes(60));
        String code = codes.kickCode(STEVE, "Steve");

        assertEquals(code, codes.kickCode(STEVE, "Steve"));
        clock.advance(Duration.ofMinutes(61));
        String newCode = codes.kickCode(STEVE, "Steve");
        assertNotEquals(code, newCode);
        assertTrue(codes.redeemKickCode(code).isEmpty());
        assertEquals(Optional.of(new LinkCodes.KickedPlayer(STEVE, "Steve")), codes.redeemKickCode(newCode));
    }

    /**
     * A clock that can be moved forward in tests.
     */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
