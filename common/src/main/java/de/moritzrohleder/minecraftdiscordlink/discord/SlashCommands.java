package de.moritzrohleder.minecraftdiscordlink.discord;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.info.ServerInfo;
import de.moritzrohleder.minecraftdiscordlink.leaderboard.Leaderboards;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.maintenance.Maintenance;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import de.moritzrohleder.minecraftdiscordlink.notifications.NotificationEvent;
import de.moritzrohleder.minecraftdiscordlink.notifications.SubscriptionStore;
import de.moritzrohleder.minecraftdiscordlink.stats.PlayerStats;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.CommandInteractionPayload;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
 * Several Minecraft servers can share one Discord server with the same bot. Every server registers the same commands,
 * each with an optional {@code server} option; the server named there answers, without it the primary server
 * ({@code server.primary}). For an unknown server name the primary server answers with an error after a short delay.
 * {@code /mc link} is answered by the server that gave out the code. Answers of named servers show the server name in
 * the footer. The leaderboard categories are suggested by the answering server (autocomplete), as they may differ.
 */
final class SlashCommands extends ListenerAdapter {

    /** Delay before the primary server answers commands no other server answered, in milliseconds. */
    private static final long FALLBACK_DELAY_MS = 2000;

    private final Guild guild;
    private final Linking linking;
    private final MinecraftServer server;
    private final Instant startTime;
    private final PlayerStats stats;
    private final Moderation moderation;
    private final SubscriptionStore subscriptions;
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
     * @param moderation    the moderation, for the moderation commands
     * @param subscriptions the subscriptions of the notifications
     */
    SlashCommands(Guild guild, Linking linking, MinecraftServer server, Instant startTime, PlayerStats stats,
                  Moderation moderation, SubscriptionStore subscriptions) {
        this.guild = guild;
        this.linking = linking;
        this.server = server;
        this.startTime = startTime;
        this.stats = stats;
        this.moderation = moderation;
        this.subscriptions = subscriptions;
    }

    /**
     * Registers the commands on the Discord server, replacing older versions of them. Reads the leaderboard
     * categories from the current config. Must be called while the server is running.
     */
    void register() {
        categories = Leaderboards.resolve(ConfigManager.get().leaderboard());
        // The same definition on every server sharing the Discord server, so the categories are suggested instead
        List<SubcommandData> leaderboards = List.of(
                new SubcommandData("top", "Show the best players of a statistic")
                        .addOptions(new OptionData(OptionType.STRING, "category", "The statistic", true, true)),
                new SubcommandData("stats", "Show a player's statistics and ranks")
                        .addOption(OptionType.STRING, "player", "The Minecraft name", true));
        guild.updateCommands().addCommands(
                withServerOption(Commands.slash("mc", "Minecraft server").addSubcommands(
                        new SubcommandData("online", "List the players online on the Minecraft server"),
                        new SubcommandData("status", "Show uptime, TPS, MSPT, players and version"),
                        new SubcommandData("whois", "Show the account linked to a Minecraft or Discord name")
                                .addOption(OptionType.STRING, "name", "Minecraft or Discord name", true),
                        new SubcommandData("playtime", "Show how long a player has played")
                                .addOption(OptionType.STRING, "player", "The Minecraft name", true),
                        new SubcommandData("link", "Link your Minecraft account with the code from the kick message")
                                .addOption(OptionType.STRING, "code", "The code from the kick message", true),
                        new SubcommandData("unlink", "Remove the link to your Minecraft account"),
                        new SubcommandData("subscribe", "Get a direct message on a server event or a player joining")
                                .addOptions(eventOption(), new OptionData(OptionType.STRING, "player",
                                        "A Minecraft player", false)),
                        new SubcommandData("unsubscribe", "Stop a notification")
                                .addOptions(eventOption(), new OptionData(OptionType.STRING, "player",
                                        "A Minecraft player", false)),
                        new SubcommandData("subscriptions", "List your notifications"))
                        .addSubcommands(leaderboards)),
                withServerOption(Commands.slash("mcadmin", "Minecraft server administration")
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
                                        new SubcommandData("status", "Show whether the maintenance mode is on"))))
        ).queue(null, error -> Constants.LOG.error("Could not register the Discord slash commands: {}",
                error.getMessage()));
    }

    /**
     * Adds the optional {@code server} option to every subcommand.
     *
     * @param command the command
     * @return the command
     */
    private static SlashCommandData withServerOption(SlashCommandData command) {
        OptionData option = new OptionData(OptionType.STRING, "server",
                "The Minecraft server, if several share this Discord server", false);
        command.getSubcommands().forEach(subcommand -> subcommand.addOptions(option));
        command.getSubcommandGroups().forEach(group -> group.getSubcommands()
                .forEach(subcommand -> subcommand.addOptions(option)));
        return command;
    }

    /**
     * Checks whether this server answers a command: the server named in the {@code server} option, otherwise the
     * primary server.
     *
     * @param interaction the command interaction
     * @return whether this server answers
     */
    private static boolean answers(CommandInteractionPayload interaction) {
        String target = interaction.getOption("server", OptionMapping::getAsString);
        ModConfig.Server server = ConfigManager.get().server();
        return target == null ? server.primary() : server.named() && target.strip().equalsIgnoreCase(server.name());
    }

    /**
     * Answers a command for an unknown server, if this is the primary server: after a short delay, so a server with
     * that name can answer first. Answering an already answered command fails silently.
     *
     * @param event the command interaction
     */
    private static void answerUnknownServer(SlashCommandInteractionEvent event) {
        String target = event.getOption("server", OptionMapping::getAsString);
        if (target == null || !ConfigManager.get().server().primary()) {
            return;
        }
        CompletableFuture.delayedExecutor(FALLBACK_DELAY_MS, TimeUnit.MILLISECONDS).execute(() ->
                event.replyEmbeds(CommandEmbeds.error("No Minecraft server named **" + DiscordText.escape(target)
                                + "** is online."))
                        .setEphemeral(true).queue(null, error -> {
                            // Another server answered
                        }));
    }

    /**
     * Marks answers with the name of this server, if it shares the Discord server with others.
     *
     * @param embeds the answers
     * @return the answers, with the server name in the footer
     */
    static List<MessageEmbed> tag(List<MessageEmbed> embeds) {
        ModConfig.Server server = ConfigManager.get().server();
        if (!server.named()) {
            return embeds;
        }
        return embeds.stream().map(embed -> {
            String footer = embed.getFooter() == null ? null : embed.getFooter().getText();
            return new EmbedBuilder(embed).setFooter(footer == null ? server.name() : footer + " · " + server.name())
                    .build();
        }).toList();
    }

    /**
     * Suggests the leaderboard categories of the answering server for {@code /mc top}.
     *
     * @param event the autocomplete interaction
     */
    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (event.getGuild() == null || event.getGuild().getIdLong() != guild.getIdLong()
                || !event.getFullCommandName().equals("mc top") || !answers(event)) {
            return;
        }
        String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        event.replyChoices(categories.stream()
                .filter(category -> category.name().toLowerCase(Locale.ROOT).contains(typed))
                .limit(OptionData.MAX_CHOICES)
                .map(category -> new Command.Choice(category.name(), category.id()))
                .toList()).queue(null, error -> {
                    // Another server answered
                });
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
        if (command.equals("mc link")) {
            link(event, userId);
            return;
        }
        if (!answers(event)) {
            answerUnknownServer(event);
            return;
        }
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
            case "mc subscribe", "mc unsubscribe" -> subscribe(event, command.equals("mc subscribe"));
            case "mc subscriptions" -> listSubscriptions(event);
            case "mc top" -> top(event);
            case "mc stats" -> stats(event);
            case "mcadmin reload" -> deferred(event, onServerThread(() ->
                    List.of(CommandEmbeds.success(MinecraftDiscordLink.reloadConfig()))));
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
     * Creates the {@code event} option of the subscribe commands, with one choice per event.
     *
     * @return the option
     */
    private static OptionData eventOption() {
        OptionData option = new OptionData(OptionType.STRING, "event", "A server event", false);
        for (NotificationEvent event : NotificationEvent.values()) {
            option.addChoice(event.displayName(), event.id());
        }
        return option;
    }

    /**
     * Runs {@code /mc subscribe} and {@code /mc unsubscribe}. The answer is only visible to the user.
     *
     * @param event     the command interaction
     * @param subscribe whether to subscribe or unsubscribe
     */
    private void subscribe(SlashCommandInteractionEvent event, boolean subscribe) {
        long userId = event.getUser().getIdLong();
        Optional<NotificationEvent> serverEvent = Optional.ofNullable(event.getOption("event",
                OptionMapping::getAsString)).flatMap(NotificationEvent::byId);
        String player = event.getOption("player", OptionMapping::getAsString);
        if (!ConfigManager.get().notifications().enabled()) {
            replyPrivately(event, CommandEmbeds.error("Notifications are switched off on this server."));
            return;
        }
        if (serverEvent.isPresent() == (player != null)) {
            replyPrivately(event, CommandEmbeds.error("Choose either an `event` or a `player`."));
            return;
        }
        if (serverEvent.isPresent()) {
            NotificationEvent chosen = serverEvent.get();
            boolean changed = subscribe ? subscriptions.subscribe(userId, chosen)
                    : subscriptions.unsubscribe(userId, chosen);
            replyPrivately(event, changed
                    ? CommandEmbeds.success((subscribe ? "You will get a direct message on: **"
                    : "You will no longer get a direct message on: **") + chosen.displayName() + "**")
                    : CommandEmbeds.error(subscribe ? "You are already subscribed to this event."
                    : "You are not subscribed to this event."));
            return;
        }
        event.deferReply(true).queue();
        CompletableFuture.supplyAsync(() -> server.getProfileCache().get(player)).thenAccept(profile -> {
            MessageEmbed answer;
            if (profile.isEmpty()) {
                answer = CommandEmbeds.error("No Minecraft player named **" + DiscordText.escape(player)
                        + "** was found.");
            } else {
                String name = DiscordText.escape(profile.get().getName());
                boolean changed = subscribe ? subscriptions.subscribe(userId, profile.get().getId())
                        : subscriptions.unsubscribe(userId, profile.get().getId());
                answer = changed
                        ? CommandEmbeds.success(subscribe ? "You will get a direct message when **" + name
                        + "** joins." : "You will no longer get a direct message when **" + name + "** joins.")
                        : CommandEmbeds.error(subscribe ? "You are already subscribed to **" + name + "**."
                        : "You are not subscribed to **" + name + "**.");
            }
            event.getHook().sendMessageEmbeds(tag(List.of(answer)))
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .queue();
        });
    }

    /**
     * Runs {@code /mc subscriptions}. The answer is only visible to the user.
     *
     * @param event the command interaction
     */
    private void listSubscriptions(SlashCommandInteractionEvent event) {
        SubscriptionStore.Subscriptions own = subscriptions.of(event.getUser().getIdLong());
        event.deferReply(true).queue();
        server.submit(() -> CommandEmbeds.subscriptions(
                own.events().stream().map(NotificationEvent::displayName).sorted().toList(),
                own.players().stream().map(player -> Leaderboards.playerName(server, player))
                        .sorted(String.CASE_INSENSITIVE_ORDER).toList()))
                .thenAccept(embed -> event.getHook().sendMessageEmbeds(tag(List.of(embed))).queue());
    }

    /**
     * Answers a command so only the user sees it.
     *
     * @param event the command interaction
     * @param embed the answer
     */
    private static void replyPrivately(SlashCommandInteractionEvent event, MessageEmbed embed) {
        event.replyEmbeds(tag(List.of(embed))).setEphemeral(true)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
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
     * Runs {@code /mc link <code>}: the server that gave out the code answers at once. If no server has the code, the
     * primary server answers that it is invalid, after a short delay.
     *
     * @param event  the command interaction
     * @param userId the Discord user
     */
    private void link(SlashCommandInteractionEvent event, long userId) {
        String code = option(event, "code");
        if (linking.ownsKickCode(code)) {
            reply(event, CommandEmbeds.reply(linking.linkWithKickCode(userId, code)));
        } else if (ConfigManager.get().server().primary()) {
            CompletableFuture.delayedExecutor(FALLBACK_DELAY_MS, TimeUnit.MILLISECONDS).execute(() ->
                    event.replyEmbeds(tag(List.of(CommandEmbeds.reply(linking.linkWithKickCode(userId, code)))))
                            .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                            .queue(null, error -> {
                                // Another server answered
                            }));
        }
    }

    /**
     * Runs {@code /mc top}: the best players of one leaderboard category.
     *
     * @param event the command interaction
     */
    private void top(SlashCommandInteractionEvent event) {
        String id = option(event, "category").strip();
        Leaderboards.Category category = categories.stream()
                .filter(entry -> entry.id().equals(id) || entry.name().equalsIgnoreCase(id)).findFirst()
                .orElse(null);
        if (category == null) {
            reply(event, CommandEmbeds.error(categories.isEmpty() ? "No leaderboards are configured."
                    : "Unknown category **" + DiscordText.escape(id) + "**. Choose one of the suggestions."));
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
        if (current.isEmpty()) {
            reply(event, CommandEmbeds.error("No leaderboards are configured."));
            return;
        }
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
        }).thenAccept(embeds -> event.getHook().sendMessageEmbeds(tag(embeds))
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
        event.replyEmbeds(tag(List.of(embed))).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
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
