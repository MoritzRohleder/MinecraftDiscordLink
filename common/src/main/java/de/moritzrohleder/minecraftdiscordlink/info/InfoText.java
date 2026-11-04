package de.moritzrohleder.minecraftdiscordlink.info;

import de.moritzrohleder.minecraftdiscordlink.linking.Linking.WhoisEntry;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Plain texts of the information commands in Minecraft. Discord answers with embeds instead, see
 * {@code CommandEmbeds}.
 */
public final class InfoText {

    /** Format of "last online", in the server's time zone. */
    private static final DateTimeFormatter LAST_ONLINE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private InfoText() {
    }

    /**
     * Formats the result of a {@code whois} lookup, one line per account, e.g. {@code Steve is linked to @steve}.
     *
     * @param name    the name that was looked up
     * @param entries the found accounts
     * @return the text
     */
    public static String whois(String name, List<WhoisEntry> entries) {
        if (entries.isEmpty()) {
            return "No Minecraft player or Discord member named " + name + " was found.";
        }
        return entries.stream().map(InfoText::whoisLine).collect(Collectors.joining("\n"));
    }

    /**
     * Formats one account of a {@code whois} lookup.
     *
     * @param entry the account
     * @return the line
     */
    private static String whoisLine(WhoisEntry entry) {
        String discordUser = entry.discordId().isEmpty() ? null
                : "@" + entry.discordUsername().orElse(String.valueOf(entry.discordId().getAsLong()));
        String line = entry.linked()
                ? entry.minecraftName().orElseThrow() + " is linked to " + discordUser
                : entry.minecraftName().orElse(discordUser) + " is not linked";
        if (entry.minecraftName().isEmpty()) {
            return line;
        }
        if (entry.lastOnline().online()) {
            return line + " (online now)";
        }
        return line + entry.lastOnline().time()
                .map(time -> " (last online " + LAST_ONLINE.format(time.atZone(ZoneId.systemDefault())) + ")")
                .orElse("");
    }
}
