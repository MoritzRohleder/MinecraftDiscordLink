package de.moritzrohleder.minecraftdiscordlink.discord;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.linking.LinkStore;
import de.moritzrohleder.minecraftdiscordlink.moderation.DiscordModeration;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bans across Minecraft and Discord for linked accounts ({@code moderation.ban_sync}).
 * <p>
 * A Minecraft ban bans (or only kicks) the linked Discord account, a Minecraft unban or an expired temporary ban
 * lifts the Discord ban. A Discord ban, also one made in the Discord client, bans the linked Minecraft account, a
 * Discord unban lifts the Minecraft ban. Actions caused by the sync are marked in the {@link ModerationContext}, so
 * they are not synchronized back. Discord unbans that fail are retried every minute while the server runs.
 */
final class BanSync extends ListenerAdapter implements DiscordModeration {

    /** Discord's maximum length of an audit log reason. */
    private static final int MAX_REASON_LENGTH = 512;
    /** Reason of Minecraft bans made from a Discord ban without reason. */
    private static final String DISCORD_BAN_REASON = "Banned on Discord";

    private final Guild guild;
    private final MinecraftServer server;
    private final LinkStore links;
    private final ScheduledExecutorService executor;
    private final Moderation moderation;
    /** Discord users whose unban failed and is retried, with the UUID of their player. */
    private final Map<Long, UUID> pendingUnbans = new ConcurrentHashMap<>();

    /**
     * Creates the ban sync; register it as JDA event listener and call {@link #start()}.
     *
     * @param guild    the Discord server
     * @param server   the running server
     * @param links    the account links
     * @param executor   the bot's executor, for the retries
     * @param moderation the moderation, to log failed changes
     */
    BanSync(Guild guild, MinecraftServer server, LinkStore links, ScheduledExecutorService executor,
            Moderation moderation) {
        this.guild = guild;
        this.server = server;
        this.links = links;
        this.executor = executor;
        this.moderation = moderation;
    }

    /**
     * Starts retrying failed Discord unbans every minute.
     */
    void start() {
        try {
            executor.scheduleWithFixedDelay(this::retryUnbans, 1, 1, TimeUnit.MINUTES);
        } catch (RejectedExecutionException ignored) {
            // The bot is shutting down
        }
    }

    // ---- Minecraft -> Discord ----

    @Override
    public void onMinecraftBan(UUID player, String reason, boolean deleteMessages) {
        ModConfig.BanSync config = ConfigManager.get().moderation().banSync();
        OptionalLong discordId = links.discordId(player);
        if (!config.enabled() || discordId.isEmpty()) {
            return;
        }
        UserSnowflake user = UserSnowflake.fromId(discordId.getAsLong());
        String auditReason = truncate("Minecraft ban: " + reason);
        pendingUnbans.remove(discordId.getAsLong());
        try {
            if (config.discordAction() == ModConfig.DiscordAction.BAN) {
                guild.ban(user, deleteMessages ? 7 : 0, TimeUnit.DAYS).reason(auditReason)
                        .queue(null, error -> failed(player, discordId.getAsLong(), "ban", error));
            } else {
                guild.kick(user).reason(auditReason).queue(null, error -> {
                    if (!isError(error, ErrorResponse.UNKNOWN_MEMBER)) {
                        failed(player, discordId.getAsLong(), "kick", error);
                    }
                });
            }
        } catch (InsufficientPermissionException | HierarchyException e) {
            // Checked by JDA before sending, e.g. without "Ban Members" or for members above the bot
            failed(player, discordId.getAsLong(), config.discordAction() == ModConfig.DiscordAction.BAN ? "ban"
                    : "kick", e);
        }
    }

    @Override
    public void onMinecraftUnban(UUID player) {
        ModConfig.BanSync config = ConfigManager.get().moderation().banSync();
        OptionalLong discordId = links.discordId(player);
        if (config.enabled() && config.discordAction() == ModConfig.DiscordAction.BAN && discordId.isPresent()) {
            unban(discordId.getAsLong(), player);
        }
    }

    @Override
    public boolean networkReady() {
        OptionalLong channel = ConfigManager.get().moderation().channelId();
        return ConfigManager.get().server().named() && channel.isPresent()
                && guild.getJDA().getChannelById(GuildMessageChannel.class, channel.getAsLong()) != null;
    }

    @Override
    public Optional<String> roleName(long roleId) {
        return Optional.ofNullable(guild.getRoleById(roleId)).map(Role::getName);
    }

    /**
     * Lifts a Discord ban. A failure other than "not banned" is logged once and retried every minute.
     *
     * @param discordId the Discord user
     * @param player    the UUID of the linked player
     */
    private void unban(long discordId, UUID player) {
        try {
            guild.unban(UserSnowflake.fromId(discordId)).reason("Minecraft unban").queue(
                    done -> pendingUnbans.remove(discordId),
                    error -> {
                        if (isError(error, ErrorResponse.UNKNOWN_BAN)) {
                            pendingUnbans.remove(discordId);
                        } else if (pendingUnbans.put(discordId, player) == null) {
                            failed(player, discordId, "unban (retrying every minute)", error);
                        }
                    });
        } catch (InsufficientPermissionException e) {
            if (pendingUnbans.put(discordId, player) == null) {
                failed(player, discordId, "unban (retrying every minute)", e);
            }
        }
    }

    /**
     * Retries the failed Discord unbans.
     */
    private void retryUnbans() {
        pendingUnbans.forEach(this::unban);
    }

    /**
     * Logs the Discord unbans that are still pending when the server stops, so moderators lift them by hand.
     */
    void reportPending() {
        pendingUnbans.forEach((discordId, player) -> {
            String name = profile(player).getName();
            Constants.LOG.error("Ban sync: the Discord ban of the user {} (player {}) could not be lifted. Lift it "
                    + "manually.", discordId, name);
            onServerThread(() -> moderation.recordSyncFailure(name, player, "The Discord ban of <@" + discordId
                    + "> could not be lifted before the server stopped. Lift it manually."));
        });
    }

    /**
     * Logs a failed Discord action to the console and the moderation log.
     *
     * @param player    the UUID of the linked player
     * @param discordId the Discord user
     * @param action    the action, e.g. {@code ban}
     * @param error     the error
     */
    private void failed(UUID player, long discordId, String action, Throwable error) {
        logError(action, discordId, error);
        onServerThread(() -> moderation.recordSyncFailure(profile(player).getName(), player, "Could not " + action
                + " the Discord user <@" + discordId + ">: " + error.getMessage()));
    }

    /**
     * Runs an action on the server thread: at once if already there (e.g. while the server stops), otherwise queued.
     *
     * @param action the action
     */
    private void onServerThread(Runnable action) {
        if (server.isSameThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }

    // ---- Discord -> Minecraft ----

    /**
     * A user was banned on Discord: bans the linked Minecraft account.
     *
     * @param event the ban event
     */
    @Override
    public void onGuildBan(GuildBanEvent event) {
        User user = event.getUser();
        Optional<UUID> player = links.player(user.getIdLong());
        if (!event.getGuild().equals(guild) || !ConfigManager.get().moderation().banSync().enabled()
                || player.isEmpty()) {
            return;
        }
        auditEntry(ActionType.BAN, user.getIdLong(), entry -> {
            if (entry.isPresent() && entry.get().getUserIdLong() == guild.getSelfMember().getIdLong()) {
                // Made by the ban sync itself
                return;
            }
            String moderator = moderator(entry);
            String reason = entry.map(AuditLogEntry::getReason).filter(text -> !text.isBlank())
                    .orElse(DISCORD_BAN_REASON);
            server.execute(() -> banInMinecraft(player.get(), moderator, reason));
        });
    }

    /**
     * A user was unbanned on Discord: lifts the ban of the linked Minecraft account.
     *
     * @param event the unban event
     */
    @Override
    public void onGuildUnban(GuildUnbanEvent event) {
        User user = event.getUser();
        Optional<UUID> player = links.player(user.getIdLong());
        pendingUnbans.remove(user.getIdLong());
        if (!event.getGuild().equals(guild) || !ConfigManager.get().moderation().banSync().enabled()
                || player.isEmpty()) {
            return;
        }
        auditEntry(ActionType.UNBAN, user.getIdLong(), entry -> {
            if (entry.isPresent() && entry.get().getUserIdLong() == guild.getSelfMember().getIdLong()) {
                return;
            }
            String moderator = moderator(entry);
            Optional<String> reason = entry.map(AuditLogEntry::getReason).filter(text -> !text.isBlank());
            server.execute(() -> unbanInMinecraft(player.get(), moderator, reason.orElse(null)));
        });
    }

    /**
     * Bans a player in Minecraft because of a Discord ban. Must be called on the server thread.
     *
     * @param player    the player's UUID
     * @param moderator the Discord moderator
     * @param reason    the reason of the Discord ban
     */
    private void banInMinecraft(UUID player, String moderator, String reason) {
        UserBanList bans = server.getPlayerList().getBans();
        GameProfile profile = profile(player);
        if (bans.isBanned(profile)) {
            return;
        }
        UserBanListEntry ban = new UserBanListEntry(profile, null, moderator, null, reason);
        ModerationContext.run(ModerationContext.Scope.synced(moderator, reason), () -> bans.add(ban));
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            online.connection.disconnect(Moderation.banMessage(ban));
        }
    }

    /**
     * Lifts the Minecraft ban of a player because of a Discord unban. Must be called on the server thread.
     *
     * @param player    the player's UUID
     * @param moderator the Discord moderator
     * @param reason    the reason of the Discord unban, may be {@code null}
     */
    private void unbanInMinecraft(UUID player, String moderator, String reason) {
        UserBanList bans = server.getPlayerList().getBans();
        GameProfile profile = profile(player);
        if (!bans.isBanned(profile)) {
            return;
        }
        ModerationContext.run(ModerationContext.Scope.synced(moderator, reason),
                () -> bans.remove(profile));
    }

    // ---- Helpers ----

    /**
     * Looks up the latest audit log entry of an action on a user. Needs the "View Audit Log" permission; without
     * it, the entry is empty.
     *
     * @param type     the kind of action
     * @param targetId the user the action was done to
     * @param callback receives the entry, empty if not found
     */
    private void auditEntry(ActionType type, long targetId, Consumer<Optional<AuditLogEntry>> callback) {
        guild.retrieveAuditLogs().type(type).limit(10).queue(
                entries -> callback.accept(entries.stream()
                        .filter(entry -> entry.getTargetIdLong() == targetId)
                        .max(Comparator.comparing(AuditLogEntry::getTimeCreated))),
                error -> {
                    Constants.LOG.warn("Could not read the Discord audit log (needs \"View Audit Log\"): {}",
                            error.getMessage());
                    callback.accept(Optional.empty());
                });
    }

    /**
     * Names the moderator of a Discord action for the moderation log.
     *
     * @param entry the audit log entry, empty if unknown
     * @return e.g. {@code Discord @anedhel}
     */
    private static String moderator(Optional<AuditLogEntry> entry) {
        return Moderation.DISCORD_PREFIX + entry.map(AuditLogEntry::getUser).map(user -> "@" + user.getName())
                .orElse("(unknown moderator)");
    }

    /**
     * Returns the profile of a player for a ban entry, with the name if known.
     *
     * @param player the player's UUID
     * @return the profile
     */
    private GameProfile profile(UUID player) {
        return Optional.ofNullable(server.getProfileCache()).flatMap(cache -> cache.get(player))
                .orElseGet(() -> new GameProfile(player, player.toString()));
    }

    /**
     * Shortens a reason to Discord's limit.
     *
     * @param reason the reason
     * @return the reason, at most {@link #MAX_REASON_LENGTH} characters
     */
    private static String truncate(String reason) {
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH - 1) + "…";
    }

    /**
     * Checks whether a failed request failed with a specific Discord error.
     *
     * @param error    the error
     * @param response the Discord error
     * @return whether it is that error
     */
    private static boolean isError(Throwable error, ErrorResponse response) {
        return error instanceof ErrorResponseException exception && exception.getErrorResponse() == response;
    }

    /**
     * Logs a failed Discord action.
     *
     * @param action    the action, e.g. {@code ban}
     * @param discordId the Discord user
     * @param error     the error
     */
    private static void logError(String action, long discordId, Throwable error) {
        Constants.LOG.error("Ban sync: could not {} the Discord user {}: {}", action, discordId, error.getMessage());
    }
}
