package de.moritzrohleder.minecraftdiscordlink.config;

import de.moritzrohleder.minecraftdiscordlink.Constants;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the active config of the mod.
 * <p>
 * The bot token and the {@code [server]} section are only applied on server start. A {@link #reload()} applies every
 * other change but keeps them from the start.
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
     * Reloads the config file. Changes to the token or the {@code [server]} section are logged and ignored until the
     * next restart.
     *
     * @return whether the token or the {@code [server]} section changed, which needs a restart
     */
    public static synchronized boolean reload() {
        ModConfig current = get();
        ModConfig loaded = ConfigLoader.load(file);

        boolean restartNeeded = !loaded.discord().token().equals(current.discord().token())
                || !loaded.server().equals(current.server());
        if (restartNeeded) {
            Constants.LOG.warn("The bot token and the [server] section are only applied after a server restart.");
        }

        config = new ModConfig(
                current.server(),
                new ModConfig.Discord(current.discord().token(), loaded.discord().status()),
                loaded.chat(),
                loaded.console(),
                loaded.whitelist(),
                loaded.linking(),
                loaded.moderation(),
                loaded.maintenance(),
                loaded.info(),
                loaded.monitoring(),
                loaded.notifications(),
                loaded.leaderboard());
        Constants.LOG.info("Config reloaded");
        return restartNeeded;
    }

    /**
     * Changes the whitelisted Discord roles, e.g. by a command: writes {@code whitelist.role_ids} into the config file
     * and applies it. Other changes in the file are kept but not applied until the next reload.
     *
     * @param whitelist the new whitelist settings; only the roles are written
     * @return whether the config file was written; nothing is changed otherwise
     */
    public static synchronized boolean updateWhitelist(ModConfig.Whitelist whitelist) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("whitelist.role_ids", whitelist.roleIds().stream().map(String::valueOf).toList());
        if (!ConfigLoader.update(file, values)) {
            return false;
        }
        ModConfig current = get();
        config = new ModConfig(current.server(), current.discord(), current.chat(), current.console(),
                new ModConfig.Whitelist(whitelist.enabled(), List.copyOf(whitelist.roleIds())), current.linking(),
                current.moderation(), current.maintenance(), current.info(), current.monitoring(),
                current.notifications(), current.leaderboard());
        return true;
    }
}
