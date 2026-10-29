package de.moritzrohleder.minecraftdiscordlink.config;

import de.moritzrohleder.minecraftdiscordlink.Constants;

import java.nio.file.Path;

/**
 * Holds the active config. The bot token and the channel IDs are only applied on server start; a reload keeps the
 * values from the start.
 */
public final class ConfigManager {

    private static Path file;
    private static ModConfig config;

    private ConfigManager() {
    }

    public static void load(Path configDirectory) {
        file = configDirectory.resolve(ConfigLoader.FILE_NAME);
        config = ConfigLoader.load(file);
    }

    public static ModConfig get() {
        if (config == null) {
            throw new IllegalStateException("Config has not been loaded yet");
        }
        return config;
    }

    /**
     * Reloads the config file. Changes to the token or the channel IDs are ignored until the next restart.
     */
    public static void reload() {
        ModConfig current = get();
        ModConfig loaded = ConfigLoader.load(file);

        if (!loaded.discord().token().equals(current.discord().token())
                || !loaded.chat().channelId().equals(current.chat().channelId())
                || !loaded.console().channelId().equals(current.console().channelId())) {
            Constants.LOG.warn("The bot token and the channel IDs are only applied after a server restart.");
        }

        config = new ModConfig(
                new ModConfig.Discord(current.discord().token(), loaded.discord().status()),
                new ModConfig.Chat(
                        current.chat().channelId(),
                        loaded.chat().webhook(),
                        loaded.chat().minecraftFormat(),
                        loaded.chat().linkedNameFormat(),
                        loaded.chat().avatarUrl(),
                        loaded.chat().discordFormat(),
                        loaded.chat().events(),
                        loaded.chat().topic()),
                new ModConfig.Console(
                        current.console().channelId(),
                        loaded.console().batchIntervalMs(),
                        loaded.console().permissions()),
                loaded.whitelist(),
                loaded.linking());
        Constants.LOG.info("Config reloaded");
    }
}
