package de.moritzrohleder.minecraftdiscordlink.notifications;

import com.google.gson.annotations.SerializedName;
import de.moritzrohleder.minecraftdiscordlink.data.JsonFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The subscriptions of Discord users to server events and players, stored in
 * {@code minecraftdiscordlink/subscriptions.json} on every change.
 * <p>
 * All methods are thread-safe.
 */
public final class SubscriptionStore {

    /** Name of the file inside the data directory. */
    public static final String FILE_NAME = "subscriptions.json";

    private final JsonFile<Data> file;
    /** The subscriptions of every user, in the order the users subscribed first. */
    private final Map<Long, Subscriptions> users = new LinkedHashMap<>();

    /**
     * Loads the subscriptions.
     *
     * @param dataDirectory the mod's data directory
     */
    public SubscriptionStore(Path dataDirectory) {
        this.file = new JsonFile<>(dataDirectory.resolve(FILE_NAME), Data.class);
        load();
    }

    /**
     * Subscribes a user to an event.
     *
     * @param discordId the Discord user
     * @param event     the event
     * @return whether the user was not subscribed yet
     */
    public synchronized boolean subscribe(long discordId, NotificationEvent event) {
        boolean added = user(discordId).events().add(event);
        save();
        return added;
    }

    /**
     * Subscribes a user to a player coming online.
     *
     * @param discordId the Discord user
     * @param player    the player's UUID
     * @return whether the user was not subscribed yet
     */
    public synchronized boolean subscribe(long discordId, UUID player) {
        boolean added = user(discordId).players().add(player);
        save();
        return added;
    }

    /**
     * Unsubscribes a user from an event.
     *
     * @param discordId the Discord user
     * @param event     the event
     * @return whether the user was subscribed
     */
    public synchronized boolean unsubscribe(long discordId, NotificationEvent event) {
        Subscriptions subscriptions = users.get(discordId);
        boolean removed = subscriptions != null && subscriptions.events().remove(event);
        cleanUp(discordId);
        return removed;
    }

    /**
     * Unsubscribes a user from a player.
     *
     * @param discordId the Discord user
     * @param player    the player's UUID
     * @return whether the user was subscribed
     */
    public synchronized boolean unsubscribe(long discordId, UUID player) {
        Subscriptions subscriptions = users.get(discordId);
        boolean removed = subscriptions != null && subscriptions.players().remove(player);
        cleanUp(discordId);
        return removed;
    }

    /**
     * Returns the subscriptions of a user.
     *
     * @param discordId the Discord user
     * @return a copy of the subscriptions, empty if none
     */
    public synchronized Subscriptions of(long discordId) {
        Subscriptions subscriptions = users.get(discordId);
        return subscriptions == null ? new Subscriptions(Set.of(), Set.of())
                : new Subscriptions(Set.copyOf(subscriptions.events()), Set.copyOf(subscriptions.players()));
    }

    /**
     * Returns the users subscribed to an event.
     *
     * @param event the event
     * @return the Discord user IDs
     */
    public synchronized List<Long> subscribers(NotificationEvent event) {
        return users.entrySet().stream().filter(entry -> entry.getValue().events().contains(event))
                .map(Map.Entry::getKey).toList();
    }

    /**
     * Returns the users subscribed to a player.
     *
     * @param player the player's UUID
     * @return the Discord user IDs
     */
    public synchronized List<Long> subscribers(UUID player) {
        return users.entrySet().stream().filter(entry -> entry.getValue().players().contains(player))
                .map(Map.Entry::getKey).toList();
    }

    /**
     * Returns the stored subscriptions of a user, creating them if needed.
     *
     * @param discordId the Discord user
     * @return the mutable subscriptions
     */
    private Subscriptions user(long discordId) {
        return users.computeIfAbsent(discordId, id -> new Subscriptions(new LinkedHashSet<>(), new LinkedHashSet<>()));
    }

    /**
     * Removes a user without subscriptions and saves.
     *
     * @param discordId the Discord user
     */
    private void cleanUp(long discordId) {
        Subscriptions subscriptions = users.get(discordId);
        if (subscriptions != null && subscriptions.events().isEmpty() && subscriptions.players().isEmpty()) {
            users.remove(discordId);
        }
        save();
    }

    /**
     * Reads the file. Invalid entries are skipped.
     */
    private void load() {
        Data data = file.read();
        if (data == null || data.subscriptions() == null) {
            return;
        }
        for (Entry entry : data.subscriptions()) {
            try {
                Subscriptions subscriptions = user(Long.parseLong(entry.discordId()));
                if (entry.events() != null) {
                    entry.events().forEach(id -> NotificationEvent.byId(id)
                            .ifPresent(subscriptions.events()::add));
                }
                if (entry.players() != null) {
                    subscriptions.players().addAll(entry.players());
                }
            } catch (NumberFormatException | NullPointerException ignored) {
                // An invalid entry is skipped
            }
        }
    }

    /**
     * Writes the file.
     */
    private void save() {
        List<Entry> entries = new ArrayList<>();
        users.forEach((discordId, subscriptions) -> entries.add(new Entry(String.valueOf(discordId),
                subscriptions.events().stream().map(NotificationEvent::id).toList(),
                List.copyOf(subscriptions.players()))));
        file.write(new Data(entries));
    }

    /**
     * The subscriptions of one user.
     *
     * @param events  the subscribed events
     * @param players the subscribed players
     */
    public record Subscriptions(Set<NotificationEvent> events, Set<UUID> players) {
    }

    /**
     * Content of the subscriptions file.
     *
     * @param subscriptions one entry per user
     */
    private record Data(List<Entry> subscriptions) {
    }

    /**
     * The subscriptions of one user in the file.
     *
     * @param discordId the Discord user ID, as string
     * @param events    the event IDs
     * @param players   the player UUIDs
     */
    private record Entry(@SerializedName("discord_id") String discordId, List<String> events, List<UUID> players) {
    }
}
