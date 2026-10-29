package de.moritzrohleder.minecraftdiscordlink.text;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces placeholders such as {@code {online}} in configurable texts.
 * <p>
 * Placeholders are names in curly braces. Unknown placeholders are left unchanged, so typos stay visible to the
 * server admin instead of silently disappearing.
 */
public final class Placeholders {

    /** A placeholder: a name of letters, digits and underscores in curly braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9_]+)}");

    private Placeholders() {
    }

    /**
     * Replaces all known placeholders in a text.
     *
     * @param format the text with placeholders
     * @param values the values by placeholder name, without braces
     * @return the text with all known placeholders replaced
     */
    public static String resolve(String format, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(format);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = values.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
