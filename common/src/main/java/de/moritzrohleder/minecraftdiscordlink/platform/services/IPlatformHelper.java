package de.moritzrohleder.minecraftdiscordlink.platform.services;

import net.minecraft.commands.CommandSourceStack;

import java.nio.file.Path;

/**
 * Loader specific functionality needed by the common code. Implemented by each loader project.
 */
public interface IPlatformHelper {

    /**
     * Gets the name of the mod loader.
     *
     * @return the name of the mod loader, e.g. {@code Fabric}
     */
    String getPlatformName();

    /**
     * Checks whether a mod is loaded.
     *
     * @param modId the ID of the mod
     * @return whether a mod with the given ID is loaded
     */
    boolean isModLoaded(String modId);

    /**
     * Checks whether the game runs in a development environment.
     *
     * @return whether the game runs in a development environment
     */
    boolean isDevelopmentEnvironment();

    /**
     * Gets the directory where config files are stored.
     *
     * @return the directory where config files are stored
     */
    Path getConfigDirectory();

    /**
     * Checks whether a command source has the admin permission of the mod: the permission node
     * {@code minecraftdiscordlink.admin} if a permissions mod is installed, otherwise OP level 4. The console always
     * has it.
     *
     * @param source the command source
     * @return whether the source is an admin
     */
    boolean isAdmin(CommandSourceStack source);
}
