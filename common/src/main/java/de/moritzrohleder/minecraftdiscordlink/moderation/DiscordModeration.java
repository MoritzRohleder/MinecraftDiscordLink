package de.moritzrohleder.minecraftdiscordlink.moderation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The Discord side of the moderation: the ban sync and the names of roles. Implemented by the Discord bot.
 */
public interface DiscordModeration {

    /**
     * Called when a player was banned in Minecraft, not by the ban sync itself. Bans or kicks the linked Discord
     * account if the ban sync is enabled.
     *
     * @param player         the player's UUID
     * @param reason         the reason of the ban
     * @param deleteMessages whether the Discord messages of the last 7 days are deleted as well
     */
    void onMinecraftBan(UUID player, String reason, boolean deleteMessages);

    /**
     * Called when a player's ban was lifted or expired in Minecraft, not by the ban sync itself. Lifts the Discord
     * ban of the linked account if the ban sync is enabled.
     *
     * @param player the player's UUID
     */
    void onMinecraftUnban(UUID player);

    /**
     * Called when a player was muted in Minecraft, not by the sync itself. Times out the linked Discord account if
     * the mute sync is enabled.
     *
     * @param player the player's UUID
     * @param until  when the mute ends
     * @param reason the reason
     */
    void onMinecraftMute(UUID player, Instant until, String reason);

    /**
     * Called when a player's mute was lifted in Minecraft, not by the sync itself. Removes the timeout of the linked
     * Discord account if the mute sync is enabled.
     *
     * @param player the player's UUID
     */
    void onMinecraftUnmute(UUID player);

    /**
     * Checks whether bans on all servers can be sent to the other servers: this server has a name, the moderation
     * channel is available and the bot is connected.
     *
     * @return whether bans on all servers are possible
     */
    boolean networkReady();

    /**
     * Looks up the username of a member of the Discord server.
     *
     * @param discordId the user ID
     * @return the username, empty if the user is not a member
     */
    Optional<String> username(long discordId);

    /**
     * Looks up the name of a role of the Discord server.
     *
     * @param roleId the role ID
     * @return the role name, empty if there is no such role
     */
    Optional<String> roleName(long roleId);
}
