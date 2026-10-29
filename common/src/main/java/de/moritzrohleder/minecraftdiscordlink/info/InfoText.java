package de.moritzrohleder.minecraftdiscordlink.info;

import de.moritzrohleder.minecraftdiscordlink.linking.Linking.WhoisEntry;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Plain texts of the information commands in Minecraft. Discord answers with embeds instead, see
 * {@code CommandEmbeds}.
 */
public final class InfoText {

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
        if (entry.linked()) {
            return entry.minecraftName().orElseThrow() + " is linked to " + discordUser;
        }
        return entry.minecraftName().orElse(discordUser) + " is not linked";
    }
}
