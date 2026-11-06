package de.moritzrohleder.minecraftdiscordlink.moderation;

import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.WarningRule;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Decides which punishment the warning rules give after a warning.
 */
public final class WarningRules {

    /** Orders rules from the mildest to the strictest punishment: by action, then by duration. */
    static final Comparator<WarningRule> STRICTNESS = Comparator.comparing(WarningRule::action)
            .thenComparing(rule -> rule.duration().orElse(Duration.ofSeconds(Long.MAX_VALUE)));

    private WarningRules() {
    }

    /**
     * Finds the strictest rule that applies after a warning.
     *
     * @param active the times of the player's active warnings, including the new one (the time a reactivated
     *               warning counts from)
     * @param now    the time of the new warning
     * @param rules  the rules
     * @return the applying rule and how many warnings it counted, empty if no rule applies
     */
    public static Optional<Match> evaluate(List<Instant> active, Instant now, List<WarningRule> rules) {
        return rules.stream()
                .map(rule -> new Match(rule, count(active, now, rule)))
                .filter(match -> match.rule().repeat() ? match.count() >= match.rule().count()
                        : match.count() == match.rule().count())
                .max(Comparator.comparing(Match::rule, STRICTNESS));
    }

    /**
     * Counts the warnings a rule looks at.
     *
     * @param active the times of the active warnings
     * @param now    the current time
     * @param rule   the rule
     * @return the number of warnings within the rule's period, or all if it has none
     */
    private static int count(List<Instant> active, Instant now, WarningRule rule) {
        return (int) active.stream()
                .filter(time -> rule.within().map(within -> time.isAfter(now.minus(within))).orElse(true))
                .count();
    }

    /**
     * A rule that applies.
     *
     * @param rule  the rule
     * @param count how many warnings it counted
     */
    public record Match(WarningRule rule, int count) {
    }
}
