package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.info.ServerInfo;
import de.moritzrohleder.minecraftdiscordlink.leaderboard.Leaderboards;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
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
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
    /** The leaderboard categories of the registered commands. */
    private volatile List<Leaderboards.Category> categories = List.of();

    /**
     * Creates the slash commands; call {@link #register()} and register this as JDA event listener.
     *
     * @param guild     the Discord server of the mod's channels
     * @param linking   the account linking
     * @param server    the running server
     * @param startTime when the server was started, for the uptime
     */
    SlashCommands(Guild guild, Linking linking, MinecraftServer server, Instant startTime) {
        this.guild = guild;
        this.linking = linking;
        this.server = server;
        this.startTime = startTime;
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
                                new SubcommandData("reload", "Reload the config of the Minecraft mod"))
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
        String command = event.getName() + " " + event.getSubcommandName();
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
                                        profile.get().getName(), ServerInfo.playtime(server, profile.get()))))));
            }
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
