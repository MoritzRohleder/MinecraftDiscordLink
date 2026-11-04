package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.console.CommandPermissions;
import de.moritzrohleder.minecraftdiscordlink.console.ConsoleCapture;
import de.moritzrohleder.minecraftdiscordlink.console.ConsoleFormat;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.minecraft.server.MinecraftServer;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The live console in the console channel.
 * <p>
 * Console output captured by {@link ConsoleCapture} is sent in batches every {@code console.batch_interval_ms}, as
 * few messages as possible, without any mentions.
 * <p>
 * Messages of users in the channel are run as console commands if {@link CommandPermissions} allows it; the bot
 * then reacts with ✅ and writes the command to the audit log. Commands of other users, multi-line messages and
 * messages of bots and webhooks are ignored.
 */
final class ConsoleChannel extends ListenerAdapter {

    /** Reaction on commands that were run. */
    private static final Emoji EXECUTED = Emoji.fromUnicode("✅");
    /** Reaction on commands the author may not run. */
    private static final Emoji DENIED = Emoji.fromUnicode("⛔");

    private final GuildMessageChannel channel;
    private final MinecraftServer server;
    private final ScheduledExecutorService executor;

    /** Whether this channel was replaced by another one after a config reload. */
    private volatile boolean stopped;

    /**
     * Creates the console channel; call {@link #start()} to begin sending console output and register it as JDA
     * event listener to run commands.
     *
     * @param channel  the console channel
     * @param server   the running server
     * @param executor executor sending the console output
     */
    ConsoleChannel(GuildMessageChannel channel, MinecraftServer server, ScheduledExecutorService executor) {
        this.channel = channel;
        this.server = server;
        this.executor = executor;
    }

    /**
     * Starts sending the captured console output.
     */
    void start() {
        schedule(0);
    }

    /**
     * Stops sending console output and running commands, e.g. when the console channel was changed by a reload.
     * Remove this as JDA event listener as well.
     */
    void stop() {
        stopped = true;
    }

    /**
     * Runs commands written in the console channel.
     *
     * @param event the received message
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        Message message = event.getMessage();
        Member member = event.getMember();
        if (stopped || !ConfigManager.get().console().enabled()
                || event.getChannel().getIdLong() != channel.getIdLong() || member == null || event.getAuthor().isBot()
                || message.isWebhookMessage() || message.getType().isSystem()) {
            return;
        }
        String command = withLowerCaseName(message.getContentRaw().strip());
        if (command.isEmpty() || command.contains("\n")) {
            return;
        }
        List<Long> roleIds = member.getRoles().stream().map(Role::getIdLong).toList();
        if (!CommandPermissions.isAllowed(ConfigManager.get().console().permissions(), member.getIdLong(), roleIds,
                command)) {
            react(message, DENIED);
            return;
        }

        ConsoleCapture.AUDIT.info("{} ({}) ran a command from Discord: {}", event.getAuthor().getName(),
                member.getId(), command);
        // Moderation actions of the command are logged with the Discord user as moderator
        ModerationContext.Scope moderator = ModerationContext.Scope.of(
                Moderation.DISCORD_PREFIX + "@" + event.getAuthor().getName() + " (console)", null);
        server.execute(() -> ModerationContext.run(moderator, () ->
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command)));
        react(message, EXECUTED);
    }

    /**
     * Writes the name of a command in lower case, as Minecraft commands are case-sensitive and phones often
     * capitalize the first letter: {@code List} becomes {@code list}, {@code Say Hello} becomes {@code say Hello}.
     *
     * @param command the command as written
     * @return the command with its name in lower case; the arguments are kept
     */
    static String withLowerCaseName(String command) {
        int space = command.indexOf(' ');
        return space < 0 ? command.toLowerCase(Locale.ROOT)
                : command.substring(0, space).toLowerCase(Locale.ROOT) + command.substring(space);
    }

    /**
     * Reacts to a command: ✅ when it was run, ⛔ when the author may not run it.
     *
     * @param message  the command message
     * @param reaction the reaction
     */
    private void react(Message message, Emoji reaction) {
        try {
            message.addReaction(reaction).queue(null, error ->
                    ConsoleCapture.LOG.error("Could not react to a command: {}", error.getMessage()));
        } catch (InsufficientPermissionException e) {
            PermissionWarnings.missing(e.getPermission().getName(), "in the console channel",
                    "the reaction on commands");
        }
    }

    /**
     * Sends the captured console output and schedules the next batch.
     */
    private void flush() {
        if (stopped) {
            return;
        }
        try {
            List<String> lines = ConsoleCapture.drain();
            // Switched off: the lines are dropped, so they do not pile up
            for (String text : ConsoleFormat.messages(ConfigManager.get().console().enabled() ? lines : List.of())) {
                channel.sendMessage(text).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                        .queue(null, error -> ConsoleCapture.LOG.error("Could not send console output: {}",
                                error.getMessage()));
            }
        } catch (RuntimeException e) {
            ConsoleCapture.LOG.error("Could not send console output: {}", e.toString());
        }
        schedule(ConfigManager.get().console().batchIntervalMs());
    }

    /**
     * Schedules the next batch. Stops silently once the executor is shut down.
     *
     * @param delayMs the delay in milliseconds
     */
    private void schedule(long delayMs) {
        try {
            executor.schedule(this::flush, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // The bot is shutting down
        }
    }
}
