package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
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

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Creates the slash commands; call {@link #register()} and register this as JDA event listener.
     *
     * @param guild   the Discord server of the mod's channels
     * @param linking the account linking
     */
    SlashCommands(Guild guild, Linking linking) {
        this.guild = guild;
        this.linking = linking;
    }

    /**
     * Registers the commands on the Discord server, replacing older versions of them.
     */
    void register() {
        guild.updateCommands().addCommands(
                Commands.slash("mc", "Minecraft server").addSubcommands(
                        new SubcommandData("link", "Link your Minecraft account with the code from the kick message")
                                .addOption(OptionType.STRING, "code", "The code from the kick message", true),
                        new SubcommandData("unlink", "Remove the link to your Minecraft account")),
                Commands.slash("mcadmin", "Minecraft server administration")
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
                        .addSubcommands(
                                new SubcommandData("link", "Link a Minecraft account with a Discord account")
                                        .addOption(OptionType.STRING, "player", "The Minecraft name", true)
                                        .addOption(OptionType.USER, "user", "The Discord user", true))
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
            case "mc link" -> reply(event, linking.linkWithKickCode(userId, option(event, "code")));
            case "mc unlink" -> reply(event, linking.unlinkDiscord(userId));
            case "mcadmin link" -> {
                String player = option(event, "player");
                User user = event.getOption("user", OptionMapping::getAsUser);
                // Looking up the player may contact Mojang, so the answer is deferred
                event.deferReply().queue();
                CompletableFuture.supplyAsync(() -> linking.adminLink(player, user.getIdLong()))
                        .thenAccept(text -> event.getHook().sendMessage(text)
                                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue());
            }
            default -> {
                // Not a command of this mod
            }
        }
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
