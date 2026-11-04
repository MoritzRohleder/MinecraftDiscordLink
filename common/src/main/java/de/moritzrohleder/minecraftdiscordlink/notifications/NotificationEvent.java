package de.moritzrohleder.minecraftdiscordlink.notifications;

import java.util.Locale;
import java.util.Optional;

/**
 * Server events Discord users can subscribe to.
 */
public enum NotificationEvent {
    /** The server has started. */
    SERVER_START("Server start"),
    /** The server is stopping. */
    SERVER_STOP("Server stop"),
    /** The maintenance mode was switched on or off. */
    MAINTENANCE("Maintenance mode");

    private final String displayName;

    NotificationEvent(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the name shown in Discord.
     *
     * @return e.g. {@code Server start}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the ID used in the subscriptions file and as command choice.
     *
     * @return e.g. {@code server_start}
     */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Looks up an event by its ID.
     *
     * @param id the ID, e.g. {@code server_start}
     * @return the event, empty if unknown
     */
    public static Optional<NotificationEvent> byId(String id) {
        for (NotificationEvent event : values()) {
            if (event.id().equals(id)) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }
}
