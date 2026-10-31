package de.moritzrohleder.minecraftdiscordlink.config;

import de.moritzrohleder.minecraftdiscordlink.Constants;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public static synchronized boolean reload() {
        ModConfig current = get();
        ModConfig loaded = ConfigLoader.load(file);

        boolean restartNeeded = !loaded.discord().token().equals(current.discord().token())
                || !loaded.chat().channelId().equals(current.chat().channelId())
                || !loaded.console().channelId().equals(current.console().channelId())
                || !loaded.server().equals(current.server());
        if (restartNeeded) {
            Constants.LOG.warn("The bot token, the channel IDs and the [server] section are only applied after a "
                    + "server restart.");
        }

        config = new ModConfig(
                current.server(),
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
                loaded.linking(),
                loaded.moderation(),
                loaded.monitoring(),
                loaded.notifications(),
                loaded.leaderboard());
        Constants.LOG.info("Config reloaded");
        return restartNeeded;
    }

    /**
     * Changes the whitelist via Discord roles, e.g. by a command: writes {@code whitelist.enabled} and
     * {@code whitelist.role_ids} into the config file and applies them. Other changes in the file are kept but not
     * applied until the next reload.
     *
     * @param whitelist the new whitelist settings
     * @return whether the config file was written; nothing is changed otherwise
     */
    public static synchronized boolean updateWhitelist(ModConfig.Whitelist whitelist) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("whitelist.enabled", whitelist.enabled());
        values.put("whitelist.role_ids", whitelist.roleIds().stream().map(String::valueOf).toList());
        if (!ConfigLoader.update(file, values)) {
            return false;
        }
        ModConfig current = get();
        config = new ModConfig(current.server(), current.discord(), current.chat(), current.console(),
                new ModConfig.Whitelist(whitelist.enabled(), List.copyOf(whitelist.roleIds())), current.linking(),
                current.moderation(), current.monitoring(), current.notifications(),
                current.leaderboard());
        return true;
    }
}
