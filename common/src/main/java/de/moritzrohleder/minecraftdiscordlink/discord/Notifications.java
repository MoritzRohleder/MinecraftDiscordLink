package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.linking.LinkStore;
import de.moritzrohleder.minecraftdiscordlink.notifications.NotificationEvent;
import de.moritzrohleder.minecraftdiscordlink.notifications.SubscriptionStore;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends direct messages to Discord users who subscribed to server events or to players coming online.
 * <p>
 * Players coming online are reported at most once per {@code notifications.player_cooldown_minutes} per subscriber,
 * so a player reconnecting does not cause a flood of messages. Users are not told about their own linked player.
 * Users who do not accept direct messages are skipped; their subscriptions stay.
 */
final class Notifications {

    private final JDA jda;
    private final SubscriptionStore store;
    private final LinkStore links;
    /** When a subscriber was last told about a player, by subscriber and player. */
    private final Map<String, Instant> lastPlayerNotice = new ConcurrentHashMap<>();

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
                .setFooter("Unsubscribe with /mc unsubscribe event:" + event.id()).build();
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
        Duration cooldown = Duration.ofMinutes(config.playerCooldownMinutes());
        List<Long> recipients = store.subscribers(player).stream()
                .filter(id -> ownAccount.isEmpty() || ownAccount.getAsLong() != id)
                .filter(id -> {
                    Instant last = lastPlayerNotice.get(id + "/" + player);
                    return last == null || !Duration.between(last, now).minus(cooldown).isNegative();
                })
                .toList();
        recipients.forEach(id -> lastPlayerNotice.put(id + "/" + player, now));
        MessageEmbed embed = new EmbedBuilder()
                .setDescription("🟢 **" + DiscordText.escape(name) + "** is now online on the Minecraft server.")
                .setColor(EmbedColors.GREEN).setTimestamp(now)
                .setFooter("Unsubscribe with /mc unsubscribe player:" + name).build();
        sendAll(recipients, embed);
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
