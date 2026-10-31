package de.moritzrohleder.minecraftdiscordlink.linking;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.discord.AccountLinking;
import de.moritzrohleder.minecraftdiscordlink.info.ServerInfo;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Placeholders;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Account linking and the whitelist via Discord roles, as seen from Minecraft.
 * <p>
 * This class owns the stored links and the pending codes and implements the linking flows of both sides:
 * <ul>
 *     <li>{@code /discord link <name>} sends a code by DM, {@code /discord confirm <code>} completes the link.</li>
 *     <li>Players kicked by the Discord whitelist get a code to enter with {@code /mc link <code>} in Discord.</li>
 *     <li>Unlinking is possible from both sides; admins can link accounts manually.</li>
 * </ul>
 * Everything that needs Discord goes through {@link AccountLinking}, which is only available while the bot is
 * connected. Methods called from Discord threads must not touch the server state directly; they schedule that on the
 * server thread.
 */
public final class Linking {

    private static final String DM_TEXT = """
            The Minecraft player **{player}** wants to link their account to yours.
            Your code: **{code}** (valid for {minutes} minutes)
            Enter it in Minecraft with `/discord confirm {code}`.
            Reply `refresh` to get a new code.
            If this wasn't you, ignore this message.""";
    private static final String KICK_NOT_LINKED = """
            You are not whitelisted on this server.
            Link your Discord account by running /mc link {code} on the Discord server.
            The code is valid for {minutes} minutes.""";
    private static final String KICK_NOT_WHITELISTED = "You are not whitelisted on this server.";
    private static final String KICK_NO_LONGER_WHITELISTED = "You are no longer whitelisted on this server.";

    private final MinecraftServer server;
    private final LinkStore links;
    private final LinkCodes codes;
    private final Supplier<Optional<AccountLinking>> discord;
    private final PlayerStats stats;

    /**
     * Creates the account linking and loads the stored links.
     *
     * @param server        the running server
     * @param dataDirectory the mod's data directory with {@code links.json}
     * @param discord       access to Discord, empty while the bot is not connected
     * @param stats         the player statistics, for "last online"
     */
    public Linking(MinecraftServer server, Path dataDirectory,
                   Supplier<Optional<AccountLinking>> discord, PlayerStats stats) {
        this.server = server;
        this.links = new LinkStore(dataDirectory);
        this.codes = new LinkCodes(Clock.systemUTC(),
                () -> Duration.ofMinutes(ConfigManager.get().linking().codeValidityMinutes()));
        this.discord = discord;
        this.stats = stats;
    }

    /**
     * Returns the stored links.
     *
     * @return the links
     */
    public LinkStore links() {
        return links;
    }

    // ---- Linking from Minecraft ----

    /**
     * Starts linking a player with a Discord user ({@code /discord link <name>}): sends a code by DM.
     *
     * @param player      the player
     * @param discordName the Discord username
     * @param reply       receives the messages for the player, called on the server thread
     */
    public void requestLink(ServerPlayer player, String discordName, Consumer<Component> reply) {
        if (links.discordId(player.getUUID()).isPresent()) {
            reply.accept(error("Your account is already linked. Use /discord unlink first."));
            return;
        }
        Optional<AccountLinking> bot = discord.get();
        if (bot.isEmpty()) {
            reply.accept(error("Discord is not available right now. Please try again later."));
            return;
        }
        OptionalLong discordId = bot.get().findMember(discordName);
        if (discordId.isEmpty()) {
            reply.accept(error("No member with the username " + discordName + " was found on the Discord server."));
            return;
        }
        if (links.player(discordId.getAsLong()).isPresent()) {
            reply.accept(error("This Discord account is already linked to another player."));
            return;
        }
        String code = codes.createDmCode(player.getUUID(), discordId.getAsLong());
        bot.get().sendDirectMessage(discordId.getAsLong(), dmText(player.getGameProfile().getName(), code),
                () -> server.execute(() -> reply.accept(success("A code was sent to " + discordName
                        + " by direct message. Enter it with /discord confirm <code>."))),
                () -> server.execute(() -> reply.accept(error("Could not send a direct message to " + discordName
                        + ". Allow direct messages from members of the Discord server and try again."))));
    }

    /**
     * Completes a link with a DM code ({@code /discord confirm <code>}).
     *
     * @param player the player
     * @param code   the code from the DM
     * @param reply  receives the message for the player
     */
    public void confirm(ServerPlayer player, String code, Consumer<Component> reply) {
        Optional<Long> discordId = codes.redeemDmCode(player.getUUID(), code);
        if (discordId.isEmpty()) {
            reply.accept(error("This code is invalid or has expired."));
            return;
        }
        if (!links.link(player.getUUID(), discordId.get())) {
            reply.accept(error("One of the accounts has been linked in the meantime."));
            return;
        }
        onLinked(player.getGameProfile().getName(), discordId.get());
        reply.accept(success("Your account is now linked to " + discordName(discordId.get()) + "."));
    }

    /**
     * Removes the link of a player ({@code /discord unlink}).
     *
     * @param player the player
     * @param reply  receives the message for the player
     */
    public void unlink(ServerPlayer player, Consumer<Component> reply) {
        OptionalLong discordId = links.unlinkPlayer(player.getUUID());
        if (discordId.isEmpty()) {
            reply.accept(error("Your account is not linked."));
            return;
        }
        reply.accept(success("Your account is no longer linked."));
        onUnlinked(player.getUUID(), discordId.getAsLong());
    }

    /**
     * Links accounts manually ({@code /discord admin link} and {@code /mcadmin link}). Looking up the player may
     * contact Mojang, so this must not run on the server thread.
     *
     * @param playerName the Minecraft name, the player may be offline
     * @param discordId  the Discord user's ID
     * @return the result for the admin
     */
    public Reply adminLink(String playerName, long discordId) {
        Optional<GameProfile> profile = server.getProfileCache().get(playerName);
        if (profile.isEmpty()) {
            return Reply.error("No Minecraft player named " + playerName + " was found.");
        }
        UUID player = profile.get().getId();
        if (links.discordId(player).isPresent()) {
            return Reply.error(profile.get().getName() + " is already linked. The link has to be removed first.");
        }
        if (!links.link(player, discordId)) {
            return Reply.error("This Discord account is already linked to another player.");
        }
        onLinked(profile.get().getName(), discordId);
        return Reply.success(profile.get().getName() + " is now linked to " + discordName(discordId) + ".");
    }

    // ---- Linking from Discord ----

    /**
     * Links a Discord user with the player a kick code was shown to ({@code /mc link <code>}).
     *
     * @param discordId the Discord user's ID
     * @param code      the code from the kick message
     * @return the result for the Discord user, with Discord Markdown
     */
    public Reply linkWithKickCode(long discordId, String code) {
        if (links.player(discordId).isPresent()) {
            return Reply.error("Your Discord account is already linked. Use `/mc unlink` first.");
        }
        Optional<LinkCodes.KickedPlayer> player = codes.redeemKickCode(code);
        if (player.isEmpty()) {
            return Reply.error("This code is invalid or has expired. Join the Minecraft server again to get a new "
                    + "code.");
        }
        if (!links.link(player.get().uuid(), discordId)) {
            return Reply.error("This Minecraft account is already linked.");
        }
        onLinked(player.get().name(), discordId);
        return Reply.success("Your Discord account is now linked to the Minecraft account **"
                + DiscordText.escape(player.get().name()) + "**. You can join the server now.");
    }

    /**
     * Checks whether a kick code was given out by this server, so this server answers {@code /mc link} when several
     * servers share the Discord server.
     *
     * @param code the code
     * @return whether the code is a valid kick code of this server
     */
    public boolean ownsKickCode(String code) {
        return codes.hasKickCode(code);
    }

    /**
     * Removes the link of a Discord user ({@code /mc unlink}).
     *
     * @param discordId the Discord user's ID
     * @return the result for the Discord user, with Discord Markdown
     */
    public Reply unlinkDiscord(long discordId) {
        Optional<UUID> player = links.unlinkDiscord(discordId);
        if (player.isEmpty()) {
            return Reply.error("Your Discord account is not linked.");
        }
        onUnlinked(player.get(), discordId);
        return Reply.success("Your Discord account is no longer linked to **"
                + DiscordText.escape(playerName(player.get())) + "**.");
    }

    /**
     * Result of a linking action, for the answer in Discord or Minecraft.
     *
     * @param success whether the action succeeded
     * @param message the message for the user
     */
    public record Reply(boolean success, String message) {

        /**
         * Creates a successful result.
         *
         * @param message the message
         * @return the result
         */
        public static Reply success(String message) {
            return new Reply(true, message);
        }

        /**
         * Creates a failed result.
         *
         * @param message the message
         * @return the result
         */
        public static Reply error(String message) {
            return new Reply(false, message);
        }
    }

    // ---- Whois ----

    /**
     * Looks up a name as Minecraft name and as Discord name ({@code whois}). Minecraft names only count if the player
     * has played on this server or is linked. If the name matches different people on both sides, all of them are
     * returned. Looking up the Minecraft name may contact Mojang, so this must not run on the server thread.
     *
     * @param name the Minecraft or Discord name
     * @return the matching accounts with their links, empty if nothing matches
     */
    public List<WhoisEntry> whois(String name) {
        List<WhoisEntry> entries = new ArrayList<>();
        Optional<AccountLinking> bot = discord.get();
        server.getProfileCache().get(name).ifPresent(profile -> {
            OptionalLong discordId = links.discordId(profile.getId());
            if (discordId.isEmpty() && !ServerInfo.hasPlayed(server, profile.getId())) {
                // Any Minecraft account exists for Mojang, but only players of this server are of interest
                return;
            }
            entries.add(new WhoisEntry(Optional.of(profile.getId()), Optional.of(profile.getName()), discordId,
                    discordId.isPresent() ? bot.flatMap(b -> b.username(discordId.getAsLong())) : Optional.empty(),
                    links.linkedAt(profile.getId()), lastOnline(profile.getId())));
        });
        bot.ifPresent(b -> {
            for (long discordId : b.findMembersByName(name)) {
                boolean known = entries.stream()
                        .anyMatch(entry -> entry.discordId().equals(OptionalLong.of(discordId)));
                if (!known) {
                    Optional<UUID> player = links.player(discordId);
                    entries.add(new WhoisEntry(player, player.map(this::playerName), OptionalLong.of(discordId),
                            b.username(discordId), player.flatMap(links::linkedAt),
                            player.map(this::lastOnline).orElse(LastOnline.UNKNOWN)));
                }
            }
        });
        return entries;
    }

    /**
     * An account found by {@link #whois(String)}, with the linked account on the other side if any.
     *
     * @param minecraftUuid   the Minecraft account's UUID, empty for an unlinked Discord user
     * @param minecraftName   the Minecraft name, empty for an unlinked Discord user
     * @param discordId       the Discord user's ID, empty for an unlinked Minecraft account
     * @param discordUsername the Discord username, empty if unknown
     * @param linkedAt        when the accounts were linked, empty if not linked or unknown
     * @param lastOnline      when the Minecraft player was last online
     */
    public record WhoisEntry(Optional<UUID> minecraftUuid, Optional<String> minecraftName, OptionalLong discordId,
                             Optional<String> discordUsername, Optional<Instant> linkedAt, LastOnline lastOnline) {

        /**
         * Checks whether the accounts are linked.
         *
         * @return whether both sides are known
         */
        public boolean linked() {
            return minecraftName.isPresent() && discordId.isPresent();
        }
    }

    /**
     * When a Minecraft player was last online.
     *
     * @param online whether the player is online now
     * @param time   when the player last left, empty if online or unknown
     */
    public record LastOnline(boolean online, Optional<Instant> time) {

        /** Last online is unknown, e.g. for an unlinked Discord user or old data. */
        public static final LastOnline UNKNOWN = new LastOnline(false, Optional.empty());
    }

    /**
     * Determines when a player was last online.
     *
     * @param player the player's UUID
     * @return online now, the time of the last leave, or unknown
     */
    private LastOnline lastOnline(UUID player) {
        if (server.getPlayerList().getPlayer(player) != null) {
            return new LastOnline(true, Optional.empty());
        }
        return new LastOnline(false, stats.lastSeen(player));
    }

    // ---- Whitelist ----

    /**
     * Checks whether a player may join, based on the Discord whitelist. Called on login on the server thread.
     * <p>
     * Without whitelisted roles nothing is checked. Otherwise a player may join who is an operator, on the vanilla
     * whitelist or linked with a member having one of the roles. If Discord is unavailable, only the vanilla
     * whitelist applies.
     *
     * @param profile the joining player
     * @return the kick message, or {@code null} if the player may join
     */
    public Component checkLogin(GameProfile profile) {
        return switch (whitelistStatus(profile)) {
            case ALLOWED -> null;
            case NOT_LINKED -> Component.literal(Placeholders.resolve(KICK_NOT_LINKED, Map.of(
                    "code", codes.kickCode(profile.getId(), profile.getName()),
                    "minutes", String.valueOf(ConfigManager.get().linking().codeValidityMinutes()))));
            case DENIED -> Component.literal(KICK_NOT_WHITELISTED);
        };
    }

    /**
     * Checks whether the Discord whitelist lets a player join, ignoring bans and the player limit.
     *
     * @param profile the player
     * @return whether the player may join
     */
    public boolean isWhitelisted(GameProfile profile) {
        return whitelistStatus(profile) == WhitelistStatus.ALLOWED;
    }

    /**
     * Kicks a player who is online but no longer whitelisted, e.g. after losing the role or unlinking. Must be called
     * on the server thread.
     *
     * @param player the player's UUID
     */
    public void recheck(UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null && !isWhitelisted(online.getGameProfile())) {
            Constants.LOG.info("Kicking {}: no longer whitelisted via Discord", online.getGameProfile().getName());
            online.connection.disconnect(Component.literal(KICK_NO_LONGER_WHITELISTED));
        }
    }

    /**
     * Rechecks all online players, e.g. after a config reload. Must be called on the server thread.
     */
    public void recheckAll() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            recheck(player.getUUID());
        }
    }

    /**
     * Determines the whitelist status of a player.
     *
     * @param profile the player
     * @return the status
     */
    private WhitelistStatus whitelistStatus(GameProfile profile) {
        ModConfig.Whitelist config = ConfigManager.get().whitelist();
        if (!config.active()) {
            return WhitelistStatus.ALLOWED;
        }
        PlayerList playerList = server.getPlayerList();
        if (playerList.getOps().get(profile) != null || playerList.getWhiteList().isWhiteListed(profile)) {
            return WhitelistStatus.ALLOWED;
        }
        Optional<AccountLinking> bot = discord.get();
        if (bot.isEmpty()) {
            return WhitelistStatus.DENIED;
        }
        OptionalLong discordId = links.discordId(profile.getId());
        if (discordId.isEmpty()) {
            return WhitelistStatus.NOT_LINKED;
        }
        Set<Long> roles = bot.get().roles(discordId.getAsLong());
        return config.roleIds().stream().anyMatch(roles::contains) ? WhitelistStatus.ALLOWED : WhitelistStatus.DENIED;
    }

    /**
     * Result of the Discord whitelist check.
     */
    private enum WhitelistStatus {
        /** The player may join. */
        ALLOWED,
        /** The player is not linked and gets a kick code. */
        NOT_LINKED,
        /** The player is linked without a whitelisted role, or Discord is unavailable. */
        DENIED
    }

    // ---- Display names ----

    /**
     * Returns the name of a player as shown in Discord: {@code linked_name_format} for linked players, otherwise the
     * Minecraft name.
     *
     * @param player the player
     * @return the name
     */
    public String displayName(ServerPlayer player) {
        String name = player.getGameProfile().getName();
        OptionalLong discordId = links.discordId(player.getUUID());
        if (discordId.isEmpty()) {
            return name;
        }
        Optional<String> discordName = discord.get().flatMap(bot -> bot.displayName(discordId.getAsLong()));
        return discordName.map(value -> linkedName(name, value)).orElse(name);
    }

    /**
     * Returns the name of a Discord user as shown in Minecraft: {@code linked_name_format} for linked users,
     * otherwise the Discord name.
     *
     * @param discordId   the Discord user's ID
     * @param discordName the user's display name in Discord
     * @return the name
     */
    public String displayName(long discordId, String discordName) {
        return links.player(discordId).map(player -> linkedName(playerName(player), discordName)).orElse(discordName);
    }

    /**
     * Applies {@code linked_name_format}.
     *
     * @param minecraftName the Minecraft name
     * @param discordName   the Discord name
     * @return the combined name
     */
    private static String linkedName(String minecraftName, String discordName) {
        return Placeholders.resolve(ConfigManager.get().chat().linkedNameFormat(),
                Map.of("mc", minecraftName, "discord", discordName));
    }

    // ---- Helpers ----

    /**
     * Updates Discord after a link was created.
     *
     * @param playerName the Minecraft name
     * @param discordId  the Discord user
     */
    private void onLinked(String playerName, long discordId) {
        Constants.LOG.info("Linked {} with Discord user {}", playerName, discordId);
        discord.get().ifPresent(bot -> bot.onLinksChanged(discordId));
    }

    /**
     * Updates Discord after a link was removed and kicks the player if only the link made them whitelisted.
     *
     * @param player    the Minecraft account
     * @param discordId the Discord user
     */
    private void onUnlinked(UUID player, long discordId) {
        Constants.LOG.info("Unlinked {} from Discord user {}", playerName(player), discordId);
        discord.get().ifPresent(bot -> bot.onLinksChanged(discordId));
        server.execute(() -> recheck(player));
    }

    /**
     * Returns the current Minecraft name of an account.
     *
     * @param player the account's UUID
     * @return the name, or the UUID if unknown
     */
    private String playerName(UUID player) {
        return server.getProfileCache().get(player).map(GameProfile::getName).orElse(player.toString());
    }

    /**
     * Returns the display name of a Discord user.
     *
     * @param discordId the user's ID
     * @return the name, or the ID if unknown
     */
    private String discordName(long discordId) {
        return discord.get().flatMap(bot -> bot.displayName(discordId)).orElse(String.valueOf(discordId));
    }

    /**
     * Builds the DM with a link code.
     *
     * @param playerName the requesting player
     * @param code       the code
     * @return the DM text
     */
    private static String dmText(String playerName, String code) {
        return Placeholders.resolve(DM_TEXT, Map.of(
                "player", playerName,
                "code", code,
                "minutes", String.valueOf(ConfigManager.get().linking().codeValidityMinutes())));
    }

    /**
     * Creates the text of a DM with a new code after the Discord user replied {@code refresh}.
     *
     * @param discordId the Discord user who replied
     * @return the DM text, empty if no code was sent to the user
     */
    public Optional<String> refreshDmCode(long discordId) {
        return codes.refreshDmCode(discordId)
                .map(refreshed -> dmText(playerName(refreshed.player()), refreshed.code()));
    }

    /**
     * Creates a success message for a player.
     *
     * @param text the message
     * @return the message in green
     */
    private static Component success(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    /**
     * Creates an error message for a player.
     *
     * @param text the message
     * @return the message in red
     */
    private static Component error(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }
}
