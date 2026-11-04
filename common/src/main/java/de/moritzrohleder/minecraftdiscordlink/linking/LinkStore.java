package de.moritzrohleder.minecraftdiscordlink.linking;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import de.moritzrohleder.minecraftdiscordlink.data.JsonFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * The links between Minecraft and Discord accounts, stored in {@code minecraftdiscordlink/links.json}.
 * <p>
 * A Minecraft account is linked to at most one Discord account and vice versa. Accounts are stored by ID only
 * (Minecraft UUID, Discord user ID), as names can change. The file is written on every change and read once on
 * server start; server admins remove links by editing the file while the server is stopped.
 * <p>
 * All methods are thread-safe.
 */
public final class LinkStore {

    /** Name of the links file inside the data directory. */
    public static final String FILE_NAME = "links.json";

    private final JsonFile<Data> file;
    private final Map<UUID, Long> byPlayer = new HashMap<>();
    private final Map<Long, UUID> byDiscord = new HashMap<>();
    /** When each link was created, by Minecraft account; missing for links created before this was stored. */
    private final Map<UUID, Instant> linkedAt = new HashMap<>();

    /**
     * Loads the links from the data directory. Entries with missing IDs and duplicates are ignored, as are invalid
     * link dates.
     *
     * @param dataDirectory the mod's data directory
     */
    public LinkStore(Path dataDirectory) {
        this.file = new JsonFile<>(dataDirectory.resolve(FILE_NAME), Data.class);
        Data data = file.read();
        if (data != null && data.links() != null) {
            for (Link link : data.links()) {
                if (link != null && link.uuid() != null && link.discordId() != null
                        && !byPlayer.containsKey(link.uuid()) && !byDiscord.containsKey(link.discordId())) {
                    byPlayer.put(link.uuid(), link.discordId());
                    byDiscord.put(link.discordId(), link.uuid());
                    parseInstant(link.linkedAt()).ifPresent(time -> linkedAt.put(link.uuid(), time));
                }
            }
        }
    }

    /**
     * Links two accounts, if neither of them is linked yet.
     *
     * @param player    the Minecraft account's UUID
     * @param discordId the Discord user's ID
     * @return whether the accounts were linked; {@code false} if one of them is already linked
     */
    public synchronized boolean link(UUID player, long discordId) {
        if (byPlayer.containsKey(player) || byDiscord.containsKey(discordId)) {
            return false;
        }
        byPlayer.put(player, discordId);
        byDiscord.put(discordId, player);
        linkedAt.put(player, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        save();
        return true;
    }

    /**
     * Removes the link of a Minecraft account.
     *
     * @param player the Minecraft account's UUID
     * @return the ID of the Discord user that was linked, empty if the account was not linked
     */
    public synchronized OptionalLong unlinkPlayer(UUID player) {
        Long discordId = byPlayer.remove(player);
        if (discordId == null) {
            return OptionalLong.empty();
        }
        byDiscord.remove(discordId);
        linkedAt.remove(player);
        save();
        return OptionalLong.of(discordId);
    }

    /**
     * Removes the link of a Discord account.
     *
     * @param discordId the Discord user's ID
     * @return the UUID of the Minecraft account that was linked, empty if the user was not linked
     */
    public synchronized Optional<UUID> unlinkDiscord(long discordId) {
        UUID player = byDiscord.remove(discordId);
        if (player == null) {
            return Optional.empty();
        }
        byPlayer.remove(player);
        linkedAt.remove(player);
        save();
        return Optional.of(player);
    }

    /**
     * Returns the Discord account linked to a Minecraft account.
     *
     * @param player the Minecraft account's UUID
     * @return the Discord user's ID, empty if not linked
     */
    public synchronized OptionalLong discordId(UUID player) {
        Long discordId = byPlayer.get(player);
        return discordId != null ? OptionalLong.of(discordId) : OptionalLong.empty();
    }

    /**
     * Returns the Minecraft account linked to a Discord account.
     *
     * @param discordId the Discord user's ID
     * @return the Minecraft account's UUID, empty if not linked
     */
    public synchronized Optional<UUID> player(long discordId) {
        return Optional.ofNullable(byDiscord.get(discordId));
    }

    /**
     * Returns the IDs of all linked Discord users.
     *
     * @return a copy of the IDs
     */
    public synchronized Set<Long> discordIds() {
        return Set.copyOf(byDiscord.keySet());
    }

    /**
     * Writes all links to the file.
     */
    private void save() {
        List<Link> links = new ArrayList<>();
        byPlayer.forEach((player, discordId) -> {
            Instant time = linkedAt.get(player);
            links.add(new Link(player, discordId, time != null ? time.toString() : null));
        });
        file.write(new Data(links));
    }

    /**
     * Returns when a Minecraft account was linked.
     *
     * @param player the Minecraft account's UUID
     * @return the time of linking, empty if not linked or linked before the time was stored
     */
    public synchronized Optional<Instant> linkedAt(UUID player) {
        return Optional.ofNullable(linkedAt.get(player));
    }

    /**
     * Parses a stored link date.
     *
     * @param value the ISO-8601 instant, may be {@code null}
     * @return the instant, empty if missing or invalid
     */
    private static Optional<Instant> parseInstant(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Content of the links file.
     *
     * @param links all links
     */
    private record Data(List<Link> links) {
    }

    /**
     * One link.
     *
     * @param uuid      the Minecraft account's UUID
     * @param discordId the Discord user's ID, stored as {@code discord_id} string as Discord IDs exceed the range of
     *                  JSON numbers in many tools
     * @param linkedAt  when the link was created, ISO-8601 ({@code linked_at}), may be {@code null}
     */
    private record Link(UUID uuid,
                        @SerializedName("discord_id") @JsonAdapter(LongAsString.class) Long discordId,
                        @SerializedName("linked_at") String linkedAt) {
    }

    /**
     * Writes Discord IDs as JSON strings and reads both strings and numbers.
     */
    private static final class LongAsString extends TypeAdapter<Long> {

        @Override
        public void write(JsonWriter out, Long value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(String.valueOf(value));
            }
        }

        @Override
        public Long read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            try {
                return Long.parseLong(in.nextString().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
