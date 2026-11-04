package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Reports missing bot permissions: as an error in the server log and once per permission and place in the
 * monitoring channel, so admins see it without looking at the console.
 * <p>
 * Warnings before the monitoring channel is ready are kept and sent once it is. All methods are thread-safe.
 */
final class PermissionWarnings {

    /** Warnings already reported since the server start. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    /** Warnings waiting for the monitoring channel. */
    private static final List<String> PENDING = new ArrayList<>();
    /** Sends a warning to the monitoring channel, {@code null} until it is ready. */
    private static Consumer<String> channel;

    private PermissionWarnings() {
    }

    /**
     * Reports a missing permission. Repeated reports of the same permission for the same feature are ignored.
     *
     * @param permission the permission as shown in Discord, e.g. {@code Pin Messages}
     * @param where      where it is needed, e.g. {@code in the monitoring channel}
     * @param feature    what needs it, e.g. {@code pinning the status message}
     */
    static void missing(String permission, String where, String feature) {
        String text = "The bot needs the '" + permission + "' permission " + where + " for " + feature + ".";
        if (!REPORTED.add(text)) {
            return;
        }
        Constants.LOG.error(text);
        Consumer<String> target;
        synchronized (PENDING) {
            target = channel;
            if (target == null) {
                PENDING.add(text);
                return;
            }
        }
        target.accept(text);
    }

    /**
     * Connects the monitoring channel and sends the warnings collected so far.
     *
     * @param sender sends a warning to the monitoring channel
     */
    static void connect(Consumer<String> sender) {
        List<String> pending;
        synchronized (PENDING) {
            channel = sender;
            pending = List.copyOf(PENDING);
            PENDING.clear();
        }
        pending.forEach(sender);
    }

    /**
     * Forgets all warnings and the monitoring channel, when the bot shuts down.
     */
    static void reset() {
        synchronized (PENDING) {
            channel = null;
            PENDING.clear();
        }
        REPORTED.clear();
    }
}
