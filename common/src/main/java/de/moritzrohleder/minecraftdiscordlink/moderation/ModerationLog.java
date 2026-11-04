package de.moritzrohleder.minecraftdiscordlink.moderation;

import de.moritzrohleder.minecraftdiscordlink.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.function.Consumer;

/**
 * The moderation log: every entry gets a number in the {@link ModerationHistory} and goes to the server console, to
 * {@code minecraftdiscordlink/moderation.log} and to a listener, which sends it to the moderation channel.
 * <p>
 * All methods are thread-safe.
 */
public final class ModerationLog {

    /** Name of the log file inside the data directory. */
    public static final String FILE_NAME = "moderation.log";

    private final Path file;
    private final ZoneId zone;
    private final ModerationHistory history;
    private volatile Consumer<ModerationEntry> listener = entry -> { };

    /**
     * Creates the moderation log.
     *
     * @param dataDirectory the mod's data directory
     * @param zone          the time zone of the times in the file, normally the server's
     */
    public ModerationLog(Path dataDirectory, ZoneId zone) {
        this.file = dataDirectory.resolve(FILE_NAME);
        this.zone = zone;
        this.history = new ModerationHistory(dataDirectory, zone);
    }

    /**
     * Returns the moderation history with all numbered entries.
     *
     * @return the history
     */
    public ModerationHistory history() {
        return history;
    }

    /**
     * Returns the time zone of the times in the log, normally the server's.
     *
     * @return the time zone
     */
    public ZoneId zone() {
        return zone;
    }

    /**
     * Sets the listener called for every entry, e.g. to send it to Discord. It must not block.
     *
     * @param listener the listener
     */
    public void setListener(Consumer<ModerationEntry> listener) {
        this.listener = listener;
    }

    /**
     * Logs a moderation action, numbering it.
     *
     * @param action the action, without number
     * @return the numbered entry
     */
    public ModerationEntry record(ModerationEntry action) {
        ModerationEntry entry = history.add(action);
        String line = entry.toLogLine(zone);
        Constants.LOG.info("[Moderation] {}", line);
        synchronized (this) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                Constants.LOG.error("Could not write the moderation log {}: {}", file, e.getMessage());
            }
        }
        try {
            listener.accept(entry);
        } catch (RuntimeException e) {
            Constants.LOG.error("Could not send the moderation log entry to Discord: {}", e.toString());
        }
        return entry;
    }
}
