package de.moritzrohleder.minecraftdiscordlink.console;

import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Formats console lines for the console channel and splits them into Discord messages.
 * <p>
 * Each log entry becomes one line {@code `HH:mm:ss` **LEVEL** message}. The message is shown exactly as in the
 * console: Discord Markdown is escaped, color codes are removed.
 */
public final class ConsoleFormat {

    /** Discord's maximum length of a message. */
    public static final int MAX_MESSAGE_LENGTH = 2000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** ANSI escape sequences (terminal colors) and Minecraft formatting codes ({@code §a}). */
    private static final Pattern COLOR_CODES = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]|§[0-9a-fk-orA-FK-OR]");

    private ConsoleFormat() {
    }

    /**
     * Formats a log entry as one line for Discord.
     *
     * @param timeMillis the time of the entry, in milliseconds since the epoch
     * @param level      the log level, e.g. {@code INFO}
     * @param message    the log message, may contain several lines
     * @return the formatted line
     */
    public static String line(long timeMillis, String level, String message) {
        String time = TIME.format(Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()));
        String clean = COLOR_CODES.matcher(message).replaceAll("");
        return "`" + time + "` **" + level + "** " + MarkdownSanitizer.escape(clean);
    }

    /**
     * Joins lines into as few Discord messages as possible. Lines are never split between two messages, unless a
     * single line is longer than {@link #MAX_MESSAGE_LENGTH}.
     *
     * @param lines the formatted lines
     * @return the messages, each at most {@link #MAX_MESSAGE_LENGTH} characters long
     */
    public static List<String> messages(List<String> lines) {
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            for (int start = 0; start < line.length(); start += MAX_MESSAGE_LENGTH) {
                String part = line.substring(start, Math.min(line.length(), start + MAX_MESSAGE_LENGTH));
                int separator = current.isEmpty() ? 0 : 1;
                if (current.length() + separator + part.length() > MAX_MESSAGE_LENGTH) {
                    messages.add(current.toString());
                    current.setLength(0);
                    separator = 0;
                }
                if (separator == 1) {
                    current.append('\n');
                }
                current.append(part);
            }
        }
        if (!current.isEmpty()) {
            messages.add(current.toString());
        }
        return messages;
    }
}
