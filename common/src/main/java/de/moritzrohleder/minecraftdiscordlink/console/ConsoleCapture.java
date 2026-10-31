package de.moritzrohleder.minecraftdiscordlink.console;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Captures the server console for the console channel and the crash notice of the monitoring channel.
 * <p>
 * A Log4j appender on the root logger receives every log entry that reaches the console (level INFO and above) and
 * keeps it as formatted line until the console channel sends it. Minecraft uses Log4j on all supported loaders.
 * Independently, the last {@link #RECENT_LINES} lines are kept for the crash notice.
 * <p>
 * Entries of the Discord libraries and of the console channel itself are not captured: they are about the
 * connection to Discord, and errors while sending console lines would otherwise be sent again in an endless loop.
 * The audit log of commands run from Discord is not captured either, it only goes to the log files.
 * <p>
 * At most {@link #MAX_LINES} lines are buffered, e.g. while the bot is still connecting; older lines are dropped
 * and replaced by a note how many were skipped.
 */
public final class ConsoleCapture extends AbstractAppender {

    /** Logger for problems of the console channel; not captured, see the class description. */
    public static final Logger LOG = LoggerFactory.getLogger(Constants.MOD_NAME + "/Console");
    /** Logger for the commands run from Discord; written to the log files but not captured. */
    public static final Logger AUDIT = LoggerFactory.getLogger(Constants.MOD_NAME + "/Audit");

    /** Maximum number of buffered lines. */
    static final int MAX_LINES = 5000;

    /** Prefixes of logger names that are not captured. */
    private static final List<String> EXCLUDED_LOGGERS = List.of(
            LOG.getName(), AUDIT.getName(), "net.dv8tion", "okhttp3", "com.neovisionaries");

    /** The installed appender, {@code null} if none. */
    private static ConsoleCapture instance;

    /** Number of recent lines kept for the crash notice. */
    static final int RECENT_LINES = 50;

    private final Deque<String> lines = new ArrayDeque<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private int skipped;
    /** Whether lines are queued for the console channel. */
    private volatile boolean queueing;

    private ConsoleCapture() {
        super(Constants.MOD_NAME + "Console", null, null, true, Property.EMPTY_ARRAY);
    }

    /**
     * Starts capturing the console. Called again, it only switches queueing on.
     *
     * @param queue whether lines are queued for the console channel; otherwise only the recent lines are kept
     */
    public static synchronized void install(boolean queue) {
        if (instance != null) {
            instance.queueing |= queue;
            return;
        }
        ConsoleCapture appender = new ConsoleCapture();
        appender.queueing = queue;
        appender.start();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        configuration.addAppender(appender);
        configuration.getRootLogger().addAppender(appender, Level.INFO, null);
        context.updateLoggers();
        instance = appender;
    }

    /**
     * Stops capturing the console and drops all buffered lines.
     */
    public static synchronized void uninstall() {
        if (instance == null) {
            return;
        }
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        configuration.getRootLogger().removeAppender(instance.getName());
        configuration.getAppenders().remove(instance.getName());
        context.updateLoggers();
        instance.stop();
        instance = null;
    }

    /**
     * Takes all buffered lines.
     *
     * @return the lines in order, empty if not capturing or nothing was logged
     */
    public static List<String> drain() {
        ConsoleCapture appender;
        synchronized (ConsoleCapture.class) {
            appender = instance;
        }
        return appender != null ? appender.takeLines() : List.of();
    }

    /**
     * Buffers a log entry as formatted line. Called by Log4j on the logging thread, so it only formats and stores.
     *
     * @param event the log entry
     */
    @Override
    public void append(LogEvent event) {
        String loggerName = event.getLoggerName() != null ? event.getLoggerName() : "";
        for (String excluded : EXCLUDED_LOGGERS) {
            if (loggerName.startsWith(excluded)) {
                return;
            }
        }
        String message = event.getMessage().getFormattedMessage();
        if (event.getThrown() != null) {
            message += "\n" + event.getThrown();
        }
        String line = ConsoleFormat.line(event.getTimeMillis(), event.getLevel().name(), message);
        synchronized (lines) {
            recent.addLast(line);
            if (recent.size() > RECENT_LINES) {
                recent.removeFirst();
            }
            if (!queueing) {
                return;
            }
            lines.addLast(line);
            while (lines.size() > MAX_LINES) {
                lines.removeFirst();
                skipped++;
            }
        }
    }

    /**
     * Returns the last lines of the console, for the crash notice.
     *
     * @param count the number of lines
     * @return at most {@code count} lines, oldest first; empty if the console is not captured
     */
    public static List<String> recent(int count) {
        ConsoleCapture appender;
        synchronized (ConsoleCapture.class) {
            appender = instance;
        }
        if (appender == null) {
            return List.of();
        }
        synchronized (appender.lines) {
            List<String> all = new ArrayList<>(appender.recent);
            return List.copyOf(all.subList(Math.max(0, all.size() - count), all.size()));
        }
    }

    /**
     * Takes the buffered lines, starting with a note about dropped lines if any.
     *
     * @return the lines
     */
    private List<String> takeLines() {
        synchronized (lines) {
            List<String> result = new ArrayList<>(lines.size() + 1);
            if (skipped > 0) {
                result.add("*… " + skipped + " lines skipped*");
                skipped = 0;
            }
            result.addAll(lines);
            lines.clear();
            return result;
        }
    }
}
