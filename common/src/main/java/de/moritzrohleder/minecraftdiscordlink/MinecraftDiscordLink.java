package de.moritzrohleder.minecraftdiscordlink;

import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.discord.DiscordBot;
import de.moritzrohleder.minecraftdiscordlink.platform.Services;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;

/**
 * Loader independent entry point and lifecycle of the mod.
 * <p>
 * The loader specific entry points call {@link #init()} when the mod is loaded and forward the server lifecycle
 * events to the other methods of this class. All methods are called on the server thread.
 */
public final class MinecraftDiscordLink {

    /** The Discord bot, {@code null} while the server is not running or the mod is inactive. */
    private static DiscordBot bot;

    private MinecraftDiscordLink() {
    }

    /**
     * Initializes the mod: loads the config.
     */
    public static void init() {
        Constants.LOG.info("Loading {} on {}", Constants.MOD_NAME, Services.PLATFORM.getPlatformName());
        ConfigManager.load(Services.PLATFORM.getConfigDirectory());
    }

    /**
     * Called when the server is starting. Starts connecting to Discord in the background if a token is configured.
     *
     * @param server the starting server
     */
    public static void onServerStarting(MinecraftServer server) {
        ModConfig config = ConfigManager.get();
        config.discord().token().ifPresent(token -> {
            bot = new DiscordBot(token, config, server, Instant.now());
            bot.connect();
        });
    }

    /**
     * Called when the server has finished starting.
     *
     * @param server the started server
     */
    public static void onServerStarted(MinecraftServer server) {
        if (bot != null) {
            bot.onServerStarted();
        }
    }

    /**
     * Called when the server is stopping. Sends the stop message and disconnects from Discord, blocking for a few
     * seconds at most.
     *
     * @param server the stopping server
     */
    public static void onServerStopping(MinecraftServer server) {
        if (bot != null) {
            bot.shutdown();
            bot = null;
        }
    }
}
