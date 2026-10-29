package de.moritzrohleder.minecraftdiscordlink.config;

import de.moritzrohleder.minecraftdiscordlink.Constants;

import java.nio.file.Path;

/**
 * Holds the active config of the mod.
 * <p>
 * The bot token and the channel IDs are only applied on server start. A {@link #reload()} applies every other change
 * but keeps the token and channel IDs from the start.
 */
public final class ConfigManager {

    /** The config file, set by {@link #load(Path)}. */
    private static Path file;
    /** The active config, set by {@link #load(Path)}. */
    private static volatile ModConfig config;

    private ConfigManager() {
    }

    /**
     * Loads the config file on mod initialization, creating or completing it if needed.
     *
     * @param configDirectory the directory containing the config file
     */
    public static void load(Path configDirectory) {
        file = configDirectory.resolve(ConfigLoader.FILE_NAME);
        config = ConfigLoader.load(file);
    }

    /**
     * Returns the active config.
     *
     * @return the active config
     * @throws IllegalStateException if the config has not been loaded yet
     */
    public static ModConfig get() {
        if (config == null) {
            throw new IllegalStateException("Config has not been loaded yet");
        }
        return config;
    }

    /**
     * Reloads the config file. Changes to the token or the channel IDs are logged and ignored until the next
     * restart.
     *
     * @return whether the token or a channel ID changed, which needs a restart
     */
    public static boolean reload() {
        ModConfig current = get();
        ModConfig loaded = ConfigLoader.load(file);

        boolean restartNeeded = !loaded.discord().token().equals(current.discord().token())
                || !loaded.chat().channelId().equals(current.chat().channelId())
                || !loaded.console().channelId().equals(current.console().channelId());
        if (restartNeeded) {
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
        return restartNeeded;
    }
}
