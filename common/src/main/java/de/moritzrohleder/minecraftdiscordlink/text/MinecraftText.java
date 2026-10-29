package de.moritzrohleder.minecraftdiscordlink.text;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the chat components shown in Minecraft for messages from Discord.
 */
public final class MinecraftText {

    /**
     * The parts of {@code chat.minecraft_format} that are replaced or change the style: the placeholders and
     * formatting codes such as {@code &9}.
     */
    private static final Pattern FORMAT_PARTS = Pattern.compile("\\{name}|\\{message}|&([0-9a-fk-orA-FK-OR])");

    private MinecraftText() {
    }

    /**
     * Builds a Discord message for the Minecraft chat from {@code chat.minecraft_format}.
     * <p>
     * The format may contain Minecraft formatting codes with {@code &} instead of {@code §}: {@code &0}–{@code &f}
     * for colors, {@code &k}–{@code &o} for obfuscated, bold, strikethrough, underlined and italic, {@code &r} to
     * reset. A color resets the other formatting, as in vanilla. Text without a code is white.
     * <p>
     * {@code {name}} and {@code {message}} are inserted as plain text in the current style, so a Discord message can
     * never inject formatting codes.
     *
     * @param format  the configured format, e.g. {@code &9[Discord]&r {name}: {message}}
     * @param name    the name of the Discord user
     * @param message the message text
     * @return the chat component
     */
    public static Component discordMessage(String format, String name, String message) {
        MutableComponent result = Component.empty();
        Style style = Style.EMPTY.withColor(ChatFormatting.WHITE);
        Matcher matcher = FORMAT_PARTS.matcher(format);
        int end = 0;
        while (matcher.find()) {
            append(result, format.substring(end, matcher.start()), style);
            switch (matcher.group()) {
                case "{name}" -> append(result, name, style);
                case "{message}" -> append(result, message, style);
                default -> style = applyCode(style,
                        ChatFormatting.getByCode(matcher.group(1).toLowerCase(Locale.ROOT).charAt(0)));
            }
            end = matcher.end();
        }
        append(result, format.substring(end), style);
        return result;
    }

    /**
     * Applies a formatting code to a style like vanilla does for {@code §} codes.
     *
     * @param style      the current style
     * @param formatting the formatting of the code
     * @return the new style
     */
    private static Style applyCode(Style style, ChatFormatting formatting) {
        if (formatting == ChatFormatting.RESET) {
            return Style.EMPTY.withColor(ChatFormatting.WHITE);
        }
        if (formatting.isColor()) {
            return Style.EMPTY.withColor(formatting);
        }
        return style.applyFormat(formatting);
    }

    /**
     * Appends a text in a style, skipping empty texts.
     *
     * @param component the component to append to
     * @param text      the text
     * @param style     the style of the text
     */
    private static void append(MutableComponent component, String text, Style style) {
        if (!text.isEmpty()) {
            component.append(Component.literal(text).withStyle(style));
        }
    }
}
