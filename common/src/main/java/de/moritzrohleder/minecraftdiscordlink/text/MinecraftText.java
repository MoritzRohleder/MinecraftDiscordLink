package de.moritzrohleder.minecraftdiscordlink.text;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the chat components shown in Minecraft for messages from Discord.
 */
public final class MinecraftText {

    /** The parts of {@code chat.minecraft_format} that are replaced or styled. */
    private static final Pattern FORMAT_PARTS = Pattern.compile("\\{name}|\\{message}|\\[Discord]");

    private MinecraftText() {
    }

    /**
     * Builds a Discord message for the Minecraft chat from {@code chat.minecraft_format}.
     * <p>
     * {@code {name}} and {@code {message}} are inserted as plain text, so a message can never inject formatting.
     * The literal {@code [Discord]} is shown in blue, everything else in white.
     *
     * @param format  the configured format, e.g. {@code [Discord] {name}: {message}}
     * @param name    the name of the Discord user
     * @param message the message text
     * @return the chat component
     */
    public static Component discordMessage(String format, String name, String message) {
        MutableComponent result = Component.empty().withStyle(ChatFormatting.WHITE);
        Matcher matcher = FORMAT_PARTS.matcher(format);
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() > end) {
                result.append(format.substring(end, matcher.start()));
            }
            switch (matcher.group()) {
                case "{name}" -> result.append(name);
                case "{message}" -> result.append(message);
                default -> result.append(Component.literal(matcher.group()).withStyle(ChatFormatting.BLUE));
            }
            end = matcher.end();
        }
        if (end < format.length()) {
            result.append(format.substring(end));
        }
        return result;
    }
}
