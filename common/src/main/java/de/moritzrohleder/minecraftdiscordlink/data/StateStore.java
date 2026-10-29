package de.moritzrohleder.minecraftdiscordlink.data;

import com.google.gson.annotations.SerializedName;

import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * Internal state of the mod that has to survive restarts, stored in {@code minecraftdiscordlink/state.json}.
 * <p>
 * Currently this is the ID of the linked role the bot created itself for {@code linking.linked_role = "auto"}. It is
 * stored so the bot only ever deletes a role it created, never a role with the same name made by someone else.
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
        this.data = loaded != null ? loaded : new Data(null);
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
        data = new Data(roleId.isPresent() ? String.valueOf(roleId.getAsLong()) : null);
        file.write(data);
    }

    /**
     * Content of the state file.
     *
     * @param createdLinkedRole ID of the linked role created by the bot, as string
     */
    private record Data(@SerializedName(value = "created_linked_role", alternate = "createdLinkedRole")
                        String createdLinkedRole) {
    }
}
