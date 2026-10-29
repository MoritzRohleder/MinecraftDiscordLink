package de.moritzrohleder.minecraftdiscordlink.info;

import de.moritzrohleder.minecraftdiscordlink.linking.Linking.WhoisEntry;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Texts of the information commands ({@code online}, {@code status}, {@code whois}, {@code playtime}), the same for
 * Minecraft and Discord.
 * <p>
 * For Discord ({@code markdown = true}) names are escaped, numbers and names are bold and Discord users are shown as
 * mentions (which do not ping, as the answers allow no mentions). For Minecraft the texts are plain and Discord users
 * are shown as {@code @username}.
 */
public final class InfoText {

    private InfoText() {
    }

    /**
     * Formats the online players, e.g. {@code **2/20** players online: Alex, Steve}.
     *
     * @param online   the online players
     * @param markdown whether the text is for Discord
     * @return the text
     */
    public static String online(ServerInfo.Online online, boolean markdown) {
        String count = bold(online.names().size() + "/" + online.max(), markdown);
        if (online.names().isEmpty()) {
            return count + " players online";
        }
        String names = online.names().stream().map(name -> name(name, markdown)).collect(Collectors.joining(", "));
        return count + " players online: " + names;
    }

    /**
     * Formats the server status, one value per line.
     *
     * @param status   the status
     * @param markdown whether the text is for Discord
     * @return the text
     */
    public static String status(ServerInfo.Status status, boolean markdown) {
        return String.join("\n",
                label("Uptime", markdown) + status.uptime(),
                label("TPS", markdown) + status.formattedTps(),
                label("MSPT", markdown) + status.formattedMspt(),
                label("Players", markdown) + status.players() + "/" + status.max(),
                label("Version", markdown) + status.version());
    }

    /**
     * Formats the result of a {@code whois} lookup, one line per account, e.g. {@code Steve is linked to @steve}.
     *
     * @param name     the name that was looked up
     * @param entries  the found accounts
     * @param markdown whether the text is for Discord
     * @return the text
     */
    public static String whois(String name, List<WhoisEntry> entries, boolean markdown) {
        if (entries.isEmpty()) {
            return "No Minecraft player or Discord member named " + name(name, markdown) + " was found.";
        }
        return entries.stream().map(entry -> whoisLine(entry, markdown)).collect(Collectors.joining("\n"));
    }

    /**
     * Formats the play time of a player, e.g. {@code Steve has played 12h 34m}.
     *
     * @param name     the player's name
     * @param playtime the play time, empty if the player has never played
     * @param markdown whether the text is for Discord
     * @return the text
     */
    public static String playtime(String name, Optional<Duration> playtime, boolean markdown) {
        return playtime.map(duration -> name(name, markdown) + " has played " + bold(Durations.format(duration),
                        markdown))
                .orElse(name(name, markdown) + " has not played on this server yet.");
    }

    /**
     * Formats the answer for an unknown Minecraft name.
     *
     * @param name     the name
     * @param markdown whether the text is for Discord
     * @return the text
     */
    public static String unknownPlayer(String name, boolean markdown) {
        return "No Minecraft player named " + name(name, markdown) + " was found.";
    }

    /**
     * Formats one account of a {@code whois} lookup.
     *
     * @param entry    the account
     * @param markdown whether the text is for Discord
     * @return the line
     */
    private static String whoisLine(WhoisEntry entry, boolean markdown) {
        String discordUser = entry.discordId().isEmpty() ? null
                : markdown ? "<@" + entry.discordId().getAsLong() + ">"
                : "@" + entry.discordUsername().orElse(String.valueOf(entry.discordId().getAsLong()));
        if (entry.linked()) {
            return name(entry.minecraftName().orElseThrow(), markdown) + " is linked to " + discordUser;
        }
        return entry.minecraftName().map(minecraftName -> name(minecraftName, markdown)).orElse(discordUser)
                + " is not linked";
    }

    /**
     * Formats a name: escaped and bold for Discord.
     *
     * @param name     the name
     * @param markdown whether the text is for Discord
     * @return the formatted name
     */
    private static String name(String name, boolean markdown) {
        return markdown ? bold(DiscordText.escape(name), true) : name;
    }

    /**
     * Formats a label of the status.
     *
     * @param label    the label
     * @param markdown whether the text is for Discord
     * @return e.g. {@code **TPS:** }
     */
    private static String label(String label, boolean markdown) {
        return bold(label + ":", markdown) + " ";
    }

    /**
     * Makes a text bold for Discord.
     *
     * @param text     the text
     * @param markdown whether the text is for Discord
     * @return the text, bold for Discord
     */
    private static String bold(String text, boolean markdown) {
        return markdown ? "**" + text + "**" : text;
    }
}
