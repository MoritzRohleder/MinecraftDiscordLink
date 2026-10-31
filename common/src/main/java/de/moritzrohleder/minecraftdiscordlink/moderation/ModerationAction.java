package de.moritzrohleder.minecraftdiscordlink.moderation;

/**
 * Kinds of moderation actions in the moderation log.
 */
public enum ModerationAction {
    /** A player was kicked. */
    KICK("KICK", "Kicked"),
    /** A player was banned, permanently or temporarily. */
    BAN("BAN", "Banned"),
    /** A player's ban was lifted. */
    UNBAN("UNBAN", "Unbanned"),
    /** A temporary ban of a player or an IP address ended. */
    BAN_EXPIRED("BAN EXPIRED", "Ban expired:"),
    /** An IP address was banned. */
    IP_BAN("IP BAN", "Banned IP"),
    /** An IP address ban was lifted. */
    IP_UNBAN("IP UNBAN", "Unbanned IP"),
    /** A player was added to the vanilla whitelist. */
    WHITELIST_ADD("WHITELIST ADD", "Added to the whitelist:"),
    /** A player was removed from the vanilla whitelist. */
    WHITELIST_REMOVE("WHITELIST REMOVE", "Removed from the whitelist:"),
    /** The vanilla whitelist was switched on. */
    WHITELIST_ON("WHITELIST ON", "Whitelist switched on"),
    /** The vanilla whitelist was switched off. */
    WHITELIST_OFF("WHITELIST OFF", "Whitelist switched off"),
    /** The whitelist via Discord roles was switched on. */
    ROLE_WHITELIST_ON("ROLE WHITELIST ON", "Role whitelist switched on"),
    /** The whitelist via Discord roles was switched off. */
    ROLE_WHITELIST_OFF("ROLE WHITELIST OFF", "Role whitelist switched off"),
    /** A role was added to the whitelist via Discord roles. */
    ROLE_WHITELIST_ADD("ROLE WHITELIST ADD", "Role added to the whitelist:"),
    /** A role was removed from the whitelist via Discord roles. */
    ROLE_WHITELIST_REMOVE("ROLE WHITELIST REMOVE", "Role removed from the whitelist:"),
    /** The maintenance mode was switched on. */
    MAINTENANCE_ON("MAINTENANCE ON", "Maintenance mode switched on"),
    /** The maintenance mode was switched off. */
    MAINTENANCE_OFF("MAINTENANCE OFF", "Maintenance mode switched off");

    private final String label;
    private final String title;

    ModerationAction(String label, String title) {
        this.label = label;
        this.title = title;
    }

    /**
     * Returns the label in the moderation log file.
     *
     * @return e.g. {@code BAN}
     */
    public String label() {
        return label;
    }

    /**
     * Returns the start of a sentence describing the action, followed by its target if it has one.
     *
     * @return e.g. {@code Banned}
     */
    public String title() {
        return title;
    }

    /**
     * Checks whether the action has a target (a player, an IP address or a role).
     *
     * @return {@code false} for switching a whitelist or the maintenance mode on or off
     */
    public boolean hasTarget() {
        return this != WHITELIST_ON && this != WHITELIST_OFF && this != ROLE_WHITELIST_ON
                && this != ROLE_WHITELIST_OFF && this != MAINTENANCE_ON && this != MAINTENANCE_OFF;
    }
}
