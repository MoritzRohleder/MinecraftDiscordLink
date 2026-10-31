package de.moritzrohleder.minecraftdiscordlink.moderation;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking.Reply;
import de.moritzrohleder.minecraftdiscordlink.maintenance.Maintenance;
import de.moritzrohleder.minecraftdiscordlink.mixin.StoredUserEntryAccessor;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext.Scope;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.BanListEntry;
import net.minecraft.server.players.IpBanList;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.world.level.GameRules;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The moderation of the server: kicks, bans and whitelist changes from the mod's commands, and the moderation log of
 * all such changes, whoever made them.
 * <p>
 * Bans and whitelist entries are the vanilla ones. Hooks on the vanilla lists report every change to
 * {@link #onAdded}, {@link #onRemoved} and {@link #onWhitelistSwitched}, which log it and start the ban sync. The
 * moderator and reason of a change come from the entry itself (bans) or from the {@link ModerationContext}.
 * <p>
 * All methods must be called on the server thread.
 */
public final class Moderation {

    /** Prefix of moderators from Discord, e.g. {@code Discord @anedhel}. */
    public static final String DISCORD_PREFIX = "Discord ";
    /** Answer when the config file could not be written. */
    private static final String CONFIG_ERROR =
            "The config file could not be changed. See the server log for details.";
    /** Reason of expired temporary bans in the moderation log. */
    private static final String EXPIRED_REASON = "Temporary ban expired";
    /** Shown as moderator when nobody is known, e.g. for changes by other mods. */
    private static final String UNKNOWN = "unknown";

    private final MinecraftServer server;
    private final ModerationLog log;
    private final Linking linking;
    private final Supplier<Optional<DiscordModeration>> discord;

    /**
     * Creates the moderation.
     *
     * @param server  the running server
     * @param log     the moderation log
     * @param linking the account linking, for the whitelist via Discord roles
     * @param discord the Discord side, empty while the bot is not ready
     */
    public Moderation(MinecraftServer server, ModerationLog log, Linking linking,
                      Supplier<Optional<DiscordModeration>> discord) {
        this.server = server;
        this.log = log;
        this.linking = linking;
        this.discord = discord;
    }

    /**
     * Returns the moderation log.
     *
     * @return the moderation log
     */
    public ModerationLog log() {
        return log;
    }

    // ---- Actions ----

    /**
     * Kicks an online player.
     *
     * @param name   the player's name
     * @param reason the reason, shown to the player
     * @param scope  the moderator
     * @return the result
     */
    public Reply kick(String name, String reason, Scope scope) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player == null) {
            return Reply.error(name + " is not online.");
        }
        player.connection.disconnect(Component.literal(reason));
        record(ModerationAction.KICK, player.getGameProfile().getName(), Optional.of(player.getUUID()),
                scope.moderator(), Optional.empty(), Optional.of(reason));
        announce(scope, Component.translatable("commands.kick.success", player.getGameProfile().getName(), reason));
        return Reply.success("Kicked " + player.getGameProfile().getName() + ": " + reason);
    }

    /**
     * Bans a player. A player who is already banned gets the new ban instead, with the duration counted from now. An
     * online player is kicked with the vanilla ban message including the reason.
     *
     * @param profile  the player
     * @param reason   the reason
     * @param duration the duration of a temporary ban, empty for a permanent ban
     * @param scope    the moderator and the ban options
     * @return the result
     */
    public Reply ban(GameProfile profile, String reason, Optional<Duration> duration, Scope scope) {
        UserBanList bans = server.getPlayerList().getBans();
        boolean update = bans.isBanned(profile);
        Optional<Instant> until = duration.map(length -> Instant.now().plus(length).truncatedTo(ChronoUnit.SECONDS));
        UserBanListEntry ban = new UserBanListEntry(profile, null, scope.moderator(),
                until.map(Date::from).orElse(null), reason);
        ModerationContext.run(scope, () -> bans.add(ban));
        ServerPlayer online = server.getPlayerList().getPlayer(profile.getId());
        if (online != null) {
            online.connection.disconnect(banMessage(ban));
        }
        announce(scope, Component.translatable("commands.ban.success", profile.getName(), reason));
        String length = duration.map(value -> " for " + Durations.formatCompact(value)).orElse(" permanently");
        return Reply.success((update ? "Updated the ban of " + profile.getName() + ": now" + length
                : "Banned " + profile.getName() + length) + ": " + reason
                + (scope.allServers() ? " (on all servers)" : ""));
    }

    /**
     * Builds the message a banned player sees, as vanilla shows it on login: the reason and, for a temporary ban,
     * its end.
     *
     * @param ban the ban
     * @return the message
     */
    public static Component banMessage(UserBanListEntry ban) {
        MutableComponent message = Component.translatable("multiplayer.disconnect.banned.reason", ban.getReason());
        if (ban.getExpires() != null) {
            message.append(Component.translatable("multiplayer.disconnect.banned.expiration",
                    new SimpleDateFormat("yyyy-MM-dd 'at' HH:mm:ss z").format(ban.getExpires())));
        }
        return message;
    }

    /**
     * Lifts the ban of a player.
     *
     * @param profile the player
     * @param reason  the reason
     * @param scope   the moderator
     * @return the result
     */
    public Reply unban(GameProfile profile, String reason, Scope scope) {
        UserBanList bans = server.getPlayerList().getBans();
        if (!bans.isBanned(profile)) {
            return Reply.error(profile.getName() + " is not banned.");
        }
        ModerationContext.run(scope, () -> bans.remove(profile));
        announce(scope, Component.translatable("commands.pardon.success", profile.getName()));
        return Reply.success("Unbanned " + profile.getName() + ": " + reason);
    }

    /**
     * Adds a player to the vanilla whitelist, also while it is switched off.
     *
     * @param profile the player
     * @param scope   the moderator and the optional reason
     * @return the result, mentioning if the whitelist is off
     */
    public Reply whitelistAdd(GameProfile profile, Scope scope) {
        PlayerList players = server.getPlayerList();
        if (players.getWhiteList().isWhiteListed(profile)) {
            return Reply.error(profile.getName() + " is already whitelisted.");
        }
        ModerationContext.run(scope, () -> players.getWhiteList().add(new UserWhiteListEntry(profile)));
        announce(scope, Component.translatable("commands.whitelist.add.success", profile.getName()));
        return Reply.success("Added " + profile.getName() + " to the whitelist."
                + (players.isUsingWhitelist() ? "" : " Note: the whitelist is off."));
    }

    /**
     * Removes a player from the vanilla whitelist. Online players who are no longer whitelisted are kicked if the
     * server enforces the whitelist.
     *
     * @param profile the player
     * @param scope   the moderator and the optional reason
     * @return the result
     */
    public Reply whitelistRemove(GameProfile profile, Scope scope) {
        PlayerList players = server.getPlayerList();
        if (!players.getWhiteList().isWhiteListed(profile)) {
            return Reply.error(profile.getName() + " is not whitelisted.");
        }
        ModerationContext.run(scope, () -> players.getWhiteList().remove(profile));
        announce(scope, Component.translatable("commands.whitelist.remove.success", profile.getName()));
        server.kickUnlistedPlayers(server.createCommandSourceStack());
        return Reply.success("Removed " + profile.getName() + " from the whitelist.");
    }

    /**
     * Switches the vanilla whitelist on or off. Switching it on kicks online players who are not whitelisted if the
     * server enforces the whitelist.
     *
     * @param on    whether to switch it on
     * @param scope the moderator and the reason
     * @return the result
     */
    public Reply whitelistSwitch(boolean on, Scope scope) {
        PlayerList players = server.getPlayerList();
        if (players.isUsingWhitelist() == on) {
            return Reply.error("The whitelist is already " + (on ? "on." : "off."));
        }
        ModerationContext.run(scope, () -> players.setUsingWhiteList(on));
        announce(scope, Component.translatable(on ? "commands.whitelist.enabled" : "commands.whitelist.disabled"));
        if (on) {
            server.kickUnlistedPlayers(server.createCommandSourceStack());
        }
        return Reply.success("The whitelist is now " + (on ? "on." : "off."));
    }

    /**
     * Switches the whitelist via Discord roles on or off by writing the config file.
     *
     * @param on    whether to switch it on
     * @param scope the moderator and the reason
     * @return the result
     */
    public Reply roleWhitelistSwitch(boolean on, Scope scope) {
        ModConfig.Whitelist current = ConfigManager.get().whitelist();
        if (on && current.roleIds().isEmpty()) {
            return Reply.error("No roles are configured for the role whitelist. Add one first.");
        }
        if (current.enabled() == on) {
            return Reply.error("The role whitelist is already " + (on ? "on." : "off."));
        }
        if (!ConfigManager.updateWhitelist(new ModConfig.Whitelist(on, current.roleIds()))) {
            return Reply.error(CONFIG_ERROR);
        }
        record(on ? ModerationAction.ROLE_WHITELIST_ON : ModerationAction.ROLE_WHITELIST_OFF, "", Optional.empty(),
                scope.moderator(), Optional.empty(), scope.reason());
        if (on) {
            linking.recheckAll();
        }
        return Reply.success("The role whitelist is now " + (on ? "on." : "off."));
    }

    /**
     * Adds a role to the whitelist via Discord roles by writing the config file.
     *
     * @param roleId the role
     * @param scope  the moderator and the optional reason
     * @return the result
     */
    public Reply roleWhitelistAdd(long roleId, Scope scope) {
        ModConfig.Whitelist current = ConfigManager.get().whitelist();
        if (current.roleIds().contains(roleId)) {
            return Reply.error("The role " + roleLabel(roleId) + " is already whitelisted.");
        }
        List<Long> roles = new ArrayList<>(current.roleIds());
        roles.add(roleId);
        if (!ConfigManager.updateWhitelist(new ModConfig.Whitelist(current.enabled(), roles))) {
            return Reply.error(CONFIG_ERROR);
        }
        record(ModerationAction.ROLE_WHITELIST_ADD, roleLabel(roleId), Optional.empty(), scope.moderator(),
                Optional.empty(), scope.reason());
        return Reply.success("Added the role " + roleLabel(roleId) + " to the role whitelist."
                + (current.enabled() ? "" : " Note: the role whitelist is off."));
    }

    /**
     * Removes a role from the whitelist via Discord roles by writing the config file. Online players who lose
     * access are kicked.
     *
     * @param roleId the role
     * @param scope  the moderator and the optional reason
     * @return the result
     */
    public Reply roleWhitelistRemove(long roleId, Scope scope) {
        ModConfig.Whitelist current = ConfigManager.get().whitelist();
        if (!current.roleIds().contains(roleId)) {
            return Reply.error("The role " + roleLabel(roleId) + " is not whitelisted.");
        }
        List<Long> roles = new ArrayList<>(current.roleIds());
        roles.remove(roleId);
        if (!ConfigManager.updateWhitelist(new ModConfig.Whitelist(current.enabled(), roles))) {
            return Reply.error(CONFIG_ERROR);
        }
        record(ModerationAction.ROLE_WHITELIST_REMOVE, roleLabel(roleId), Optional.empty(), scope.moderator(),
                Optional.empty(), scope.reason());
        linking.recheckAll();
        return Reply.success("Removed the role " + roleLabel(roleId) + " from the role whitelist."
                + (roles.isEmpty() && current.enabled() ? " No roles are left, so the role whitelist is inactive."
                : ""));
    }

    /**
     * Lists the banned players.
     *
     * @return the bans, sorted by name
     */
    public List<Ban> bans() {
        return server.getPlayerList().getBans().getEntries().stream()
                .map(entry -> {
                    GameProfile profile =
                            (GameProfile) ((StoredUserEntryAccessor) entry).minecraftdiscordlink$getUser();
                    return new Ban(profile == null ? UNKNOWN : profile.getName(), entry.getSource(),
                            Optional.ofNullable(entry.getExpires()).map(Date::toInstant), entry.getReason());
                })
                .sorted(Comparator.comparing(Ban::player, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Lists the players on the vanilla whitelist.
     *
     * @return the names, sorted
     */
    public List<String> whitelisted() {
        return List.of(server.getPlayerList().getWhiteList().getUserList()).stream()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /**
     * Checks whether the vanilla whitelist is on.
     *
     * @return whether the vanilla whitelist is on
     */
    public boolean whitelistOn() {
        return server.getPlayerList().isUsingWhitelist();
    }

    /**
     * Describes a role for answers and the log: its name and ID.
     *
     * @param roleId the role ID
     * @return e.g. {@code @Members (123456789012345678)}
     */
    public String roleLabel(long roleId) {
        return discord.get().flatMap(bot -> bot.roleName(roleId)).map(name -> "@" + name + " (" + roleId + ")")
                .orElse(String.valueOf(roleId));
    }

    /**
     * Removes expired temporary bans of players and IP addresses. Called regularly, on server start and before
     * login checks, as Minecraft itself only removes them silently.
     */
    public void removeExpiredBans() {
        removeExpired(server.getPlayerList().getBans());
        removeExpired(server.getPlayerList().getIpBans());
    }

    /**
     * Removes the expired entries of a ban list. The removal is logged by {@link #onRemoved}.
     *
     * @param list the ban list
     * @param <K>  the type of the banned users
     * @param <V>  the type of the entries
     */
    @SuppressWarnings("unchecked")
    private <K, V extends BanListEntry<K>> void removeExpired(StoredUserList<K, V> list) {
        Date now = new Date();
        List<K> expired = list.getEntries().stream()
                .filter(entry -> entry.getExpires() != null && entry.getExpires().before(now))
                .map(entry -> (K) ((StoredUserEntryAccessor) entry).minecraftdiscordlink$getUser())
                .filter(user -> user != null)
                .toList();
        expired.forEach(list::remove);
    }

    /**
     * Switches the maintenance mode and logs it.
     *
     * @param maintenance the maintenance mode
     * @param on          whether to switch it on
     * @param persistent  whether it stays on after a restart, only used when switching it on
     * @param scope       the moderator and the optional reason
     * @return the result
     */
    public Reply maintenance(Maintenance maintenance, boolean on, boolean persistent, Scope scope) {
        Reply reply = on ? maintenance.enable(persistent, scope) : maintenance.disable();
        if (reply.success()) {
            record(on ? ModerationAction.MAINTENANCE_ON : ModerationAction.MAINTENANCE_OFF, "", Optional.empty(),
                    scope.moderator(), Optional.empty(), scope.reason());
        }
        return reply;
    }

    // ---- Hooks ----

    /**
     * Called by a hook when an entry is added to a vanilla list: logs bans, IP bans and whitelist additions and
     * starts the ban sync.
     *
     * @param list     the list
     * @param entry    the new entry
     * @param replaces whether the entry replaces one of the same user, e.g. an updated ban
     */
    public void onAdded(StoredUserList<?, ?> list, StoredUserEntry<?> entry, boolean replaces) {
        Object user = ((StoredUserEntryAccessor) entry).minecraftdiscordlink$getUser();
        Optional<Scope> scope = ModerationContext.current();
        if (list instanceof UserBanList && entry instanceof UserBanListEntry ban
                && user instanceof GameProfile profile) {
            record(replaces ? ModerationAction.BAN_UPDATED : ModerationAction.BAN, profile.getName(),
                    Optional.of(profile.getId()), moderator(scope, ban),
                    Optional.ofNullable(ban.getExpires()).map(Date::toInstant), reason(ban.getReason()));
            if (scope.map(Scope::fromSync).orElse(false)) {
                return;
            }
            boolean deleteMessages = scope.map(Scope::deleteMessages).orElse(false);
            if (!replaces) {
                discord.get().ifPresent(bot -> bot.onMinecraftBan(profile.getId(), ban.getReason(),
                        deleteMessages));
            }
        } else if (list instanceof IpBanList && entry instanceof BanListEntry<?> ban && user instanceof String ip) {
            record(ModerationAction.IP_BAN, ip, Optional.empty(), moderator(scope, ban),
                    Optional.ofNullable(ban.getExpires()).map(Date::toInstant), reason(ban.getReason()));
        } else if (list instanceof UserWhiteList && user instanceof GameProfile profile) {
            record(ModerationAction.WHITELIST_ADD, profile.getName(), Optional.of(profile.getId()),
                    moderator(scope), Optional.empty(), scope.flatMap(Scope::reason));
        }
    }

    /**
     * Called by a hook before an entry is removed from a vanilla list: logs unbans, expired bans and whitelist
     * removals and starts the ban sync.
     *
     * @param list  the list
     * @param entry the entry being removed
     */
    public void onRemoved(StoredUserList<?, ?> list, StoredUserEntry<?> entry) {
        Object user = ((StoredUserEntryAccessor) entry).minecraftdiscordlink$getUser();
        Optional<Scope> scope = ModerationContext.current();
        boolean expired = entry instanceof BanListEntry<?> ban && ban.getExpires() != null
                && ban.getExpires().before(new Date());
        if (list instanceof UserBanList && user instanceof GameProfile profile) {
            if (expired) {
                record(ModerationAction.BAN_EXPIRED, profile.getName(), Optional.of(profile.getId()),
                        Constants.MOD_NAME, Optional.empty(), Optional.of(EXPIRED_REASON));
            } else {
                record(ModerationAction.UNBAN, profile.getName(), Optional.of(profile.getId()), moderator(scope),
                        Optional.empty(), scope.flatMap(Scope::reason));
            }
            if (scope.map(Scope::fromSync).orElse(false)) {
                return;
            }
            discord.get().ifPresent(bot -> bot.onMinecraftUnban(profile.getId()));
        } else if (list instanceof IpBanList && user instanceof String ip) {
            record(expired ? ModerationAction.BAN_EXPIRED : ModerationAction.IP_UNBAN, ip, Optional.empty(),
                    expired ? Constants.MOD_NAME : moderator(scope), Optional.empty(), expired
                            ? Optional.of(EXPIRED_REASON) : scope.flatMap(Scope::reason));
        } else if (list instanceof UserWhiteList && user instanceof GameProfile profile) {
            record(ModerationAction.WHITELIST_REMOVE, profile.getName(), Optional.of(profile.getId()),
                    moderator(scope), Optional.empty(), scope.flatMap(Scope::reason));
        }
    }

    /**
     * Called by a hook when the vanilla whitelist is switched on or off.
     *
     * @param on whether it is switched on
     */
    public void onWhitelistSwitched(boolean on) {
        Optional<Scope> scope = ModerationContext.current();
        record(on ? ModerationAction.WHITELIST_ON : ModerationAction.WHITELIST_OFF, "", Optional.empty(),
                moderator(scope), Optional.empty(), scope.flatMap(Scope::reason));
    }

    /**
     * Called by a hook when the vanilla {@code /kick} command kicks a player.
     *
     * @param moderator who ran the command
     * @param player    the kicked player
     * @param reason    the reason
     */
    public void onVanillaKick(String moderator, ServerPlayer player, String reason) {
        record(ModerationAction.KICK, player.getGameProfile().getName(), Optional.of(player.getUUID()), moderator,
                Optional.empty(), reason(reason));
    }

    /**
     * Logs that the ban sync could not change a Discord account, so moderators can do it by hand.
     *
     * @param player the player's name
     * @param uuid   the player's UUID
     * @param detail what failed and what to do
     */
    public void recordSyncFailure(String player, UUID uuid, String detail) {
        record(ModerationAction.SYNC_FAILED, player, Optional.of(uuid), Constants.MOD_NAME, Optional.empty(),
                Optional.of(detail));
    }

    // ---- Helpers ----

    /**
     * Writes an entry to the moderation log.
     *
     * @param action    the kind of action
     * @param target    the player name, IP address or role
     * @param player    the target player's UUID
     * @param moderator who did it
     * @param until     the end of a temporary ban
     * @param reason    the reason
     */
    private void record(ModerationAction action, String target, Optional<UUID> player, String moderator,
                        Optional<Instant> until, Optional<String> reason) {
        log.record(new ModerationEntry(Instant.now().truncatedTo(ChronoUnit.SECONDS), action, target, player,
                moderator, until, reason));
    }

    /**
     * Shows an action of a Discord moderator to the operators in Minecraft, like vanilla shows commands of other
     * operators: {@code [@anedhel: Banned Steve: Griefing]}. Actions from Minecraft commands are shown by the
     * commands themselves.
     *
     * @param scope   the moderator
     * @param message the vanilla message of the action
     */
    private void announce(Scope scope, Component message) {
        if (!scope.moderator().startsWith(DISCORD_PREFIX)
                || !server.getGameRules().getBoolean(GameRules.RULE_SENDCOMMANDFEEDBACK)) {
            return;
        }
        Component line = Component.translatable("chat.type.admin",
                        scope.moderator().substring(DISCORD_PREFIX.length()), message)
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (server.getPlayerList().isOp(player.getGameProfile())) {
                player.sendSystemMessage(line);
            }
        }
    }

    /**
     * Returns the moderator of the running action.
     *
     * @param scope the active scope
     * @return the moderator, or "unknown" outside of commands
     */
    private static String moderator(Optional<Scope> scope) {
        return scope.map(Scope::moderator).orElse(UNKNOWN);
    }

    /**
     * Returns the moderator of a ban: the moderator of the running command, otherwise the source stored in the ban
     * (e.g. for bans by other mods). Commands from the Discord console channel are stored as {@code Server}, but
     * logged with the Discord user.
     *
     * @param scope the active scope
     * @param ban   the ban entry
     * @return the moderator
     */
    private static String moderator(Optional<Scope> scope, BanListEntry<?> ban) {
        return scope.map(Scope::moderator).orElse(ban.getSource());
    }

    /**
     * Converts a reason of a vanilla entry for the log. The vanilla default reason ({@code Banned by an operator.})
     * counts as given, as it cannot be told apart from a typed one.
     *
     * @param reason the reason
     * @return the reason, empty if blank
     */
    private static Optional<String> reason(String reason) {
        return Optional.ofNullable(reason).map(String::strip).filter(text -> !text.isEmpty());
    }

    /**
     * A ban for the ban list.
     *
     * @param player the player's name
     * @param source who banned the player
     * @param until  when the ban ends, empty if permanent
     * @param reason the reason
     */
    public record Ban(String player, String source, Optional<Instant> until, String reason) {
    }
}
