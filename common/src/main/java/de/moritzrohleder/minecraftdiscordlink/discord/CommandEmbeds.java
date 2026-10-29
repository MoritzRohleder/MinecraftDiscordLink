package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.info.ServerInfo;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking.Reply;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking.WhoisEntry;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The embeds answering the Discord slash commands.
 * <p>
 * Names are escaped, Discord users are shown as mentions (answers allow no mentions, so nobody is pinged) and the
 * color of the bar shows the kind of answer: green for success, red for errors, gray for "nothing found", blurple for
 * information. Player heads use {@code chat.avatar_url}.
 */
final class CommandEmbeds {

    /** Discord shows at most ten embeds per message. */
    static final int MAX_EMBEDS = 10;

    private CommandEmbeds() {
    }

    /**
     * Builds the answer of {@code /mc online}: one player per line, linked players with their Discord account.
     *
     * @param online the online players
     * @return the embed
     */
    static MessageEmbed online(ServerInfo.Online online) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🟢 " + online.players().size() + "/" + online.max() + " players online");
        if (online.players().isEmpty()) {
            return embed.setDescription("Nobody is online.").setColor(EmbedColors.GRAY).build();
        }
        String list = online.players().stream()
                .map(player -> "**" + DiscordText.escape(player.name()) + "**"
                        + player.discordId().stream().mapToObj(id -> " · <@" + id + ">").findFirst().orElse(""))
                .collect(Collectors.joining("\n"));
        return embed.setDescription(list).setColor(EmbedColors.GREEN).build();
    }

    /**
     * Builds the answer of {@code /mc status}. The color shows the performance: green from 19 TPS, yellow from 15,
     * otherwise red.
     *
     * @param status the server status
     * @return the embed
     */
    static MessageEmbed status(ServerInfo.Status status) {
        Color color = status.tps() >= 19 ? EmbedColors.GREEN
                : status.tps() >= 15 ? EmbedColors.YELLOW
                : EmbedColors.RED;
        return new EmbedBuilder()
                .setTitle("📊 Server status")
                .addField("Uptime", status.uptime(), true)
                .addField("TPS", status.formattedTps(), true)
                .addField("MSPT", status.formattedMspt(), true)
                .addField("Players", status.players() + "/" + status.max(), true)
                .addField("Version", status.version(), true)
                .setColor(color)
                .build();
    }

    /**
     * Builds the answer of {@code /mc whois}: one embed per found account, at most {@link #MAX_EMBEDS}.
     *
     * @param name    the name that was looked up
     * @param entries the found accounts
     * @return the embeds
     */
    static List<MessageEmbed> whois(String name, List<WhoisEntry> entries) {
        if (entries.isEmpty()) {
            return List.of(error("No Minecraft player or Discord member named **" + DiscordText.escape(name)
                    + "** was found."));
        }
        return entries.stream().limit(MAX_EMBEDS).map(CommandEmbeds::whoisEntry).toList();
    }

    /**
     * Builds the answer of {@code /mc playtime}.
     *
     * @param uuid     the player's UUID, for the head
     * @param name     the player's name
     * @param playtime the play time, empty if the player has never played
     * @return the embed
     */
    static MessageEmbed playtime(UUID uuid, String name, Optional<Duration> playtime) {
        EmbedBuilder embed = new EmbedBuilder().setTitle(DiscordText.escape(name)).setThumbnail(head(uuid, name));
        return playtime
                .map(duration -> embed.setDescription("Play time: **" + Durations.format(duration) + "**")
                        .setColor(EmbedColors.BLURPLE))
                .orElseGet(() -> embed.setDescription("Has not played on this server yet.")
                        .setColor(EmbedColors.GRAY))
                .build();
    }

    /**
     * Builds the answer of an action such as {@code /mc link}: green on success, red on failure.
     *
     * @param reply the result of the action
     * @return the embed
     */
    static MessageEmbed reply(Reply reply) {
        return reply.success() ? success(reply.message()) : error(reply.message());
    }

    /**
     * Builds a success answer.
     *
     * @param message the message, Discord Markdown
     * @return the embed
     */
    static MessageEmbed success(String message) {
        return new EmbedBuilder().setDescription("✅ " + message).setColor(EmbedColors.GREEN).build();
    }

    /**
     * Builds an error answer.
     *
     * @param message the message, Discord Markdown
     * @return the embed
     */
    static MessageEmbed error(String message) {
        return new EmbedBuilder().setDescription("❌ " + message).setColor(EmbedColors.RED).build();
    }

    /**
     * Builds the embed of one account found by {@code whois}.
     *
     * @param entry the account
     * @return the embed
     */
    private static MessageEmbed whoisEntry(WhoisEntry entry) {
        EmbedBuilder embed = new EmbedBuilder();
        if (entry.minecraftName().isPresent()) {
            String name = entry.minecraftName().get();
            embed.setAuthor(name, null, entry.minecraftUuid().map(uuid -> head(uuid, name)).orElse(null));
        } else {
            embed.setAuthor("@" + entry.discordUsername().orElse("unknown"));
        }
        embed.addField("Minecraft", entry.minecraftName().map(DiscordText::escape).orElse("—"), true);
        embed.addField("Discord", entry.discordId().isPresent() ? "<@" + entry.discordId().getAsLong() + ">"
                : "—", true);
        if (entry.linked()) {
            embed.addField("Linked since", entry.linkedAt()
                    .map(time -> "<t:" + time.getEpochSecond() + ":D>")
                    .orElse("unknown"), true);
            embed.setColor(EmbedColors.GREEN);
        } else {
            embed.setDescription("Not linked");
            embed.setColor(EmbedColors.GRAY);
        }
        return embed.build();
    }

    /**
     * Builds the URL of a player head.
     *
     * @param uuid the player's UUID
     * @param name the player's name
     * @return the URL from {@code chat.avatar_url}
     */
    private static String head(UUID uuid, String name) {
        return ChatBridge.avatarUrl(ConfigManager.get().chat(), uuid, name);
    }
}
