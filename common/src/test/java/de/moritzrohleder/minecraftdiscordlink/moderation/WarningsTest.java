package de.moritzrohleder.minecraftdiscordlink.moderation;

import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.WarningAction;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.WarningRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarningsTest {

    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");
    private static final UUID STEVE = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final Duration DAY = Duration.ofDays(1);
    private static final WarningRule TWO_IN_A_WEEK = new WarningRule(2, Optional.of(Duration.ofDays(7)),
            WarningAction.MUTE, Optional.of(DAY), true);
    private static final WarningRule FIVE_ONCE = new WarningRule(5, Optional.empty(), WarningAction.MUTE,
            Optional.of(Duration.ofDays(7)), false);
    private static final WarningRule TEN_BAN = new WarningRule(10, Optional.empty(), WarningAction.BAN,
            Optional.empty(), true);

    @TempDir
    Path dir;

    @Test
    void appliesRulesWithinTheirPeriod() {
        List<WarningRule> rules = List.of(TWO_IN_A_WEEK);
        assertTrue(WarningRules.evaluate(times(NOW), NOW, rules).isEmpty());
        // The first warning is older than a week
        assertTrue(WarningRules.evaluate(times(NOW.minus(Duration.ofDays(8)), NOW), NOW, rules).isEmpty());
        assertEquals(2, WarningRules.evaluate(times(NOW.minus(DAY), NOW), NOW, rules).orElseThrow().count());
        // repeat = true: the third warning in the week punishes again
        assertEquals(3, WarningRules.evaluate(times(NOW.minus(DAY), NOW.minus(DAY), NOW), NOW, rules).orElseThrow()
                .count());
    }

    @Test
    void appliesOnceRulesOnlyWhenReached() {
        List<WarningRule> rules = List.of(FIVE_ONCE);
        assertTrue(WarningRules.evaluate(times(4), NOW, rules).isEmpty());
        assertEquals(FIVE_ONCE, WarningRules.evaluate(times(5), NOW, rules).orElseThrow().rule());
        assertTrue(WarningRules.evaluate(times(6), NOW, rules).isEmpty());
    }

    @Test
    void picksTheStrictestRule() {
        WarningRule kick = new WarningRule(1, Optional.empty(), WarningAction.KICK, Optional.empty(), true);
        WarningRule tempBan = new WarningRule(1, Optional.empty(), WarningAction.BAN, Optional.of(DAY), true);
        List<WarningRule> rules = List.of(kick, TWO_IN_A_WEEK, FIVE_ONCE, tempBan, TEN_BAN);
        // Mute 7d beats mute 1d and kick; a temporary ban beats every mute
        assertEquals(FIVE_ONCE, WarningRules.evaluate(times(5), NOW, List.of(kick, TWO_IN_A_WEEK, FIVE_ONCE))
                .orElseThrow().rule());
        assertEquals(tempBan, WarningRules.evaluate(times(5), NOW, rules).orElseThrow().rule());
        // A permanent ban beats a temporary one
        assertEquals(TEN_BAN, WarningRules.evaluate(times(10), NOW, rules).orElseThrow().rule());
    }

    @Test
    void storesStatesAndCountsReactivatedWarningsFromTheReactivation() {
        WarningStore store = new WarningStore(dir);
        WarningStore.Warning old = warning(1, NOW.minus(Duration.ofDays(100)));
        store.add(old);
        store.add(warning(2, NOW.minus(DAY)).asLifted());
        Optional<Duration> expireAfter = Optional.of(Duration.ofDays(90));

        assertEquals(WarningStore.Status.EXPIRED, store.get(1).orElseThrow().status(NOW, expireAfter));
        assertEquals(WarningStore.Status.ACTIVE, store.get(1).orElseThrow().status(NOW, Optional.empty()));
        assertEquals(WarningStore.Status.LIFTED, store.get(2).orElseThrow().status(NOW, expireAfter));

        store.replace(old.reactivatedAt(NOW));
        WarningStore reloaded = new WarningStore(dir);
        WarningStore.Warning reactivated = reloaded.get(1).orElseThrow();
        assertEquals(NOW, reactivated.countsFrom());
        assertEquals(WarningStore.Status.ACTIVE, reactivated.status(NOW, expireAfter));
        assertEquals(List.of(2L, 1L), reloaded.of(STEVE).stream().map(WarningStore.Warning::id).toList());
    }

    @Test
    void findsReceivedWarningsByTheirOrigin() {
        WarningStore store = new WarningStore(dir);
        store.add(new WarningStore.Warning(7, STEVE, "Steve", NOW, "Discord @anedhel via Survival", "Spam",
                ModerationEntry.Network.RECEIVED, false, Optional.empty(), Optional.of("Survival"), 42));
        assertEquals(7, new WarningStore(dir).received("survival", 42).orElseThrow().id());
        assertTrue(store.received("Survival", 43).isEmpty());
    }

    @Test
    void showsTheReferencedEntryInTheLog() {
        ModerationEntry unwarn = new ModerationEntry(NOW, ModerationAction.UNWARN, "Steve", Optional.of(STEVE),
                "Server", Optional.empty(), Optional.of("Mistake")).withId(12).withRef(7);
        String line = unwarn.toLogLine(ZoneOffset.UTC);
        assertEquals("2026-11-06 12:00:00 | #12 | UNWARN | Steve | #7 | by Server | Mistake", line);
        assertEquals("UNWARN #7 · by Server · Mistake", unwarn.historyText(ZoneOffset.UTC));
        assertEquals(7, ModerationHistory.parseLogLine(line, ZoneOffset.UTC).orElseThrow().ref());
    }

    private static List<Instant> times(Instant... times) {
        return List.of(times);
    }

    private static List<Instant> times(int count) {
        return java.util.Collections.nCopies(count, NOW);
    }

    private static WarningStore.Warning warning(long id, Instant time) {
        return new WarningStore.Warning(id, STEVE, "Steve", time, "Server", "Spam", ModerationEntry.Network.LOCAL,
                false, Optional.empty(), Optional.empty(), 0);
    }
}
