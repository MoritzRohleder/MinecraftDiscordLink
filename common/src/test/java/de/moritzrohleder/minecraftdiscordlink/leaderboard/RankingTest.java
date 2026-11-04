package de.moritzrohleder.minecraftdiscordlink.leaderboard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankingTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);
    private static final UUID D = new UUID(0, 4);

    @Test
    void ranksHighestFirstWithSharedRanks() {
        Ranking ranking = Ranking.of(Map.of(A, 10L, B, 50L, C, 30L, D, 30L), false);

        assertEquals(List.of(B, C, D, A), ranking.entries().stream().map(Ranking.Entry::player).toList());
        assertEquals(List.of(1, 2, 2, 4), ranking.entries().stream().map(Ranking.Entry::rank).toList());
        assertEquals(List.of(B, C), ranking.top(2).stream().map(Ranking.Entry::player).toList());
        assertEquals(4, ranking.top(10).size());
    }

    @Test
    void ranksLowestFirst() {
        Ranking ranking = Ranking.of(Map.of(A, 3L, B, 0L, C, 0L), true);

        assertEquals(1, ranking.find(B).orElseThrow().rank());
        assertEquals(1, ranking.find(C).orElseThrow().rank());
        assertEquals(3, ranking.find(A).orElseThrow().rank());
        assertTrue(ranking.find(D).isEmpty());
    }

    @Test
    void formatsOrdinals() {
        assertEquals("1st", Ranking.ordinal(1));
        assertEquals("2nd", Ranking.ordinal(2));
        assertEquals("3rd", Ranking.ordinal(3));
        assertEquals("4th", Ranking.ordinal(4));
        assertEquals("11th", Ranking.ordinal(11));
        assertEquals("12th", Ranking.ordinal(12));
        assertEquals("13th", Ranking.ordinal(13));
        assertEquals("21st", Ranking.ordinal(21));
        assertEquals("102nd", Ranking.ordinal(102));
        assertEquals("111th", Ranking.ordinal(111));
    }

    @Test
    void formatsValues() {
        assertEquals("1,234", StatFormat.NUMBER.format(1234));
        assertEquals("12.5", StatFormat.TENTHS.format(125));
        assertEquals("0 cm", StatFormat.DISTANCE.format(0));
        assertEquals("38 cm", StatFormat.DISTANCE.format(38));
        assertEquals("1 m", StatFormat.DISTANCE.format(100));
        assertEquals("850 m", StatFormat.DISTANCE.format(85_000));
        assertEquals("12.3 km", StatFormat.DISTANCE.format(1_234_567));
        assertEquals("0s", StatFormat.TIME.format(0));
        assertEquals("42s", StatFormat.TIME.format(841));
        assertEquals("1m", StatFormat.TIME.format(60 * 20));
        assertEquals("17m", StatFormat.TIME.format(17 * 60 * 20));
        assertEquals("1d 2h 3m", StatFormat.TIME.format(((24 + 2) * 60 + 3) * 60 * 20L));
    }
}
