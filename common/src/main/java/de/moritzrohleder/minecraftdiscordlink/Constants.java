package de.moritzrohleder.minecraftdiscordlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Constants shared by all parts of the mod.
 */
public final class Constants {

    /** The mod ID, also used for file and directory names. */
    public static final String MOD_ID = "minecraftdiscordlink";
    /** The display name of the mod. */
    public static final String MOD_NAME = "MinecraftDiscordLink";
    /** The logger of the mod. */
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Constants() {
    }
}
