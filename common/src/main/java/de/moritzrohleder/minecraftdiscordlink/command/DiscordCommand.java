package de.moritzrohleder.minecraftdiscordlink.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.info.Features;
import de.moritzrohleder.minecraftdiscordlink.info.InfoText;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.maintenance.Maintenance;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationEntry;
import de.moritzrohleder.minecraftdiscordlink.moderation.WarningStore;
import de.moritzrohleder.minecraftdiscordlink.platform.Services;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The Minecraft command {@code /discord}.
 * <ul>
 *     <li>{@code /discord link <discordname>}: sends a link code to the Discord user by DM</li>
 *     <li>{@code /discord confirm <code>}: completes the link with the code</li>
 *     <li>{@code /discord unlink}: removes the own link</li>
 *     <li>{@code /discord whois <name>}: shows the account linked to a Minecraft or Discord name</li>
 *     <li>{@code /discord reload}: reloads the config (admins only)</li>
 *     <li>{@code /discord features}: shows which features are switched on (admins only)</li>
 *     <li>{@code /discord admin link <player> <discordname>}: links accounts manually (admins only)</li>
 *     <li>{@code /discord tempban <player> <duration> <reason>}: bans a player for a time (admins only)</li>
 *     <li>{@code /discord networkban <player> <reason>}, {@code /discord networktempban <player> <duration> <reason>}
 *     and {@code /discord networkunban <player> <reason>}: the same on all servers sharing the Discord server
 *     (admins only)</li>
 *     <li>{@code /discord mute <player> <duration> <reason>}, {@code /discord unmute <player> <reason>} and the
 *     variants on all servers {@code networkmute} and {@code networkunmute} (admins only)</li>
 *     <li>{@code /discord history <player> [page]}: shows the moderation actions on a player (admins only)</li>
 *     <li>{@code /discord warn <player> <reason>}, {@code /discord networkwarn <player> <reason>},
 *     {@code /discord unwarn <id> <reason>} and {@code /discord warnings <player>}: warnings (admins only)</li>
 *     <li>{@code /discord case <id>}, {@code /discord case reason <id> <reason>} and
 *     {@code /discord case reactivate <id> <reason>}: a moderation action by its number (admins only)</li>
 *     <li>{@code /discord rolewhitelist status}: shows the whitelisted Discord roles (admins only)</li>
 *     <li>{@code /discord maintenance on [persistent] [reason]|off [reason]|status}: switches the maintenance mode
 *     (admins only)</li>
 * </ul>
 * Admins are operators of level 4 or players with the permission node {@code minecraftdiscordlink.admin}.
 */
public final class DiscordCommand {

    /** Entries per page of {@code /discord history}. */
    private static final int HISTORY_PAGE_SIZE = 10;
    /** Format of the times in {@code /discord history}, in the server's time zone. */
    private static final DateTimeFormatter HISTORY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** Error for an invalid ban duration. */
    private static final SimpleCommandExceptionType INVALID_DURATION = new SimpleCommandExceptionType(
            Component.literal("Invalid duration. Use e.g. 30m, 12h, 7d, 2w or 1d12h."));

    private DiscordCommand() {
    }

    /**
     * Registers the command.
     *
     * @param dispatcher the server's command dispatcher
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("discord")
                .then(Commands.literal("link")
                        .then(Commands.argument("discordname", StringArgumentType.word())
                                .executes(DiscordCommand::link)))
                .then(Commands.literal("confirm")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(DiscordCommand::confirm)))
                .then(Commands.literal("unlink")
                        .executes(DiscordCommand::unlink))
                .then(Commands.literal("whois")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(DiscordCommand::whois)))
                .then(Commands.literal("reload")
                        .requires(Services.PLATFORM::isAdmin)
                        .executes(DiscordCommand::reload))
                .then(Commands.literal("features")
                        .requires(Services.PLATFORM::isAdmin)
                        .executes(DiscordCommand::features))
                .then(Commands.literal("tempban")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> ban(context, true, false))))))
                .then(Commands.literal("networkban")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> ban(context, false, true)))))
                .then(Commands.literal("networktempban")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> ban(context, true, true))))))
                .then(Commands.literal("networkunban")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(DiscordCommand::networkUnban))))
                .then(Commands.literal("mute")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> mute(context, true, false))))))
                .then(Commands.literal("networkmute")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> mute(context, true, true))))))
                .then(Commands.literal("unmute")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> mute(context, false, false)))))
                .then(Commands.literal("networkunmute")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> mute(context, false, true)))))
                .then(Commands.literal("warn")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> warn(context, false)))))
                .then(Commands.literal("networkwarn")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> warn(context, true)))))
                .then(Commands.literal("unwarn")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> caseAction(context, CaseAction.UNWARN)))))
                .then(Commands.literal("warnings")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(DiscordCommand::warnings)))
                .then(Commands.literal("case")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(DiscordCommand::showCase))
                        .then(Commands.literal("reason")
                                .then(Commands.argument("id", LongArgumentType.longArg(1))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> caseAction(context, CaseAction.REASON)))))
                        .then(Commands.literal("reactivate")
                                .then(Commands.argument("id", LongArgumentType.longArg(1))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> caseAction(context, CaseAction.REACTIVATE))))))
                .then(Commands.literal("history")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(context -> history(context, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> history(context,
                                                IntegerArgumentType.getInteger(context, "page"))))))
                .then(Commands.literal("rolewhitelist")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.literal("status")
                                .executes(DiscordCommand::roleWhitelistStatus)))
                .then(Commands.literal("maintenance")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.literal("on")
                                .executes(context -> maintenance(context, true, false, false))
                                .then(Commands.argument("persistent", BoolArgumentType.bool())
                                        .executes(context -> maintenance(context, true,
                                                BoolArgumentType.getBool(context, "persistent"), false))
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(context -> maintenance(context, true,
                                                        BoolArgumentType.getBool(context, "persistent"), true)))))
                        .then(Commands.literal("off")
                                .executes(context -> maintenance(context, false, false, false))
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(context -> maintenance(context, false, false, true))))
                        .then(Commands.literal("status")
                                .executes(DiscordCommand::maintenanceStatus)))
                .then(Commands.literal("admin")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.literal("link")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("discordname", StringArgumentType.word())
                                                .executes(DiscordCommand::adminLink))))));
    }

    /**
     * Runs {@code /discord link <discordname>}.
     *
     * @param context the command context
     * @return the command result
     * @throws CommandSyntaxException if the source is not a player
     */
    private static int link(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        withLinking(context, linking -> linking.requestLink(player,
                StringArgumentType.getString(context, "discordname"), reply(context)));
        return 1;
    }

    /**
     * Runs {@code /discord confirm <code>}.
     *
     * @param context the command context
     * @return the command result
     * @throws CommandSyntaxException if the source is not a player
     */
    private static int confirm(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        withLinking(context, linking -> linking.confirm(player, StringArgumentType.getString(context, "code"),
                reply(context)));
        return 1;
    }

    /**
     * Runs {@code /discord unlink}.
     *
     * @param context the command context
     * @return the command result
     * @throws CommandSyntaxException if the source is not a player
     */
    private static int unlink(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        withLinking(context, linking -> linking.unlink(player, reply(context)));
        return 1;
    }

    /**
     * Runs {@code /discord whois <name>}. Looking up the name may contact Mojang, so this runs in the background
     * and answers on the server thread.
     *
     * @param context the command context
     * @return the command result
     */
    private static int whois(CommandContext<CommandSourceStack> context) {
        if (switchedOff(context, ConfigManager.get().info().enabled())) {
            return 0;
        }
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        withLinking(context, linking -> CompletableFuture
                .supplyAsync(() -> InfoText.whois(name, linking.whois(name)))
                .thenAccept(text -> server.execute(() -> source.sendSystemMessage(Component.literal(text)))));
        return 1;
    }

    /**
     * Runs {@code /discord reload}.
     *
     * @param context the command context
     * @return the command result
     */
    private static int reload(CommandContext<CommandSourceStack> context) {
        String result = MinecraftDiscordLink.reloadConfig();
        context.getSource().sendSuccess(() -> Component.literal(result), true);
        return 1;
    }

    /**
     * Runs {@code /discord features}: one line per feature, green if switched on, red if off, with its parts.
     *
     * @param context the command context
     * @return 1
     */
    private static int features(CommandContext<CommandSourceStack> context) {
        MutableComponent text = Component.literal("Features:").withStyle(ChatFormatting.GOLD);
        for (Features.Feature feature : Features.of(ConfigManager.get())) {
            text.append(featureLine(feature, ""));
            feature.parts().forEach(part -> text.append(featureLine(part, "    ")));
        }
        context.getSource().sendSystemMessage(text);
        return 1;
    }

    /**
     * Formats one feature for {@code /discord features}.
     *
     * @param feature the feature
     * @param indent  the indentation of parts
     * @return the line, starting with a line break
     */
    private static Component featureLine(Features.Feature feature, String indent) {
        return Component.literal("\n" + indent + (feature.enabled() ? "✔ " : "✘ ") + feature.name())
                .withStyle(feature.enabled() ? ChatFormatting.GREEN : ChatFormatting.RED)
                .append(Component.literal(feature.note().map(note -> " (" + note + ")").orElse(""))
                        .withStyle(ChatFormatting.YELLOW));
    }

    /**
     * Runs {@code /discord admin link <player> <discordname>}. Looking up the player may contact Mojang, so this
     * runs in the background and answers on the server thread.
     *
     * @param context the command context
     * @return the command result
     */
    private static int adminLink(CommandContext<CommandSourceStack> context) {
        String playerName = StringArgumentType.getString(context, "player");
        String discordName = StringArgumentType.getString(context, "discordname");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        withLinking(context, linking -> {
            Optional<OptionalLong> discordId = MinecraftDiscordLink.findDiscordMember(discordName);
            if (discordId.isEmpty()) {
                source.sendFailure(Component.literal("Discord is not available right now. Please try again later."));
            } else if (discordId.get().isEmpty()) {
                source.sendFailure(Component.literal("No member with the username " + discordName
                        + " was found on the Discord server."));
            } else {
                long id = discordId.get().getAsLong();
                CompletableFuture.supplyAsync(() -> linking.adminLink(playerName, id)).thenAccept(reply ->
                        server.execute(() -> source.sendSystemMessage(Component.literal(reply.message())
                                .withStyle(reply.success() ? ChatFormatting.GREEN : ChatFormatting.RED))));
            }
        });
        return 1;
    }

    /**
     * Runs {@code /discord tempban}, {@code /discord networkban} and {@code /discord networktempban}.
     *
     * @param context    the command context
     * @param temporary  whether the command has a duration
     * @param allServers whether the ban applies to all servers sharing the Discord server
     * @return the number of banned players
     * @throws CommandSyntaxException if the player or the duration is invalid
     */
    private static int ban(CommandContext<CommandSourceStack> context, boolean temporary, boolean allServers)
            throws CommandSyntaxException {
        if (switchedOff(context, ConfigManager.get().moderation().enabled())) {
            return 0;
        }
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "player");
        Optional<Duration> duration = temporary ? Optional.of(Durations.parse(StringArgumentType.getString(context,
                "duration")).orElseThrow(INVALID_DURATION::create)) : Optional.empty();
        String reason = StringArgumentType.getString(context, "reason");
        CommandSourceStack source = context.getSource();
        Optional<Moderation> moderation = MinecraftDiscordLink.moderation();
        if (moderation.isEmpty()) {
            source.sendFailure(Component.literal("The moderation is not available right now."));
            return 0;
        }
        ModerationContext.Scope scope = ModerationContext.Scope.of(source.getTextName(), reason)
                .withBanOptions(false, allServers);
        int banned = 0;
        for (GameProfile profile : profiles) {
            Linking.Reply reply = moderation.get().ban(profile, reason, duration, scope);
            if (reply.success()) {
                banned++;
                source.sendSuccess(() -> Component.literal(reply.message()), true);
            } else {
                source.sendFailure(Component.literal(reply.message()));
            }
        }
        return banned;
    }

    /**
     * Runs {@code /discord networkunban <player> <reason>}: lifts the ban on all servers.
     *
     * @param context the command context
     * @return the number of unbanned players
     * @throws CommandSyntaxException if the player is invalid
     */
    private static int networkUnban(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (switchedOff(context, ConfigManager.get().moderation().enabled())) {
            return 0;
        }
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "player");
        String reason = StringArgumentType.getString(context, "reason");
        CommandSourceStack source = context.getSource();
        Optional<Moderation> moderation = MinecraftDiscordLink.moderation();
        if (moderation.isEmpty()) {
            source.sendFailure(Component.literal("The moderation is not available right now."));
            return 0;
        }
        ModerationContext.Scope scope = ModerationContext.Scope.of(source.getTextName(), reason)
                .withBanOptions(false, true);
        int unbanned = 0;
        for (GameProfile profile : profiles) {
            Linking.Reply reply = moderation.get().unban(profile, reason, scope);
            if (reply.success()) {
                unbanned++;
                source.sendSuccess(() -> Component.literal(reply.message()), true);
            } else {
                source.sendFailure(Component.literal(reply.message()));
            }
        }
        return unbanned;
    }

    /**
     * Runs {@code /discord mute}, {@code /discord unmute}, {@code /discord networkmute} and
     * {@code /discord networkunmute}.
     *
     * @param context    the command context
     * @param mute       whether to mute (with a duration) or to unmute
     * @param allServers whether it applies to all servers sharing the Discord server
     * @return the number of players changed
     * @throws CommandSyntaxException if the player or the duration is invalid
     */
    private static int mute(CommandContext<CommandSourceStack> context, boolean mute, boolean allServers)
            throws CommandSyntaxException {
        if (switchedOff(context, ConfigManager.get().moderation().enabled())) {
            return 0;
        }
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "player");
        Duration duration = mute ? Durations.parse(StringArgumentType.getString(context, "duration"))
                .orElseThrow(INVALID_DURATION::create) : null;
        String reason = StringArgumentType.getString(context, "reason");
        CommandSourceStack source = context.getSource();
        Optional<Moderation> moderation = MinecraftDiscordLink.moderation();
        if (moderation.isEmpty()) {
            source.sendFailure(Component.literal("The moderation is not available right now."));
            return 0;
        }
        ModerationContext.Scope scope = ModerationContext.Scope.of(source.getTextName(), reason)
                .withBanOptions(false, allServers);
        int changed = 0;
        for (GameProfile profile : profiles) {
            Linking.Reply reply = mute ? moderation.get().mute(profile, reason, duration, scope)
                    : moderation.get().unmute(profile, reason, scope);
            if (reply.success()) {
                changed++;
                source.sendSuccess(() -> Component.literal(reply.message()), true);
            } else {
                source.sendFailure(Component.literal(reply.message()));
            }
        }
        return changed;
    }

    /**
     * Changes of a moderation action by its number.
     */
    private enum CaseAction {
        /** {@code /discord unwarn <id> <reason>}. */
        UNWARN,
        /** {@code /discord case reactivate <id> <reason>}. */
        REACTIVATE,
        /** {@code /discord case reason <id> <reason>}. */
        REASON
    }

    /**
     * Runs {@code /discord warn <player> <reason>} and {@code /discord networkwarn <player> <reason>}.
     *
     * @param context    the command context
     * @param allServers whether the warning applies to all servers
     * @return the number of warned players
     * @throws CommandSyntaxException if the player argument is invalid
     */
    private static int warn(CommandContext<CommandSourceStack> context, boolean allServers)
            throws CommandSyntaxException {
        if (switchedOff(context, warningsEnabled())) {
            return 0;
        }
        Optional<Moderation> moderation = moderationOrFail(context);
        if (moderation.isEmpty()) {
            return 0;
        }
        String reason = StringArgumentType.getString(context, "reason");
        CommandSourceStack source = context.getSource();
        ModerationContext.Scope scope = ModerationContext.Scope.of(source.getTextName(), reason)
                .withBanOptions(false, allServers);
        int warned = 0;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(context, "player")) {
            warned += sendReply(source, moderation.get().warn(profile, reason, scope));
        }
        return warned;
    }

    /**
     * Runs {@code /discord unwarn}, {@code /discord case reactivate} and {@code /discord case reason}.
     *
     * @param context the command context
     * @param action  what to change
     * @return 1 on success, 0 otherwise
     */
    private static int caseAction(CommandContext<CommandSourceStack> context, CaseAction action) {
        boolean enabled = action == CaseAction.REASON ? ConfigManager.get().moderation().enabled()
                : warningsEnabled();
        if (switchedOff(context, enabled)) {
            return 0;
        }
        Optional<Moderation> moderation = moderationOrFail(context);
        if (moderation.isEmpty()) {
            return 0;
        }
        long id = LongArgumentType.getLong(context, "id");
        String reason = StringArgumentType.getString(context, "reason");
        ModerationContext.Scope scope = ModerationContext.Scope.of(context.getSource().getTextName(), reason);
        return sendReply(context.getSource(), switch (action) {
            case UNWARN -> moderation.get().unwarn(id, reason, scope);
            case REACTIVATE -> moderation.get().reactivateWarning(id, reason, scope);
            case REASON -> moderation.get().changeReason(id, reason, scope);
        });
    }

    /**
     * Runs {@code /discord warnings <player>}: all warnings of the player, newest first, with their state.
     *
     * @param context the command context
     * @return the number of warnings shown
     * @throws CommandSyntaxException if the player argument is invalid
     */
    private static int warnings(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (switchedOff(context, warningsEnabled())) {
            return 0;
        }
        Optional<Moderation> moderation = moderationOrFail(context);
        if (moderation.isEmpty()) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        ZoneId zone = moderation.get().log().zone();
        int shown = 0;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(context, "player")) {
            List<WarningStore.Warning> warnings = moderation.get().warnings(profile.getId());
            long active = warnings.stream()
                    .filter(warning -> moderation.get().status(warning) == WarningStore.Status.ACTIVE).count();
            source.sendSystemMessage(Component.literal("Warnings of " + profile.getName() + ": " + active
                    + " active, " + warnings.size() + " in total").withStyle(ChatFormatting.GOLD));
            for (WarningStore.Warning warning : warnings) {
                WarningStore.Status status = moderation.get().status(warning);
                source.sendSystemMessage(Component.literal("#" + warning.id() + " "
                                + HISTORY_DATE.format(warning.time().atZone(zone)) + " ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(status.name().toLowerCase(Locale.ROOT)).withStyle(
                                status == WarningStore.Status.ACTIVE ? ChatFormatting.YELLOW : ChatFormatting.GRAY))
                        .append(Component.literal(" · by " + warning.moderator() + " · " + warning.reason())
                                .withStyle(status == WarningStore.Status.ACTIVE ? ChatFormatting.WHITE
                                        : ChatFormatting.GRAY)));
                shown++;
            }
        }
        return shown;
    }

    /**
     * Runs {@code /discord case <id>}: all details of a moderation action.
     *
     * @param context the command context
     * @return 1 if the action exists, 0 otherwise
     */
    private static int showCase(CommandContext<CommandSourceStack> context) {
        if (switchedOff(context, ConfigManager.get().moderation().enabled())) {
            return 0;
        }
        Optional<Moderation> moderation = moderationOrFail(context);
        if (moderation.isEmpty()) {
            return 0;
        }
        long id = LongArgumentType.getLong(context, "id");
        CommandSourceStack source = context.getSource();
        Optional<ModerationEntry> entry = moderation.get().entry(id);
        if (entry.isEmpty()) {
            source.sendFailure(Component.literal("There is no moderation action #" + id + "."));
            return 0;
        }
        ZoneId zone = moderation.get().log().zone();
        source.sendSystemMessage(Component.literal("#" + id + " " + entry.get().action().label()
                + (entry.get().action().hasTarget() ? " " + entry.get().target() : "")).withStyle(ChatFormatting.GOLD));
        source.sendSystemMessage(Component.literal(HISTORY_DATE.format(entry.get().time().atZone(zone)) + " · "
                + entry.get().historyText(zone)));
        moderation.get().warning(id).ifPresent(warning -> source.sendSystemMessage(Component.literal("Warning: "
                + moderation.get().status(warning).name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    /**
     * Checks whether the warnings are switched on.
     *
     * @return whether moderation and warnings are switched on
     */
    private static boolean warningsEnabled() {
        ModConfig.Moderation moderation = ConfigManager.get().moderation();
        return moderation.enabled() && moderation.warnings().enabled();
    }

    /**
     * Returns the moderation, telling the source if it is not available.
     *
     * @param context the command context
     * @return the moderation, empty if not available
     */
    private static Optional<Moderation> moderationOrFail(CommandContext<CommandSourceStack> context) {
        Optional<Moderation> moderation = MinecraftDiscordLink.moderation();
        if (moderation.isEmpty()) {
            context.getSource().sendFailure(Component.literal("The moderation is not available right now."));
        }
        return moderation;
    }

    /**
     * Sends the result of a moderation action to the source, like a vanilla command.
     *
     * @param source the command source
     * @param reply  the result
     * @return 1 on success, 0 otherwise
     */
    private static int sendReply(CommandSourceStack source, Linking.Reply reply) {
        if (reply.success()) {
            source.sendSuccess(() -> Component.literal(reply.message()), true);
            return 1;
        }
        source.sendFailure(Component.literal(reply.message()));
        return 0;
    }

    /**
     * Runs {@code /discord history <player> [page]}: the player's moderation actions, newest first, with their
     * numbers.
     *
     * @param context the command context
     * @param page    the page, starting at 1
     * @return the number of entries shown
     * @throws CommandSyntaxException if the player argument is invalid
     */
    private static int history(CommandContext<CommandSourceStack> context, int page) throws CommandSyntaxException {
        if (switchedOff(context, ConfigManager.get().moderation().enabled())) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        Optional<Moderation> moderation = MinecraftDiscordLink.moderation();
        if (moderation.isEmpty()) {
            source.sendFailure(Component.literal("The moderation is not available right now."));
            return 0;
        }
        int shown = 0;
        for (GameProfile profile : GameProfileArgument.getGameProfiles(context, "player")) {
            List<ModerationEntry> entries = moderation.get().history(profile.getId(), profile.getName());
            if (entries.isEmpty()) {
                source.sendSystemMessage(Component.literal("No moderation actions on " + profile.getName() + "."));
                continue;
            }
            int pages = (entries.size() + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE;
            int current = Math.min(page, pages);
            source.sendSystemMessage(Component.literal("Moderation history of " + profile.getName() + " ("
                    + entries.size() + ", page " + current + "/" + pages + "):").withStyle(ChatFormatting.GOLD));
            ZoneId zone = moderation.get().log().zone();
            for (ModerationEntry entry : entries.subList((current - 1) * HISTORY_PAGE_SIZE,
                    Math.min(entries.size(), current * HISTORY_PAGE_SIZE))) {
                source.sendSystemMessage(Component.literal("#" + entry.id() + " ").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(HISTORY_DATE.format(entry.time().atZone(zone)) + " ")
                                .withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(entry.historyText(zone)).withStyle(ChatFormatting.WHITE)));
                shown++;
            }
        }
        return shown;
    }

    /**
     * Runs {@code /discord rolewhitelist status}.
     *
     * @param context the command context
     * @return 1
     */
    private static int roleWhitelistStatus(CommandContext<CommandSourceStack> context) {
        if (switchedOff(context, ConfigManager.get().moderation().enabled())) {
            return 0;
        }
        ModConfig.Whitelist whitelist = ConfigManager.get().whitelist();
        boolean on = context.getSource().getServer().getPlayerList().isUsingWhitelist();
        String roles = whitelist.roleIds().isEmpty() ? "none"
                : String.join(", ", whitelist.roleIds().stream().map(id -> MinecraftDiscordLink.moderation()
                        .map(moderation -> moderation.roleLabel(id)).orElse(String.valueOf(id))).toList());
        context.getSource().sendSystemMessage(Component.literal("Whitelist: " + (on ? "on" : "off")
                + ". Whitelisted roles: " + roles + (whitelist.enabled() ? "."
                : " (whitelisting by roles is switched off in the config).")));
        return 1;
    }

    /**
     * Runs {@code /discord maintenance on [persistent] [reason]} and {@code /discord maintenance off [reason]}.
     *
     * @param context    the command context
     * @param on         whether to switch the maintenance mode on
     * @param persistent whether it stays on after a restart
     * @param hasReason  whether the command has a reason argument
     * @return 1 on success, otherwise 0
     */
    private static int maintenance(CommandContext<CommandSourceStack> context, boolean on, boolean persistent,
                                   boolean hasReason) {
        if (switchedOff(context, ConfigManager.get().maintenance().enabled())) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        String reason = hasReason ? StringArgumentType.getString(context, "reason") : null;
        Optional<Moderation> moderation = MinecraftDiscordLink.moderation();
        Optional<Maintenance> maintenance = MinecraftDiscordLink.maintenance();
        if (moderation.isEmpty() || maintenance.isEmpty()) {
            source.sendFailure(Component.literal("The maintenance mode is not available right now."));
            return 0;
        }
        Linking.Reply reply = moderation.get().maintenance(maintenance.get(), on, persistent,
                ModerationContext.Scope.of(source.getTextName(), reason));
        if (reply.success()) {
            source.sendSuccess(() -> Component.literal(reply.message()), true);
            return 1;
        }
        source.sendFailure(Component.literal(reply.message()));
        return 0;
    }

    /**
     * Runs {@code /discord maintenance status}.
     *
     * @param context the command context
     * @return 1
     */
    private static int maintenanceStatus(CommandContext<CommandSourceStack> context) {
        if (switchedOff(context, ConfigManager.get().maintenance().enabled())) {
            return 0;
        }
        Optional<Maintenance.Status> status = MinecraftDiscordLink.maintenance().flatMap(Maintenance::status);
        context.getSource().sendSystemMessage(Component.literal(status
                .map(current -> "Maintenance mode is on since " + current.since() + " by " + current.moderator()
                        + (current.persistent() ? ", stays on after a restart" : ", ends with the restart")
                        + current.reason().map(reason -> ". Reason: " + reason).orElse("."))
                .orElse("Maintenance mode is off.")));
        return 1;
    }

    /**
     * Reports a command whose feature is switched off in the config.
     *
     * @param context the command context
     * @param enabled whether the feature is switched on
     * @return whether the feature is switched off, so the command must stop
     */
    private static boolean switchedOff(CommandContext<CommandSourceStack> context, boolean enabled) {
        if (!enabled) {
            context.getSource().sendFailure(Component.literal("This feature is switched off on this server."));
        }
        return !enabled;
    }

    /**
     * Runs an action with the account linking, or reports that the server is not ready.
     *
     * @param context the command context
     * @param action  the action
     */
    private static void withLinking(CommandContext<CommandSourceStack> context, Consumer<Linking> action) {
        MinecraftDiscordLink.linking().ifPresentOrElse(action, () -> context.getSource()
                .sendFailure(Component.literal("The account linking is not available right now.")));
    }

    /**
     * Creates the receiver of the messages for the command source.
     *
     * @param context the command context
     * @return a receiver sending each message to the command source
     */
    private static Consumer<Component> reply(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        return message -> source.sendSystemMessage(message.copy().withStyle(style ->
                style.getColor() == null ? style.withColor(ChatFormatting.WHITE) : style));
    }
}
