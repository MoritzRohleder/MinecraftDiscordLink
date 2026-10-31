package de.moritzrohleder.minecraftdiscordlink.discord;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.info.ServerInfo;
import de.moritzrohleder.minecraftdiscordlink.leaderboard.Leaderboards;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.maintenance.Maintenance;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Discord slash commands {@code /mc} (for everyone) and {@code /mcadmin} (for administrators).
 * <p>
 * The commands are registered for the mod's Discord server only, so they are available immediately. Discord itself
 * restricts {@code /mcadmin} to members with the Administrator permission; server admins can change that in the
 * integration settings of the Discord server. Answers are public embeds in the channel, see {@link CommandEmbeds};
 * they never ping anyone.
 * <p>
 * The leaderboard commands {@code /mc top} and {@code /mc stats} are only registered if at least one category is
 * configured; the commands are registered again on a config reload, as the categories may have changed.
 */
final class SlashCommands extends ListenerAdapter {

    private final Guild guild;
    private final Linking linking;
    private final MinecraftServer server;
    private final Instant startTime;
    private final PlayerStats stats;
    private final Moderation moderation;
    /** The leaderboard categories of the registered commands. */
    private volatile List<Leaderboards.Category> categories = List.of();

    /**
     * Creates the slash commands; call {@link #register()} and register this as JDA event listener.
     *
     * @param guild     the Discord server of the mod's channels
     * @param linking   the account linking
     * @param server    the running server
     * @param startTime when the server was started, for the uptime
     * @param stats      the player statistics, for "online since"
     * @param moderation the moderation, for the moderation commands
     */
    SlashCommands(Guild guild, Linking linking, MinecraftServer server, Instant startTime, PlayerStats stats,
                  Moderation moderation) {
        this.guild = guild;
        this.linking = linking;
        this.server = server;
        this.startTime = startTime;
        this.stats = stats;
        this.moderation = moderation;
    }

    /**
     * Registers the commands on the Discord server, replacing older versions of them. Reads the leaderboard
     * categories from the current config. Must be called while the server is running.
     */
    void register() {
        categories = Leaderboards.resolve(ConfigManager.get().leaderboard());
        List<SubcommandData> leaderboards = new ArrayList<>();
        if (!categories.isEmpty()) {
            OptionData category = new OptionData(OptionType.STRING, "category", "The statistic", true);
            categories.forEach(entry -> category.addChoice(entry.name(), entry.id()));
            leaderboards.add(new SubcommandData("top", "Show the best players of a statistic").addOptions(category));
            leaderboards.add(new SubcommandData("stats", "Show a player's statistics and ranks")
                    .addOption(OptionType.STRING, "player", "The Minecraft name", true));
        }
        guild.updateCommands().addCommands(
                Commands.slash("mc", "Minecraft server").addSubcommands(
                        new SubcommandData("online", "List the players online on the Minecraft server"),
                        new SubcommandData("status", "Show uptime, TPS, MSPT, players and version"),
                        new SubcommandData("whois", "Show the account linked to a Minecraft or Discord name")
                                .addOption(OptionType.STRING, "name", "Minecraft or Discord name", true),
                        new SubcommandData("playtime", "Show how long a player has played")
                                .addOption(OptionType.STRING, "player", "The Minecraft name", true),
                        new SubcommandData("link", "Link your Minecraft account with the code from the kick message")
                                .addOption(OptionType.STRING, "code", "The code from the kick message", true),
                        new SubcommandData("unlink", "Remove the link to your Minecraft account"))
                        .addSubcommands(leaderboards),
                Commands.slash("mcadmin", "Minecraft server administration")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                        .addSubcommands(
                                new SubcommandData("link", "Link a Minecraft account with a Discord account")
                                        .addOption(OptionType.STRING, "player", "The Minecraft name", true)
                                        .addOption(OptionType.USER, "user", "The Discord user", true),
                                new SubcommandData("reload", "Reload the config of the Minecraft mod"),
                                new SubcommandData("kick", "Kick a player from the Minecraft server")
                                        .addOption(OptionType.STRING, "player", "The Minecraft name", true)
                                        .addOption(OptionType.STRING, "reason", "Why the player is kicked", true),
                                new SubcommandData("ban", "Ban a player, permanently or for a time")
                                        .addOption(OptionType.STRING, "player", "The Minecraft name", true)
                                        .addOption(OptionType.STRING, "reason", "Why the player is banned", true)
                                        .addOption(OptionType.STRING, "duration",
                                                "e.g. 30m, 12h, 7d, 2w or 1d12h; permanent without", false),
                                new SubcommandData("unban", "Lift the ban of a player")
                                        .addOption(OptionType.STRING, "player", "The Minecraft name", true)
                                        .addOption(OptionType.STRING, "reason", "Why the ban is lifted", true),
                                new SubcommandData("banlist", "List the banned players"))
                        .addSubcommandGroups(
                                new SubcommandGroupData("whitelist", "The vanilla whitelist").addSubcommands(
                                        new SubcommandData("add", "Add a player to the whitelist")
                                                .addOption(OptionType.STRING, "player", "The Minecraft name", true)
                                                .addOption(OptionType.STRING, "reason", "Why", false),
                                        new SubcommandData("remove", "Remove a player from the whitelist")
                                                .addOption(OptionType.STRING, "player", "The Minecraft name", true)
                                                .addOption(OptionType.STRING, "reason", "Why", false),
                                        new SubcommandData("list", "List the whitelisted players"),
                                        new SubcommandData("on", "Switch the whitelist on")
                                                .addOption(OptionType.STRING, "reason", "Why", true),
                                        new SubcommandData("off", "Switch the whitelist off")
                                                .addOption(OptionType.STRING, "reason", "Why", false)),
                                new SubcommandGroupData("rolewhitelist", "The whitelist via Discord roles")
                                        .addSubcommands(
                                        new SubcommandData("on", "Switch the role whitelist on")
                                                .addOption(OptionType.STRING, "reason", "Why", true),
                                        new SubcommandData("off", "Switch the role whitelist off")
                                                .addOption(OptionType.STRING, "reason", "Why", false),
                                        new SubcommandData("add", "Whitelist the members of a role")
                                                .addOption(OptionType.ROLE, "role", "The role", true)
                                                .addOption(OptionType.STRING, "reason", "Why", false),
                                        new SubcommandData("remove", "Remove a role from the role whitelist")
                                                .addOption(OptionType.ROLE, "role", "The role", true)
                                                .addOption(OptionType.STRING, "reason", "Why", false),
                                        new SubcommandData("status", "Show whether the role whitelist is on")),
                                new SubcommandGroupData("maintenance", "The maintenance mode").addSubcommands(
                                        new SubcommandData("on", "Switch the maintenance mode on")
                                                .addOption(OptionType.BOOLEAN, "keep_after_restart",
                                                        "Stay on after a restart (default: no)", false)
                                                .addOption(OptionType.STRING, "reason", "Why", false),
                                        new SubcommandData("off", "Switch the maintenance mode off")
                                                .addOption(OptionType.STRING, "reason", "Why", false),
                                        new SubcommandData("status", "Show whether the maintenance mode is on")))
        ).queue(null, error -> Constants.LOG.error("Could not register the Discord slash commands: {}",
                error.getMessage()));
    }

    /**
     * Runs a slash command.
     *
     * @param event the command interaction
     */
    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || event.getGuild().getIdLong() != guild.getIdLong()) {
            return;
        }
        String command = event.getFullCommandName();
        long userId = event.getUser().getIdLong();
        switch (command) {
            case "mc online" -> deferred(event, onServerThread(() ->
                    List.of(CommandEmbeds.online(ServerInfo.online(server, linking.links()::discordId)))));
            case "mc status" -> deferred(event, onServerThread(() ->
                    List.of(CommandEmbeds.status(ServerInfo.status(server, startTime)))));
            case "mc whois" -> {
                String name = option(event, "name");
                deferred(event, CompletableFuture.supplyAsync(() -> CommandEmbeds.whois(name, linking.whois(name))));
            }
            case "mc playtime" -> {
                String player = option(event, "player");
                // Looking up the player may contact Mojang, reading the statistics needs the server thread
                deferred(event, CompletableFuture.supplyAsync(() -> server.getProfileCache().get(player))
                        .thenCompose(profile -> profile.isEmpty()
                                ? CompletableFuture.completedFuture(List.of(CommandEmbeds.error(
                                        "No Minecraft player named **" + DiscordText.escape(player) + "** was found.")))
                                : onServerThread(() -> List.of(CommandEmbeds.playtime(profile.get().getId(),
                                        profile.get().getName(), ServerInfo.playtime(server, profile.get()),
                                        stats.onlineSince(profile.get().getId()))))));
            }
            case "mcadmin kick", "mcadmin ban", "mcadmin unban", "mcadmin banlist", "mcadmin whitelist add",
                 "mcadmin whitelist remove", "mcadmin whitelist list", "mcadmin whitelist on",
                 "mcadmin whitelist off", "mcadmin rolewhitelist on", "mcadmin rolewhitelist off",
                 "mcadmin rolewhitelist add", "mcadmin rolewhitelist remove", "mcadmin rolewhitelist status",
                 "mcadmin maintenance on", "mcadmin maintenance off", "mcadmin maintenance status" ->
                    moderate(event, command);
            case "mc top" -> top(event);
            case "mc stats" -> stats(event);
            case "mcadmin reload" -> deferred(event, onServerThread(() ->
                    List.of(CommandEmbeds.success(MinecraftDiscordLink.reloadConfig()))));
            case "mc link" -> reply(event, CommandEmbeds.reply(linking.linkWithKickCode(userId, option(event,
                    "code"))));
            case "mc unlink" -> reply(event, CommandEmbeds.reply(linking.unlinkDiscord(userId)));
            case "mcadmin link" -> {
                String player = option(event, "player");
                User user = event.getOption("user", OptionMapping::getAsUser);
                // Looking up the player may contact Mojang, so the answer is deferred
                deferred(event, CompletableFuture.supplyAsync(() ->
                        List.of(CommandEmbeds.reply(linking.adminLink(player, user.getIdLong())))));
            }
            default -> {
                // Not a command of this mod
            }
        }
    }

    /**
     * Runs a moderation command of {@code /mcadmin}. Players are looked up first, as that may contact Mojang; the
     * action itself runs on the server thread.
     *
     * @param event   the command interaction
     * @param command the full command name, e.g. {@code mcadmin whitelist add}
     */
    private void moderate(SlashCommandInteractionEvent event, String command) {
        String reason = event.getOption("reason", OptionMapping::getAsString);
        ModerationContext.Scope scope = ModerationContext.Scope.of(
                Moderation.DISCORD_PREFIX + "@" + event.getUser().getName(), reason);
        switch (command) {
            case "mcadmin kick" -> {
                String player = option(event, "player");
                deferred(event, onServerThread(() -> answer(moderation.kick(player, reason, scope))));
            }
            case "mcadmin ban" -> {
                String input = event.getOption("duration", OptionMapping::getAsString);
                Optional<Duration> duration = input == null ? Optional.empty() : Durations.parse(input);
                if (input != null && duration.isEmpty()) {
                    reply(event, CommandEmbeds.error("Invalid duration **" + DiscordText.escape(input)
                            + "**. Use e.g. 30m, 12h, 7d, 2w or 1d12h."));
                    return;
                }
                withPlayer(event, profile -> moderation.ban(profile, reason, duration, scope));
            }
            case "mcadmin unban" -> withPlayer(event, profile -> moderation.unban(profile, reason, scope));
            case "mcadmin whitelist add" -> withPlayer(event, profile -> moderation.whitelistAdd(profile, scope));
            case "mcadmin whitelist remove" ->
                    withPlayer(event, profile -> moderation.whitelistRemove(profile, scope));
            case "mcadmin whitelist on", "mcadmin whitelist off" -> deferred(event, onServerThread(() ->
                    answer(moderation.whitelistSwitch(command.endsWith("on"), scope))));
            case "mcadmin rolewhitelist on", "mcadmin rolewhitelist off" -> deferred(event, onServerThread(() ->
                    answer(moderation.roleWhitelistSwitch(command.endsWith("on"), scope))));
            case "mcadmin rolewhitelist add", "mcadmin rolewhitelist remove" -> {
                long roleId = event.getOption("role", OptionMapping::getAsRole).getIdLong();
                boolean add = command.endsWith("add");
                deferred(event, onServerThread(() -> answer(add ? moderation.roleWhitelistAdd(roleId, scope)
                        : moderation.roleWhitelistRemove(roleId, scope))));
            }
            case "mcadmin banlist" -> deferred(event, onServerThread(() ->
                    List.of(CommandEmbeds.banlist(moderation.bans()))));
            case "mcadmin whitelist list" -> deferred(event, onServerThread(() ->
                    List.of(CommandEmbeds.whitelist(moderation.whitelisted(), moderation.whitelistOn()))));
            case "mcadmin rolewhitelist status" -> reply(event,
                    CommandEmbeds.roleWhitelist(ConfigManager.get().whitelist()));
            case "mcadmin maintenance on", "mcadmin maintenance off" -> {
                boolean on = command.endsWith("on");
                boolean persistent = Boolean.TRUE.equals(event.getOption("keep_after_restart",
                        OptionMapping::getAsBoolean));
                deferred(event, onServerThread(() -> answer(MinecraftDiscordLink.maintenance()
                        .map(maintenance -> moderation.maintenance(maintenance, on, persistent, scope))
                        .orElse(Linking.Reply.error("The server is not running.")))));
            }
            case "mcadmin maintenance status" -> reply(event, CommandEmbeds.maintenance(
                    MinecraftDiscordLink.maintenance().flatMap(Maintenance::status)));
            default -> {
                // Not a moderation command
            }
        }
    }

    /**
     * Looks up the player of the {@code player} option and runs an action with it on the server thread.
     *
     * @param event  the command interaction
     * @param action the action
     */
    private void withPlayer(SlashCommandInteractionEvent event, Function<GameProfile, Linking.Reply> action) {
        String player = option(event, "player");
        deferred(event, CompletableFuture.supplyAsync(() -> server.getProfileCache().get(player))
                .thenCompose(profile -> profile.isEmpty()
                        ? CompletableFuture.completedFuture(List.of(CommandEmbeds.error(
                                "No Minecraft player named **" + DiscordText.escape(player) + "** was found.")))
                        : onServerThread(() -> answer(action.apply(profile.get())))));
    }

    /**
     * Converts the plain text result of a moderation action into the answer.
     *
     * @param reply the result
     * @return the answer embeds
     */
    private static List<MessageEmbed> answer(Linking.Reply reply) {
        return List.of(CommandEmbeds.reply(new Linking.Reply(reply.success(), DiscordText.escape(reply.message()))));
    }

    /**
     * Runs {@code /mc top}: the best players of one leaderboard category.
     *
     * @param event the command interaction
     */
    private void top(SlashCommandInteractionEvent event) {
        String id = option(event, "category");
        Leaderboards.Category category = categories.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElse(null);
        if (category == null) {
            reply(event, CommandEmbeds.error("This category does not exist anymore."));
            return;
        }
        int size = ConfigManager.get().leaderboard().size();
        deferred(event, Leaderboards.rank(server, List.of(category)).thenApplyAsync(rankings -> List.of(
                CommandEmbeds.top(category, rankings.get(category), size,
                        player -> Leaderboards.playerName(server, player))), server));
    }

    /**
     * Runs {@code /mc stats}: a player's value and rank in every leaderboard category.
     *
     * @param event the command interaction
     */
    private void stats(SlashCommandInteractionEvent event) {
        String player = option(event, "player");
        List<Leaderboards.Category> current = categories;
        // Looking up the player may contact Mojang
        deferred(event, CompletableFuture.supplyAsync(() -> server.getProfileCache().get(player))
                .thenCompose(profile -> profile.isEmpty()
                        ? CompletableFuture.completedFuture(List.of(CommandEmbeds.error(
                                "No Minecraft player named **" + DiscordText.escape(player) + "** was found.")))
                        : Leaderboards.rank(server, current).thenApply(rankings -> List.of(CommandEmbeds.stats(
                                profile.get().getId(), profile.get().getName(), rankings)))));
    }

    /**
     * Answers a command whose answer takes longer than Discord's three seconds: the answer is deferred ("thinking")
     * and sent once it is ready.
     *
     * @param event  the command interaction
     * @param answer the answer embeds, completed later
     */
    private static void deferred(SlashCommandInteractionEvent event, CompletableFuture<List<MessageEmbed>> answer) {
        event.deferReply().queue();
        answer.exceptionally(error -> {
            Constants.LOG.error("Could not run the Discord command /{}: {}", event.getFullCommandName(),
                    error.toString());
            return List.of(CommandEmbeds.error("Something went wrong. See the server log for details."));
        }).thenAccept(embeds -> event.getHook().sendMessageEmbeds(embeds)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue());
    }

    /**
     * Computes an answer on the server thread, as it reads the game state.
     *
     * @param answer computes the answer
     * @return the answer, completed on the server thread
     */
    private CompletableFuture<List<MessageEmbed>> onServerThread(Supplier<List<MessageEmbed>> answer) {
        return server.submit(answer);
    }

    /**
     * Answers a command publicly, without mentions.
     *
     * @param event the command interaction
     * @param embed the answer
     */
    private static void reply(SlashCommandInteractionEvent event, MessageEmbed embed) {
        event.replyEmbeds(embed).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
    }

    /**
     * Reads a required string option.
     *
     * @param event the command interaction
     * @param name  the option name
     * @return the option value
     */
    private static String option(SlashCommandInteractionEvent event, String name) {
        return event.getOption(name, OptionMapping::getAsString);
    }
}
