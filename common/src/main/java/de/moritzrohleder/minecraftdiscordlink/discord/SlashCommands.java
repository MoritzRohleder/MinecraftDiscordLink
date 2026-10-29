package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.info.InfoText;
import de.moritzrohleder.minecraftdiscordlink.info.ServerInfo;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The Discord slash commands {@code /mc} (for everyone) and {@code /mcadmin} (for administrators).
 * <p>
 * The commands are registered for the mod's Discord server only, so they are available immediately. Discord itself
 * restricts {@code /mcadmin} to members with the Administrator permission; server admins can change that in the
 * integration settings of the Discord server. Answers are public in the channel, without mentions.
 */
final class SlashCommands extends ListenerAdapter {

    private final Guild guild;
    private final Linking linking;
    private final MinecraftServer server;
    private final Instant startTime;

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
     * Registers the commands on the Discord server, replacing older versions of them.
     */
    void register() {
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
                        new SubcommandData("unlink", "Remove the link to your Minecraft account")),
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
                    InfoText.online(ServerInfo.online(server), true)));
            case "mc status" -> deferred(event, onServerThread(() ->
                    InfoText.status(ServerInfo.status(server, startTime), true)));
            case "mc whois" -> {
                String name = option(event, "name");
                deferred(event, CompletableFuture.supplyAsync(() -> InfoText.whois(name, linking.whois(name), true)));
            }
            case "mc playtime" -> {
                String player = option(event, "player");
                // Looking up the player may contact Mojang, reading the statistics needs the server thread
                deferred(event, CompletableFuture.supplyAsync(() -> server.getProfileCache().get(player))
                        .thenCompose(profile -> profile.isEmpty()
                                ? CompletableFuture.completedFuture(InfoText.unknownPlayer(player, true))
                                : onServerThread(() -> InfoText.playtime(profile.get().getName(),
                                        ServerInfo.playtime(server, profile.get()), true))));
            }
            case "mcadmin reload" -> deferred(event, onServerThread(MinecraftDiscordLink::reloadConfig));
            case "mc link" -> reply(event, linking.linkWithKickCode(userId, option(event, "code")));
            case "mc unlink" -> reply(event, linking.unlinkDiscord(userId));
            case "mcadmin link" -> {
                String player = option(event, "player");
                User user = event.getOption("user", OptionMapping::getAsUser);
                // Looking up the player may contact Mojang, so the answer is deferred
                deferred(event, CompletableFuture.supplyAsync(() -> linking.adminLink(player, user.getIdLong())));
            }
            default -> {
                // Not a command of this mod
            }
        }
    }

    /**
     * Answers a command whose answer takes longer than Discord's three seconds: the answer is deferred ("thinking")
     * and sent once it is ready.
     *
     * @param event  the command interaction
     * @param answer the answer, completed later
     */
    private static void deferred(SlashCommandInteractionEvent event, CompletableFuture<String> answer) {
        event.deferReply().queue();
        answer.exceptionally(error -> {
            Constants.LOG.error("Could not run the Discord command /{}: {}", event.getFullCommandName(),
                    error.toString());
            return "Something went wrong. See the server log for details.";
        }).thenAccept(text -> event.getHook().sendMessage(text)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue());
    }

    /**
     * Computes an answer on the server thread, as it reads the game state.
     *
     * @param answer computes the answer
     * @return the answer, completed on the server thread
     */
    private CompletableFuture<String> onServerThread(Supplier<String> answer) {
        return server.submit(answer);
    }

    /**
     * Answers a command publicly, without mentions.
     *
     * @param event the command interaction
     * @param text  the answer
     */
    private static void reply(SlashCommandInteractionEvent event, String text) {
        event.reply(text).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
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
