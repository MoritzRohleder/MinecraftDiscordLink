package de.moritzrohleder.minecraftdiscordlink.discord;

import java.awt.Color;

/**
 * Colors of the embed bars used by the mod, matching Discord's own palette.
 */
public final class EmbedColors {

    /** Success, server start, players joining, good server performance. */
    public static final Color GREEN = new Color(0x57F287);
    /** Errors, server stop, players leaving, bad server performance. */
    public static final Color RED = new Color(0xED4245);
    /** Medium server performance. */
    public static final Color YELLOW = new Color(0xFEE75C);
    /** Advancements. */
    public static final Color GOLD = new Color(0xF1C40F);
    /** Neutral information, e.g. an account that is not linked. */
    public static final Color GRAY = new Color(0x99AAB5);
    /** Whitelist changes in the moderation log. */
    public static final Color BLUE = new Color(0x3498DB);
    /** Informational answers. */
    public static final Color BLURPLE = new Color(0x5865F2);
    /** Deaths. Almost black: Discord shows an embed with color 0 without a color bar. */
    public static final Color BLACK = new Color(0x010101);

    private EmbedColors() {
    }
}
