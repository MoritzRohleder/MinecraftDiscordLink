package de.moritzrohleder.minecraftdiscordlink;

import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.platform.Services;

/**
 * Loader independent entry point. Called by the loader specific entry points.
 */
public class MinecraftDiscordLink {

    public static void init() {
        Constants.LOG.info("Loading {} on {}", Constants.MOD_NAME, Services.PLATFORM.getPlatformName());
        ConfigManager.load(Services.PLATFORM.getConfigDirectory());
    }
}
