package de.moritzrohleder.minecraftdiscordlink.info;

import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;

import java.util.List;
import java.util.Optional;

/**
 * Overview of the features of the mod and whether they are switched on, for {@code /mcadmin features} and
 * {@code /discord features}.
 */
public final class Features {

    private Features() {
    }

    /**
     * Lists the features and their parts as configured.
     *
     * @param config the config
     * @return the features in the order of the config file
     */
    public static List<Feature> of(ModConfig config) {
        ModConfig.Chat chat = config.chat();
        ModConfig.Events events = chat.events();
        ModConfig.Monitoring monitoring = config.monitoring();
        ModConfig.Moderation moderation = config.moderation();
        return List.of(
                new Feature("Chat bridge", chat.enabled(), noChannel(chat.channelId().isEmpty()), List.of(
                        part("Join messages", events.join()),
                        part("Leave messages", events.leave()),
                        part("Death messages", events.death()),
                        part("Advancement messages", events.advancement()),
                        part("Server start message", events.serverStart()),
                        part("Server stop message", events.serverStop()),
                        part("Channel topic", chat.topic().enabled()),
                        part("Mention suggestions", chat.mentionSuggestions()))),
                new Feature("Bot status", config.discord().status().enabled(), Optional.empty(), List.of()),
                new Feature("Console channel", config.console().enabled(),
                        noChannel(config.console().channelId().isEmpty()), List.of()),
                new Feature("Account linking", config.linking().enabled(), Optional.empty(), List.of(
                        part("Linked role", config.linking().linkedRole().mode() != ModConfig.LinkedRoleMode.NONE))),
                new Feature("Whitelist roles", config.whitelist().enabled(), config.whitelist().roleIds().isEmpty()
                        ? Optional.of("no roles configured") : Optional.empty(), List.of()),
                new Feature("Moderation commands", moderation.enabled(), Optional.empty(), List.of(
                        part("Moderation channel", moderation.channelId().isPresent()),
                        part("Ban sync", moderation.banSync().enabled()))),
                new Feature("Maintenance mode", config.maintenance().enabled(), Optional.empty(), List.of()),
                new Feature("Information commands", config.info().enabled(), Optional.empty(), List.of()),
                new Feature("Leaderboards", config.leaderboard().enabled(), config.leaderboard().categories()
                        .isEmpty() ? Optional.of("no categories configured") : Optional.empty(), List.of()),
                new Feature("Notifications", config.notifications().enabled(), Optional.empty(), List.of()),
                new Feature("Monitoring channel", monitoring.enabled(), noChannel(monitoring.channelId().isEmpty()),
                        List.of(
                                part("Lag warnings", monitoring.lag().enabled()),
                                part("Crash notices", monitoring.crash().enabled()),
                                part("Daily report", monitoring.dailyReport().enabled()),
                                part("Status message", monitoring.statusMessage().enabled()),
                                part("Pinned status message", monitoring.statusMessage().pin()))));
    }

    /**
     * Creates a part of a feature.
     *
     * @param name    the name
     * @param enabled whether it is switched on
     * @return the part
     */
    private static Feature part(String name, boolean enabled) {
        return new Feature(name, enabled, Optional.empty(), List.of());
    }

    /**
     * Returns the note of a feature without its channel.
     *
     * @param missing whether the channel ID is missing
     * @return {@code no channel configured}, or empty
     */
    private static Optional<String> noChannel(boolean missing) {
        return missing ? Optional.of("no channel configured") : Optional.empty();
    }

    /**
     * A feature or a part of one.
     *
     * @param name    the name shown
     * @param enabled whether it is switched on in the config
     * @param note    why a switched on feature may still be inactive, e.g. {@code no channel configured}
     * @param parts   the parts, which only work while the feature is switched on
     */
    public record Feature(String name, boolean enabled, Optional<String> note, List<Feature> parts) {
    }
}
