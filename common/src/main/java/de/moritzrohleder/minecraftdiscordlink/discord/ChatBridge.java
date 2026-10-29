package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.MinecraftText;
import de.moritzrohleder.minecraftdiscordlink.text.Placeholders;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.WebhookClient;
import net.dv8tion.jda.api.entities.channel.attribute.IWebhookContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.awt.Color;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static net.dv8tion.jda.api.entities.Message.MentionType;

/**
 * Bridge between the Minecraft chat and the Discord chat channel.
 * <p>
 * Minecraft to Discord: chat messages are sent via webhook as the player, events as embeds. Without a webhook the bot
 * sends them itself. Emoji shortcodes and {@code @name} mentions are converted; only users can be mentioned, never
 * {@code @everyone}, {@code @here} or roles.
 * <p>
 * Discord to Minecraft: messages of users in the chat channel are shown in the Minecraft chat. Messages of bots and
 * webhooks, system messages and messages consisting only of attachments are ignored.
 * <p>
 * The send methods may be called from any thread; sending happens asynchronously through JDA.
 */
public final class ChatBridge extends ListenerAdapter {

    /**
     * Name of the webhook the bot creates for {@code webhook = "auto"}. Discord does not allow "discord" in webhook
     * names; the name is not shown in messages, as each message sets its own name.
     */
    static final String WEBHOOK_NAME = "Minecraft Chat Bridge";

    /** Mentions that may ping: only users. */
    private static final EnumSet<MentionType> ALLOWED_MENTIONS = EnumSet.of(MentionType.USER);
    /** Discord's maximum length of a message. */
    private static final int MAX_MESSAGE_LENGTH = 2000;
    /** A custom emoji as written in a message, e.g. {@code <:creeper:123>}. */
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");

    private final JDA jda;
    private final GuildMessageChannel channel;
    private final MinecraftServer server;
    private final Linking linking;

    /** The webhook used to send messages, {@code null} for plain bot messages. */
    private volatile WebhookClient<Message> webhook;
    /** Whether the bridge still forwards messages; {@code false} once the bot shuts down. */
    private volatile boolean active = true;

    /**
     * Creates the bridge. Call {@link #setUpWebhook()} before using it and register it as JDA event listener.
     *
     * @param jda     the connected bot
     * @param channel the chat channel
     * @param server  the running server
     * @param linking the account linking, for the names of linked users
     */
    ChatBridge(JDA jda, GuildMessageChannel channel, MinecraftServer server, Linking linking) {
        this.jda = jda;
        this.channel = channel;
        this.server = server;
        this.linking = linking;
    }

    /**
     * Sets up the webhook from {@code chat.webhook}. Blocks while talking to Discord, so it must not run on the
     * server thread. If the webhook cannot be set up, an error is logged and plain bot messages are used.
     */
    void setUpWebhook() {
        ModConfig.Webhook config = ConfigManager.get().chat().webhook();
        webhook = switch (config.mode()) {
            case NONE -> null;
            case URL -> WebhookClient.createClient(jda, config.url().orElseThrow());
            case AUTO -> findOrCreateWebhook();
        };
    }

    /**
     * Finds the webhook named {@link #WEBHOOK_NAME} created by the bot in the chat channel, or creates it.
     *
     * @return the webhook, or {@code null} if it cannot be set up
     */
    private WebhookClient<Message> findOrCreateWebhook() {
        if (!(channel instanceof IWebhookContainer container)) {
            Constants.LOG.error("The chat channel does not support webhooks. Plain bot messages are used.");
            return null;
        }
        try {
            long selfId = jda.getSelfUser().getIdLong();
            List<Webhook> webhooks = container.retrieveWebhooks().complete();
            for (Webhook existing : webhooks) {
                if (WEBHOOK_NAME.equals(existing.getName()) && existing.getOwnerAsUser() != null
                        && existing.getOwnerAsUser().getIdLong() == selfId) {
                    return existing;
                }
            }
            return container.createWebhook(WEBHOOK_NAME).complete();
        } catch (InsufficientPermissionException e) {
            Constants.LOG.error("Could not set up the chat webhook: the bot needs the 'Manage Webhooks' permission "
                    + "in the chat channel. Plain bot messages are used.");
            return null;
        } catch (RuntimeException e) {
            Constants.LOG.error("Could not set up the chat webhook ({}). Plain bot messages are used.",
                    e.getMessage());
            return null;
        }
    }

    /**
     * Stops forwarding messages, e.g. when the bot shuts down.
     */
    void deactivate() {
        active = false;
    }

    /**
     * Sends a chat message of a player.
     *
     * @param uuid    the player's UUID, for the avatar
     * @param name    the name to show
     * @param message the message text as written in Minecraft; Discord Markdown is kept
     */
    public void sendPlayerMessage(UUID uuid, String name, String message) {
        if (!active) {
            return;
        }
        String text = limit(toDiscord(message));
        if (text.isBlank()) {
            return;
        }
        ModConfig.Chat config = ConfigManager.get().chat();
        WebhookClient<Message> client = webhook;
        if (client != null) {
            client.sendMessage(text)
                    .setUsername(DiscordText.webhookName(name))
                    .setAvatarUrl(avatarUrl(config, uuid, name))
                    .setAllowedMentions(ALLOWED_MENTIONS)
                    .queue(null, this::logSendError);
        } else {
            String formatted = Placeholders.resolve(config.discordFormat(),
                    Map.of("player", DiscordText.escape(name), "message", text));
            channel.sendMessage(limit(formatted)).setAllowedMentions(ALLOWED_MENTIONS)
                    .queue(null, this::logSendError);
        }
    }

    /**
     * Sends a message as the bot itself, e.g. for {@code /say} from the console. Emojis and mentions are converted
     * like in player messages.
     *
     * @param text the message; Discord Markdown is kept
     */
    public void sendBotMessage(String text) {
        if (!active) {
            return;
        }
        String converted = limit(toDiscord(text));
        if (converted.isBlank()) {
            return;
        }
        channel.sendMessage(converted).setAllowedMentions(ALLOWED_MENTIONS).queue(null, this::logSendError);
    }

    /**
     * Sends an event of a player (join, leave, death, advancement) as embed, via webhook as the player.
     *
     * @param uuid        the player's UUID, for the avatar
     * @param name        the name to show as webhook name
     * @param description the embed text, already in Discord format
     * @param color       the color of the embed bar
     */
    public void sendPlayerEvent(UUID uuid, String name, String description, Color color) {
        if (!active) {
            return;
        }
        MessageEmbed embed = new EmbedBuilder().setDescription(toDiscordEmojis(description)).setColor(color).build();
        WebhookClient<Message> client = webhook;
        if (client != null) {
            client.sendMessageEmbeds(embed)
                    .setUsername(DiscordText.webhookName(name))
                    .setAvatarUrl(avatarUrl(ConfigManager.get().chat(), uuid, name))
                    .queue(null, this::logSendError);
        } else {
            channel.sendMessageEmbeds(embed).queue(null, this::logSendError);
        }
    }

    /**
     * Forwards messages from the chat channel to Minecraft.
     *
     * @param event the received message
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        Message message = event.getMessage();
        if (!active || event.getChannel().getIdLong() != channel.getIdLong()
                || event.getAuthor().isBot() || event.getAuthor().isSystem() || message.isWebhookMessage()
                || message.getType().isSystem()) {
            return;
        }

        String text = DiscordText.emojisToMinecraft(
                CUSTOM_EMOJI.matcher(message.getContentDisplay()).replaceAll(":$1:")).strip();
        if (text.isEmpty()) {
            // Only attachments, stickers or embeds
            return;
        }
        Member member = event.getMember();
        String discordName = member != null ? member.getEffectiveName() : event.getAuthor().getEffectiveName();
        String name = linking.displayName(event.getAuthor().getIdLong(), discordName);
        Component component = MinecraftText.discordMessage(ConfigManager.get().chat().minecraftFormat(), name, text);
        server.execute(() -> server.getPlayerList().broadcastSystemMessage(component, false));
    }

    /**
     * Converts a message written in Minecraft for Discord: emojis and mentions.
     *
     * @param message the message from Minecraft
     * @return the message for Discord
     */
    private String toDiscord(String message) {
        return DiscordText.mentionsToDiscord(toDiscordEmojis(message), this::findMember);
    }

    /**
     * Converts emoji shortcodes, including the custom emojis of the Discord server.
     *
     * @param text the text from Minecraft
     * @return the text with Discord emojis
     */
    private String toDiscordEmojis(String text) {
        Map<String, String> customEmojis = channel.getGuild().getEmojiCache().stream()
                .collect(Collectors.toMap(emoji -> emoji.getName().toLowerCase(Locale.ROOT),
                        RichCustomEmoji::getAsMention, (first, second) -> first));
        return DiscordText.emojisToDiscord(text, customEmojis);
    }

    /**
     * Finds the member mentioned by {@code @name}: by username first, then by server nickname.
     *
     * @param name the name after the {@code @}
     * @return the member's ID, empty if no member or several members have this nickname
     */
    private OptionalLong findMember(String name) {
        List<Member> members = channel.getGuild().getMemberCache().asList();
        for (Member member : members) {
            if (member.getUser().getName().equalsIgnoreCase(name)) {
                return OptionalLong.of(member.getIdLong());
            }
        }
        List<Member> byNickname = members.stream()
                .filter(member -> member.getNickname() != null && member.getNickname().equalsIgnoreCase(name))
                .toList();
        return byNickname.size() == 1 ? OptionalLong.of(byNickname.getFirst().getIdLong()) : OptionalLong.empty();
    }

    /**
     * Builds the avatar URL of a player from {@code chat.avatar_url}.
     *
     * @param config the chat settings
     * @param uuid   the player's UUID
     * @param name   the player's name
     * @return the avatar URL
     */
    private static String avatarUrl(ModConfig.Chat config, UUID uuid, String name) {
        return Placeholders.resolve(config.avatarUrl(),
                Map.of("uuid", uuid.toString().replace("-", ""), "name", name));
    }

    /**
     * Cuts a text to Discord's maximum message length.
     *
     * @param text the text
     * @return the text, at most {@link #MAX_MESSAGE_LENGTH} characters long
     */
    private static String limit(String text) {
        return text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) : text;
    }

    /**
     * Logs a message that could not be sent.
     *
     * @param error the error
     */
    private void logSendError(Throwable error) {
        Constants.LOG.error("Could not send a message to the Discord chat channel: {}", error.getMessage());
    }
}
