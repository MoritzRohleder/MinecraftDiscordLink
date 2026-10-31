package de.moritzrohleder.minecraftdiscordlink.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.info.InfoText;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.maintenance.Maintenance;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import de.moritzrohleder.minecraftdiscordlink.platform.Services;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.Collection;
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
 *     <li>{@code /discord admin link <player> <discordname>}: links accounts manually (admins only)</li>
 *     <li>{@code /discord tempban <player> <duration> <reason>}: bans a player for a time (admins only)</li>
 *     <li>{@code /discord rolewhitelist status}: shows the whitelisted Discord roles (admins only)</li>
 *     <li>{@code /discord maintenance on [persistent] [reason]|off [reason]|status}: switches the maintenance mode
 *     (admins only)</li>
 * </ul>
 * Admins are operators of level 4 or players with the permission node {@code minecraftdiscordlink.admin}.
 */
public final class DiscordCommand {

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
                .then(Commands.literal("tempban")
                        .requires(Services.PLATFORM::isAdmin)
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(DiscordCommand::tempban)))))
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
     * Runs {@code /discord tempban <player> <duration> <reason>}.
     *
     * @param context the command context
     * @return the number of banned players
     * @throws CommandSyntaxException if the player or the duration is invalid
     */
    private static int tempban(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (switchedOff(context, ConfigManager.get().moderation().enabled())) {
            return 0;
        }
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "player");
        Duration duration = Durations.parse(StringArgumentType.getString(context, "duration"))
                .orElseThrow(INVALID_DURATION::create);
        String reason = StringArgumentType.getString(context, "reason");
        CommandSourceStack source = context.getSource();
        Optional<Moderation> moderation = MinecraftDiscordLink.moderation();
        if (moderation.isEmpty()) {
            source.sendFailure(Component.literal("The moderation is not available right now."));
            return 0;
        }
        ModerationContext.Scope scope = ModerationContext.Scope.of(source.getTextName(), reason);
        int banned = 0;
        for (GameProfile profile : profiles) {
            Linking.Reply reply = moderation.get().ban(profile, reason, Optional.of(duration), scope);
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
