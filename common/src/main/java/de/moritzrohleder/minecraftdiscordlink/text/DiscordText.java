package de.moritzrohleder.minecraftdiscordlink.text;

import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.fellbaum.jemoji.Emoji;
import net.fellbaum.jemoji.EmojiManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts texts between the Minecraft chat and Discord.
 * <p>
 * Minecraft has no emoji picker and no mentions, so players write emojis as {@code :name:} shortcodes and mentions as
 * {@code @name}. Discord only turns these into emojis and mentions while typing in its own client, so the mod
 * converts them itself. In the other direction, Unicode emojis are written back as shortcodes, as the Minecraft font
 * cannot display most of them.
 */
public final class DiscordText {

    /** A shortcode such as {@code :skull:} that was not converted to a Unicode emoji. */
    private static final Pattern SHORTCODE = Pattern.compile(":([A-Za-z0-9_]{2,32}):");
    /** A mention such as {@code @steve}, not preceded by a word character (e.g. in e-mail addresses). */
    private static final Pattern MENTION = Pattern.compile("(?<![\\w@<])@([\\w.]{2,32})");
    /** Words Discord does not allow in webhook names. */
    private static final Pattern FORBIDDEN_WEBHOOK_WORDS = Pattern.compile("(?i)(d)(iscord)|(c)(lyde)");
    /** A zero-width space, used to break up forbidden words in webhook names. */
    private static final String ZERO_WIDTH_SPACE = "​";
    /** Discord's maximum length of a webhook name. */
    private static final int MAX_WEBHOOK_NAME_LENGTH = 80;

    private DiscordText() {
    }

    /**
     * Converts emoji shortcodes from Minecraft into Discord emojis.
     * <p>
     * Shortcodes of Unicode emojis (e.g. {@code :skull:}) become the emoji itself. Remaining shortcodes that name a
     * custom emoji of the Discord server become its mention (e.g. {@code <:creeper:123>}). Unknown shortcodes are
     * kept.
     *
     * @param text         the text written in Minecraft
     * @param customEmojis mentions of the server's custom emojis by lower case name
     * @return the text with Discord emojis
     */
    public static String emojisToDiscord(String text, Map<String, String> customEmojis) {
        String unicode = EmojiManager.replaceAliases(text, (alias, emojis) -> emojis.getFirst().getEmoji());
        Matcher matcher = SHORTCODE.matcher(unicode);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String custom = customEmojis.get(matcher.group(1).toLowerCase(Locale.ROOT));
            matcher.appendReplacement(result, Matcher.quoteReplacement(custom != null ? custom : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Converts Unicode emojis from Discord into shortcodes for Minecraft, e.g. 💀 into {@code :skull:}. Discord's own
     * names are preferred, so the shortcodes match what Discord users type.
     *
     * @param text the text from Discord
     * @return the text with shortcodes instead of Unicode emojis
     */
    public static String emojisToMinecraft(String text) {
        return EmojiManager.replaceAllEmojis(text, DiscordText::shortcode);
    }

    /**
     * Converts mentions written in Minecraft ({@code @name}) into Discord mentions ({@code <@id>}). Names that cannot
     * be resolved to exactly one member stay plain text.
     *
     * @param text     the text written in Minecraft
     * @param resolver resolves a name to the ID of a member, empty if unknown or ambiguous
     * @return the text with Discord mentions
     */
    public static String mentionsToDiscord(String text, Function<String, OptionalLong> resolver) {
        Matcher matcher = MENTION.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            OptionalLong id = resolver.apply(matcher.group(1));
            String replacement = id.isPresent() ? "<@" + id.getAsLong() + ">" : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Makes a name usable as webhook name: Discord rejects names containing "discord" or "clyde" and names longer
     * than 80 characters.
     *
     * @param name the wanted name
     * @return a name Discord accepts, looking the same as {@code name}
     */
    public static String webhookName(String name) {
        String sanitized = FORBIDDEN_WEBHOOK_WORDS.matcher(name)
                .replaceAll(match -> Matcher.quoteReplacement(match.group(1) != null
                        ? match.group(1) + ZERO_WIDTH_SPACE + match.group(2)
                        : match.group(3) + ZERO_WIDTH_SPACE + match.group(4)));
        return sanitized.length() > MAX_WEBHOOK_NAME_LENGTH
                ? sanitized.substring(0, MAX_WEBHOOK_NAME_LENGTH)
                : sanitized;
    }

    /**
     * Escapes Discord Markdown in a text and shows the first occurrence of a name in bold, e.g. for death messages.
     *
     * @param text the text, e.g. {@code Steve was slain by Zombie}
     * @param name the name to highlight, e.g. {@code Steve}
     * @return the escaped text with the name in bold, e.g. {@code **Steve** was slain by Zombie}
     */
    public static String boldName(String text, String name) {
        String escapedText = MarkdownSanitizer.escape(text);
        String escapedName = MarkdownSanitizer.escape(name);
        int index = escapedText.indexOf(escapedName);
        if (index < 0) {
            return escapedText;
        }
        return escapedText.substring(0, index) + "**" + escapedName + "**"
                + escapedText.substring(index + escapedName.length());
    }

    /**
     * Escapes Discord Markdown, e.g. in player names containing underscores.
     *
     * @param text the text
     * @return the text shown literally in Discord
     */
    public static String escape(String text) {
        return MarkdownSanitizer.escape(text);
    }

    /**
     * Finds the shortcode of an emoji.
     *
     * @param emoji the emoji
     * @return its first Discord shortcode, otherwise its first other shortcode, otherwise the emoji itself
     */
    private static String shortcode(Emoji emoji) {
        List<String> aliases = emoji.getDiscordAliases().isEmpty() ? emoji.getAllAliases() : emoji.getDiscordAliases();
        return aliases.isEmpty() ? emoji.getEmoji() : aliases.getFirst();
    }
}
