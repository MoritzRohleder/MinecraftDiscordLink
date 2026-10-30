package de.moritzrohleder.minecraftdiscordlink.mixin;

import net.minecraft.stats.Stat;
import net.minecraft.stats.StatFormatter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Gives access to the unit of a statistic, to format leaderboard values like the vanilla statistics screen.
 */
@Mixin(Stat.class)
public interface StatAccessor {

    /**
     * Returns the formatter of the statistic.
     *
     * @return the formatter, e.g. {@link StatFormatter#TIME}
     */
    @Accessor("formatter")
    StatFormatter minecraftdiscordlink$getFormatter();
}
