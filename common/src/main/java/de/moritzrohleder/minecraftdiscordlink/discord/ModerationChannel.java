package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationAction;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationEntry;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;

import java.awt.Color;
import java.util.EnumSet;
import java.util.OptionalLong;

/**
 * Sends the moderation log to the channel {@code moderation.channel_id}, one embed per action.
 * <p>
 * The channel is looked up for every entry, so it can be changed by a config reload.
 */
final class ModerationChannel {

    private final JDA jda;
    /** The last channel ID reported as unavailable, so the error is logged only once. */
    private volatile long reportedChannel;

    /**
     * Creates the sender.
     *
     * @param jda the bot
     */
    ModerationChannel(JDA jda) {
        this.jda = jda;
    }

    /**
     * Sends a moderation log entry, if a moderation channel is configured.
     *
     * @param entry the entry
     */
    void send(ModerationEntry entry) {
        OptionalLong channelId = ConfigManager.get().moderation().channelId();
        if (channelId.isEmpty()) {
            return;
        }
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, channelId.getAsLong());
        if (channel == null) {
            if (reportedChannel != channelId.getAsLong()) {
                reportedChannel = channelId.getAsLong();
                Constants.LOG.error("The moderation channel {} was not found or the bot cannot see it.",
                        channelId.getAsLong());
            }
            return;
        }
        channel.sendMessageEmbeds(embed(entry)).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                .queue(null, error -> Constants.LOG.error("Could not send to the moderation channel: {}",
                        error.getMessage()));
    }

    /**
     * Builds the embed of an entry, e.g. "🔨 Banned Steve" with the moderator, duration and reason.
     *
     * @param entry the entry
     * @return the embed
     */
    static MessageEmbed embed(ModerationEntry entry) {
        ModerationAction action = entry.action();
        String title = emoji(action) + " " + action.title()
                + (action.hasTarget() ? " " + DiscordText.escape(entry.target()) : "");
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(title)
                .setColor(color(action))
                .setTimestamp(entry.time());
        entry.player().ifPresent(uuid -> embed.setThumbnail(
                ChatBridge.avatarUrl(ConfigManager.get().chat(), uuid, entry.target())));
        if (action == ModerationAction.BAN_EXPIRED) {
            return embed.build();
        }
        embed.addField("Moderator", DiscordText.escape(entry.moderator()), true);
        if (action == ModerationAction.BAN || action == ModerationAction.IP_BAN) {
            embed.addField("Duration", entry.until()
                    .map(end -> Durations.formatCompact(entry.duration().orElseThrow()) + " (ends <t:"
                            + end.getEpochSecond() + ":R>)")
                    .orElse("permanent"), true);
        }
        embed.addField("Reason", entry.reason().map(DiscordText::escape).orElse(ModerationEntry.NO_REASON), false);
        return embed.build();
    }

    /**
     * Returns the emoji of an action.
     *
     * @param action the action
     * @return the emoji
     */
    private static String emoji(ModerationAction action) {
        return switch (action) {
            case KICK -> "👢";
            case BAN, IP_BAN -> "🔨";
            case UNBAN, IP_UNBAN, BAN_EXPIRED -> "🔓";
            default -> "📋";
        };
    }

    /**
     * Returns the color of an action: kicks yellow, bans red, unbans green, whitelist changes blue.
     *
     * @param action the action
     * @return the color
     */
    private static Color color(ModerationAction action) {
        return switch (action) {
            case KICK -> EmbedColors.YELLOW;
            case BAN, IP_BAN -> EmbedColors.RED;
            case UNBAN, IP_UNBAN, BAN_EXPIRED -> EmbedColors.GREEN;
            default -> EmbedColors.BLUE;
        };
    }
}
