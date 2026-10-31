package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.info.ServerInfo;
import de.moritzrohleder.minecraftdiscordlink.leaderboard.Leaderboards.Category;
import de.moritzrohleder.minecraftdiscordlink.leaderboard.Ranking;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking.Reply;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking.WhoisEntry;
import de.moritzrohleder.minecraftdiscordlink.maintenance.Maintenance;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
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
     * Builds the answer of {@code /mc playtime}, for online players with the time since they joined.
     *
     * @param uuid        the player's UUID, for the head
     * @param name        the player's name
     * @param playtime    the play time, empty if the player has never played
     * @param onlineSince when the player joined, empty if the player is not online
     * @return the embed
     */
    static MessageEmbed playtime(UUID uuid, String name, Optional<Duration> playtime, Optional<Instant> onlineSince) {
        EmbedBuilder embed = new EmbedBuilder().setTitle(DiscordText.escape(name)).setThumbnail(head(uuid, name));
        return playtime
                .map(duration -> embed.setDescription("Play time: **" + Durations.format(duration) + "**"
                                + onlineSince.map(time -> "\n🟢 Online since <t:" + time.getEpochSecond() + ":R>")
                                        .orElse(""))
                        .setColor(onlineSince.isPresent() ? EmbedColors.GREEN : EmbedColors.BLURPLE))
                .orElseGet(() -> embed.setDescription("Has not played on this server yet.")
                        .setColor(EmbedColors.GRAY))
                .build();
    }

    /**
     * Builds the answer of {@code /mc top}: the best players of one category, the first three with medals.
     *
     * @param category the category
     * @param ranking  the ranking of the category
     * @param size     the number of players shown
     * @param names    returns the name of a player
     * @return the embed
     */
    static MessageEmbed top(Category category, Ranking ranking, int size, Function<UUID, String> names) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("🏆 " + category.name());
        if (ranking.entries().isEmpty()) {
            return embed.setDescription("Nobody has played on this server yet.").setColor(EmbedColors.GRAY).build();
        }
        String list = ranking.top(size).stream()
                .map(entry -> place(entry.rank()) + " **" + DiscordText.escape(names.apply(entry.player())) + "** · "
                        + category.format().format(entry.value()))
                .collect(Collectors.joining("\n"));
        return embed.setDescription(list)
                .setFooter(players(ranking.entries().size()) + " ranked"
                        + (category.lowestFirst() ? " · lowest value first" : ""))
                .setColor(EmbedColors.GOLD)
                .build();
    }

    /**
     * Builds the answer of {@code /mc stats}: the player's value and rank in every category, e.g.
     * {@code Deaths: 1st · 0}.
     *
     * @param uuid     the player's UUID, for the head
     * @param name     the player's name
     * @param rankings the ranking of every category
     * @return the embed
     */
    static MessageEmbed stats(UUID uuid, String name, Map<Category, Ranking> rankings) {
        EmbedBuilder embed = new EmbedBuilder().setAuthor(name, null, head(uuid, name));
        StringBuilder lines = new StringBuilder();
        int players = 0;
        for (Map.Entry<Category, Ranking> category : rankings.entrySet()) {
            Optional<Ranking.Entry> entry = category.getValue().find(uuid);
            if (entry.isEmpty()) {
                return embed.setDescription("Has not played on this server yet.").setColor(EmbedColors.GRAY).build();
            }
            players = category.getValue().entries().size();
            lines.append("**").append(DiscordText.escape(category.getKey().name())).append(":** ")
                    .append(Ranking.ordinal(entry.get().rank())).append(" · ")
                    .append(category.getKey().format().format(entry.get().value())).append('\n');
        }
        return embed.setDescription(lines.toString().strip())
                .setFooter("Ranked among " + players(players))
                .setColor(EmbedColors.BLURPLE)
                .build();
    }

    /**
     * Builds the answer of {@code /mcadmin banlist}: one ban per line with source, end and reason.
     *
     * @param bans the bans
     * @return the embed
     */
    static MessageEmbed banlist(List<Moderation.Ban> bans) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("🔨 " + players(bans.size()) + " banned");
        if (bans.isEmpty()) {
            return embed.setDescription("Nobody is banned.").setColor(EmbedColors.GRAY).build();
        }
        List<String> lines = bans.stream()
                .map(ban -> "**" + DiscordText.escape(ban.player()) + "** · by " + DiscordText.escape(ban.source())
                        + " · " + ban.until().map(end -> "until <t:" + end.getEpochSecond() + ":f>")
                        .orElse("permanent")
                        + " · " + DiscordText.escape(ban.reason()))
                .toList();
        return embed.setDescription(limitLines(lines)).setColor(EmbedColors.RED).build();
    }

    /**
     * Builds the answer of {@code /mcadmin whitelist list}.
     *
     * @param players the whitelisted players
     * @param on      whether the whitelist is on
     * @return the embed
     */
    static MessageEmbed whitelist(List<String> players, boolean on) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📋 Whitelist " + (on ? "on" : "off") + " · " + players(players.size()));
        if (players.isEmpty()) {
            return embed.setDescription("Nobody is whitelisted.").setColor(EmbedColors.GRAY).build();
        }
        return embed.setDescription(limitLines(players.stream().map(DiscordText::escape).toList()))
                .setColor(EmbedColors.BLUE).build();
    }

    /**
     * Builds the answer of {@code /mcadmin rolewhitelist status}.
     *
     * @param whitelist the role whitelist settings
     * @return the embed
     */
    static MessageEmbed roleWhitelist(ModConfig.Whitelist whitelist) {
        String state = whitelist.active() ? "on"
                : whitelist.enabled() ? "on, but inactive without roles"
                : "off";
        String roles = whitelist.roleIds().isEmpty() ? "No roles configured."
                : whitelist.roleIds().stream().map(id -> "<@&" + id + ">").collect(Collectors.joining("\n"));
        return new EmbedBuilder().setTitle("🛡️ Role whitelist " + state).setDescription(roles)
                .setColor(whitelist.active() ? EmbedColors.BLUE : EmbedColors.GRAY).build();
    }

    /**
     * Builds the answer of {@code /mcadmin maintenance status}.
     *
     * @param status the maintenance status, empty if off
     * @return the embed
     */
    static MessageEmbed maintenance(Optional<Maintenance.Status> status) {
        if (status.isEmpty()) {
            return new EmbedBuilder().setTitle("🔧 Maintenance mode off").setColor(EmbedColors.GRAY).build();
        }
        Maintenance.Status current = status.get();
        return new EmbedBuilder().setTitle("🔧 Maintenance mode on")
                .addField("Since", "<t:" + current.since().getEpochSecond() + ":R>", true)
                .addField("By", DiscordText.escape(current.moderator()), true)
                .addField("After a restart", current.persistent() ? "stays on" : "ends", true)
                .addField("Reason", current.reason().map(DiscordText::escape).orElse("no reason given"), false)
                .setColor(EmbedColors.BLURPLE)
                .build();
    }

    /**
     * Builds the answer of {@code /mc subscriptions}.
     *
     * @param events  the names of the subscribed events
     * @param players the names of the subscribed players
     * @return the embed
     */
    static MessageEmbed subscriptions(List<String> events, List<String> players) {
        EmbedBuilder embed = new EmbedBuilder().setTitle("🔔 Your notifications");
        if (events.isEmpty() && players.isEmpty()) {
            return embed.setDescription("You have no notifications. Add one with `/mc subscribe`.")
                    .setColor(EmbedColors.GRAY).build();
        }
        return embed.addField("Events", events.isEmpty() ? "—" : String.join("\n", events), true)
                .addField("Players", players.isEmpty() ? "—"
                        : players.stream().map(DiscordText::escape).collect(Collectors.joining("\n")), true)
                .setColor(EmbedColors.BLURPLE).build();
    }

    /**
     * Joins lines for an embed description, leaving out lines beyond Discord's limit of 4096 characters.
     *
     * @param lines the lines
     * @return the description, ending with "… and N more" if lines were left out
     */
    private static String limitLines(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String more = "\n… and " + (lines.size() - i) + " more";
            if (text.length() + lines.get(i).length() + 1 + more.length() > MessageEmbed.DESCRIPTION_MAX_LENGTH) {
                return text.append(more).toString().strip();
            }
            text.append(i == 0 ? "" : "\n").append(lines.get(i));
        }
        return text.toString();
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
        if (entry.minecraftName().isPresent()) {
            embed.addField("Last online", entry.lastOnline().online() ? "Online now"
                    : entry.lastOnline().time().map(time -> "<t:" + time.getEpochSecond() + ":R>").orElse("unknown"),
                    true);
        }
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
     * Shows a rank in a leaderboard: a medal for the first three, otherwise the number.
     *
     * @param rank the rank
     * @return e.g. {@code 🥇} or {@code **4.**}
     */
    private static String place(int rank) {
        return switch (rank) {
            case 1 -> "🥇";
            case 2 -> "🥈";
            case 3 -> "🥉";
            default -> "**" + rank + ".**";
        };
    }

    /**
     * Formats a number of players.
     *
     * @param count the number of players
     * @return e.g. {@code 1 player} or {@code 12 players}
     */
    private static String players(int count) {
        return count + (count == 1 ? " player" : " players");
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
