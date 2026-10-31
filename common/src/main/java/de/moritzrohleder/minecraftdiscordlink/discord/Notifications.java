package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.linking.LinkStore;
import de.moritzrohleder.minecraftdiscordlink.notifications.NotificationEvent;
import de.moritzrohleder.minecraftdiscordlink.notifications.SubscriptionStore;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Messages;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sends direct messages to Discord users who subscribed to server events or to players coming online.
 * <p>
 * Users are not told about their own linked player.
 * Users who do not accept direct messages are skipped; their subscriptions stay.
 */
final class Notifications {

    private final JDA jda;
    private final SubscriptionStore store;
    private final LinkStore links;

    /**
     * Creates the notifications.
     *
     * @param jda   the bot
     * @param store the subscriptions
     * @param links the account links, to skip a user's own player
     */
    Notifications(JDA jda, SubscriptionStore store, LinkStore links) {
        this.jda = jda;
        this.store = store;
        this.links = links;
    }

    /**
     * Notifies the subscribers of an event.
     *
     * @param event the event
     * @param text  the message, Discord Markdown
     * @param color the color of the embed
     * @return completes when all messages were sent or failed
     */
    CompletableFuture<Void> onEvent(NotificationEvent event, String text, Color color) {
        if (!ConfigManager.get().notifications().enabled()) {
            return CompletableFuture.completedFuture(null);
        }
        MessageEmbed embed = new EmbedBuilder().setDescription(text).setColor(color).setTimestamp(Instant.now())
                .setFooter("Unsubscribe with /mc unsubscribe event:" + event.id() + serverOption()).build();
        return sendAll(store.subscribers(event), embed);
    }

    /**
     * Notifies the subscribers of a player who came online.
     *
     * @param player the player's UUID
     * @param name   the player's name
     */
    void onPlayerJoin(UUID player, String name) {
        ModConfig.Notifications config = ConfigManager.get().notifications();
        if (!config.enabled()) {
            return;
        }
        OptionalLong ownAccount = links.discordId(player);
        Instant now = Instant.now();
        List<Long> recipients = store.subscribers(player).stream()
                .filter(id -> ownAccount.isEmpty() || ownAccount.getAsLong() != id)
                .toList();
        MessageEmbed embed = new EmbedBuilder()
                .setDescription(Messages.discord(ModConfig.Messages::notifyPlayerOnline,
                        withPlayer(DiscordText.escape(name))))
                .setColor(EmbedColors.GREEN).setTimestamp(now)
                .setFooter("Unsubscribe with /mc unsubscribe player:" + name + serverOption()).build();
        sendAll(recipients, embed);
    }

    /**
     * Returns the placeholders naming this server in notification texts: {@code {server}} is
     * {@code the Minecraft server}, or with {@code server.name} e.g. {@code the Minecraft server **Survival**};
     * {@code {Server}} is the same with a capital letter.
     *
     * @return the placeholder values
     */
    static Map<String, String> serverValues() {
        ModConfig.Server server = ConfigManager.get().server();
        String label = "the Minecraft server" + (server.named() ? " **" + DiscordText.escape(server.name()) + "**"
                : "");
        return Map.of("server", label, "Server", "T" + label.substring(1));
    }

    /**
     * Returns the server placeholders plus {@code {player}}.
     *
     * @param player the player's name, escaped
     * @return the placeholder values
     */
    private static Map<String, String> withPlayer(String player) {
        Map<String, String> values = new HashMap<>(serverValues());
        values.put("player", player);
        return values;
    }

    /**
     * Returns the {@code server} option for the unsubscribe hint.
     *
     * @return e.g. {@code  server:Survival}, empty for a single server
     */
    private static String serverOption() {
        ModConfig.Server server = ConfigManager.get().server();
        return server.named() ? " server:" + server.name() : "";
    }

    /**
     * Sends an embed to users by direct message.
     *
     * @param recipients the Discord user IDs
     * @param embed      the message
     * @return completes when all messages were sent or failed
     */
    private CompletableFuture<Void> sendAll(List<Long> recipients, MessageEmbed embed) {
        CompletableFuture<?>[] sent = recipients.stream()
                .map(id -> jda.openPrivateChannelById(id)
                        .flatMap(channel -> channel.sendMessageEmbeds(embed))
                        .submit()
                        .exceptionally(error -> {
                            Constants.LOG.warn("Could not send a notification to the Discord user {}: {}", id,
                                    error.getMessage());
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(sent);
    }
}
