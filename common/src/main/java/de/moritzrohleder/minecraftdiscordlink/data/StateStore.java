package de.moritzrohleder.minecraftdiscordlink.data;

import com.google.gson.annotations.SerializedName;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Internal state of the mod that has to survive restarts, stored in {@code minecraftdiscordlink/state.json}.
 * <p>
 * This is the ID of the linked role the bot created itself for {@code linking.linked_role = "auto"} (stored so the
 * bot only ever deletes a role it created, never a role with the same name made by someone else), a maintenance mode
 * that survives restarts and the ID of the status message in the monitoring channel.
 * <p>
 * All methods are thread-safe.
 */
public final class StateStore {

    /** Name of the state file inside the data directory. */
    public static final String FILE_NAME = "state.json";

    private final JsonFile<Data> file;
    private Data data;

    /**
     * Loads the state from the data directory.
     *
     * @param dataDirectory the mod's data directory
     */
    public StateStore(Path dataDirectory) {
        this.file = new JsonFile<>(dataDirectory.resolve(FILE_NAME), Data.class);
        Data loaded = file.read();
        this.data = loaded != null ? loaded : new Data(null, null, null);
    }

    /**
     * Returns the ID of the linked role created by the bot.
     *
     * @return the role ID, empty if the bot has not created one
     */
    public synchronized OptionalLong createdLinkedRole() {
        try {
            return data.createdLinkedRole() != null
                    ? OptionalLong.of(Long.parseLong(data.createdLinkedRole()))
                    : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Stores the ID of the linked role created by the bot.
     *
     * @param roleId the role ID, empty to forget it
     */
    public synchronized void setCreatedLinkedRole(OptionalLong roleId) {
        data = new Data(roleId.isPresent() ? String.valueOf(roleId.getAsLong()) : null, data.maintenance(),
                data.statusMessage());
        file.write(data);
    }

    /**
     * Returns the stored maintenance mode.
     *
     * @return the maintenance mode that survives restarts, empty if none
     */
    public synchronized Optional<StoredMaintenance> maintenance() {
        return Optional.ofNullable(data.maintenance());
    }

    /**
     * Stores the maintenance mode that survives restarts.
     *
     * @param maintenance the maintenance mode, empty if none
     */
    public synchronized void setMaintenance(Optional<StoredMaintenance> maintenance) {
        data = new Data(data.createdLinkedRole(), maintenance.orElse(null), data.statusMessage());
        file.write(data);
    }

    /**
     * Returns the ID of the status message in the monitoring channel.
     *
     * @return the message ID, empty if none was sent yet
     */
    public synchronized OptionalLong statusMessage() {
        try {
            return data.statusMessage() != null
                    ? OptionalLong.of(Long.parseLong(data.statusMessage()))
                    : OptionalLong.empty();
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /**
     * Stores the ID of the status message in the monitoring channel.
     *
     * @param messageId the message ID, empty to forget it
     */
    public synchronized void setStatusMessage(OptionalLong messageId) {
        data = new Data(data.createdLinkedRole(), data.maintenance(),
                messageId.isPresent() ? String.valueOf(messageId.getAsLong()) : null);
        file.write(data);
    }

    /**
     * A maintenance mode that survives restarts.
     *
     * @param reason    the reason, may be {@code null}
     * @param moderator who switched it on
     * @param since     when it was switched on, ISO-8601
     */
    public record StoredMaintenance(String reason, String moderator, String since) {
    }

    /**
     * Content of the state file.
     *
     * @param createdLinkedRole ID of the linked role created by the bot, as string
     * @param maintenance       the maintenance mode that survives restarts, {@code null} if none
     * @param statusMessage     ID of the status message in the monitoring channel, as string
     */
    private record Data(@SerializedName(value = "created_linked_role", alternate = "createdLinkedRole")
                        String createdLinkedRole,
                        StoredMaintenance maintenance,
                        @SerializedName("status_message") String statusMessage) {
    }
}
