package de.moritzrohleder.minecraftdiscordlink.leaderboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The ranking of all players by one statistic.
 * <p>
 * Players with the same value share a rank and the next rank is skipped ("standard competition ranking"): values
 * 50, 30, 30, 10 get the ranks 1, 2, 2, 4.
 *
 * @param entries the ranked players, best first; players with the same value are ordered by UUID, so the order is
 *                stable
 */
public record Ranking(List<Entry> entries) {

    /**
     * Ranks players by their values.
     *
     * @param values      the value of every player
     * @param lowestFirst whether the lowest value is ranked first instead of the highest
     * @return the ranking
     */
    public static Ranking of(Map<UUID, Long> values, boolean lowestFirst) {
        Comparator<Map.Entry<UUID, Long>> byValue = Map.Entry.comparingByValue();
        List<Map.Entry<UUID, Long>> sorted = values.entrySet().stream()
                .sorted((lowestFirst ? byValue : byValue.reversed()).thenComparing(Map.Entry.comparingByKey()))
                .toList();
        List<Entry> entries = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            long value = sorted.get(i).getValue();
            int rank = i > 0 && entries.get(i - 1).value() == value ? entries.get(i - 1).rank() : i + 1;
            entries.add(new Entry(sorted.get(i).getKey(), value, rank));
        }
        return new Ranking(List.copyOf(entries));
    }

    /**
     * Finds the entry of a player.
     *
     * @param player the player's UUID
     * @return the player's entry, empty if the player is not ranked
     */
    public Optional<Entry> find(UUID player) {
        return entries.stream().filter(entry -> entry.player().equals(player)).findFirst();
    }

    /**
     * Returns the best players.
     *
     * @param size the number of players
     * @return the first {@code size} entries
     */
    public List<Entry> top(int size) {
        return entries.subList(0, Math.min(size, entries.size()));
    }

    /**
     * Formats a rank as English ordinal number.
     *
     * @param rank the rank, at least 1
     * @return e.g. {@code 1st}, {@code 2nd}, {@code 3rd}, {@code 11th}, {@code 22nd}
     */
    public static String ordinal(int rank) {
        String suffix = switch (rank % 100) {
            case 11, 12, 13 -> "th";
            default -> switch (rank % 10) {
                case 1 -> "st";
                case 2 -> "nd";
                case 3 -> "rd";
                default -> "th";
            };
        };
        return rank + suffix;
    }

    /**
     * A ranked player.
     *
     * @param player the player's UUID
     * @param value  the player's value of the statistic
     * @param rank   the player's rank, starting at 1
     */
    public record Entry(UUID player, long value, int rank) {
    }
}
