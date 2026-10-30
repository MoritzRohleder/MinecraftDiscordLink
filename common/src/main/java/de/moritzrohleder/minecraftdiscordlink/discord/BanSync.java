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
import net.dv8tion.jda.api.events.guild.GuildBanEvent;
import net.dv8tion.jda.api.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;

import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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
    /** Discord users whose unban failed and is retried. */
    private final Set<Long> pendingUnbans = ConcurrentHashMap.newKeySet();

    /**
     * Creates the ban sync; register it as JDA event listener and call {@link #start()}.
     *
     * @param guild    the Discord server
     * @param server   the running server
     * @param links    the account links
     * @param executor the bot's executor, for the retries
     */
    BanSync(Guild guild, MinecraftServer server, LinkStore links, ScheduledExecutorService executor) {
        this.guild = guild;
        this.server = server;
        this.links = links;
        this.executor = executor;
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
    public void onMinecraftBan(UUID player, String reason) {
        ModConfig.BanSync config = ConfigManager.get().moderation().banSync();
        OptionalLong discordId = links.discordId(player);
        if (!config.enabled() || discordId.isEmpty()) {
            return;
        }
        UserSnowflake user = UserSnowflake.fromId(discordId.getAsLong());
        String auditReason = truncate("Minecraft ban: " + reason);
        pendingUnbans.remove(discordId.getAsLong());
        if (config.discordAction() == ModConfig.DiscordAction.BAN) {
            guild.ban(user, 0, TimeUnit.SECONDS).reason(auditReason)
                    .queue(null, error -> logError("ban", discordId.getAsLong(), error));
        } else {
            guild.kick(user).reason(auditReason).queue(null, error -> {
                if (!isError(error, ErrorResponse.UNKNOWN_MEMBER)) {
                    logError("kick", discordId.getAsLong(), error);
                }
            });
        }
    }

    @Override
    public void onMinecraftUnban(UUID player) {
        ModConfig.BanSync config = ConfigManager.get().moderation().banSync();
        OptionalLong discordId = links.discordId(player);
        if (config.enabled() && config.discordAction() == ModConfig.DiscordAction.BAN && discordId.isPresent()) {
            unban(discordId.getAsLong());
        }
    }

    @Override
    public Optional<String> roleName(long roleId) {
        return Optional.ofNullable(guild.getRoleById(roleId)).map(Role::getName);
    }

    /**
     * Lifts a Discord ban. A failure other than "not banned" is retried later.
     *
     * @param discordId the Discord user
     */
    private void unban(long discordId) {
        guild.unban(UserSnowflake.fromId(discordId)).reason("Minecraft unban").queue(
                done -> pendingUnbans.remove(discordId),
                error -> {
                    if (isError(error, ErrorResponse.UNKNOWN_BAN)) {
                        pendingUnbans.remove(discordId);
                    } else if (pendingUnbans.add(discordId)) {
                        logError("unban (will retry)", discordId, error);
                    }
                });
    }

    /**
     * Retries the failed Discord unbans.
     */
    private void retryUnbans() {
        pendingUnbans.forEach(this::unban);
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
        ModerationContext.run(new ModerationContext.Scope(moderator, Optional.of(reason), true),
                () -> bans.add(new UserBanListEntry(profile, null, moderator, null, reason)));
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            online.connection.disconnect(Component.translatable("multiplayer.disconnect.banned"));
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
        ModerationContext.run(new ModerationContext.Scope(moderator, Optional.ofNullable(reason), true),
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
