package de.moritzrohleder.minecraftdiscordlink.maintenance;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.data.StateStore;
import de.moritzrohleder.minecraftdiscordlink.data.StateStore.StoredMaintenance;
import de.moritzrohleder.minecraftdiscordlink.linking.LinkStore;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking.Reply;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext.Scope;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

/**
 * The maintenance mode: a temporary whitelist that only lets operators and linked Discord administrators join.
 * <p>
 * Switching it on kicks everybody else. By default it ends with the next restart; a persistent maintenance mode is
 * stored in {@code minecraftdiscordlink/state.json} and active again after a restart. Listeners are told about every
 * change, e.g. to update the channel topic and to notify subscribers.
 * <p>
 * The state can be read from any thread; changes must be made on the server thread.
 */
public final class Maintenance {

    /** Kick message of players without access. */
    private static final String KICK_MESSAGE = "The server is in maintenance mode. Please try again later.";

    private final MinecraftServer server;
    private final StateStore state;
    private final LinkStore links;
    private final LongPredicate discordAdmin;
    private final List<Consumer<Optional<Status>>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status;

    /**
     * Creates the maintenance mode and restores a persistent one.
     *
     * @param server       the running server
     * @param state        the stored state, for a persistent maintenance mode
     * @param links        the account links
     * @param discordAdmin checks whether a Discord user is an administrator of the Discord server; {@code false} while
     *                     Discord is unavailable
     */
    public Maintenance(MinecraftServer server, StateStore state, LinkStore links, LongPredicate discordAdmin) {
        this.server = server;
        this.state = state;
        this.links = links;
        this.discordAdmin = discordAdmin;
        this.status = state.maintenance().map(Maintenance::restore).orElse(null);
        if (status != null) {
            Constants.LOG.info("The maintenance mode is still on (persistent).");
        }
    }

    /**
     * Adds a listener told about every change, with the new status (empty when switched off). It must not block.
     *
     * @param listener the listener
     */
    public void addListener(Consumer<Optional<Status>> listener) {
        listeners.add(listener);
    }

    /**
     * Returns the current status.
     *
     * @return the status, empty while the maintenance mode is off
     */
    public Optional<Status> status() {
        return Optional.ofNullable(status);
    }

    /**
     * Switches the maintenance mode on and kicks online players without access.
     *
     * @param persistent whether it stays on after a restart
     * @param scope      the moderator and the optional reason
     * @return the result
     */
    public Reply enable(boolean persistent, Scope scope) {
        if (status != null) {
            return Reply.error("The maintenance mode is already on.");
        }
        status = new Status(persistent, scope.reason(), scope.moderator(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
        if (persistent) {
            state.setMaintenance(Optional.of(new StoredMaintenance(scope.reason().orElse(null), scope.moderator(),
                    status.since().toString())));
        }
        int kicked = 0;
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (!hasAccess(player.getGameProfile())) {
                player.connection.disconnect(kickMessage());
                kicked++;
            }
        }
        notifyListeners();
        return Reply.success("The maintenance mode is now on" + (persistent ? " and stays on after a restart." : " "
                + "until the next restart.") + (kicked > 0 ? " Kicked " + kicked + " player(s)." : ""));
    }

    /**
     * Switches the maintenance mode off.
     *
     * @return the result
     */
    public Reply disable() {
        if (status == null) {
            return Reply.error("The maintenance mode is not on.");
        }
        if (status.persistent()) {
            state.setMaintenance(Optional.empty());
        }
        status = null;
        notifyListeners();
        return Reply.success("The maintenance mode is now off.");
    }

    /**
     * Checks a joining player.
     *
     * @param profile the joining player
     * @return the kick message, or {@code null} if the player may join
     */
    public Component checkLogin(GameProfile profile) {
        return status != null && !hasAccess(profile) ? kickMessage() : null;
    }

    /**
     * Checks whether a player may join during maintenance: operators and players linked to an administrator of the
     * Discord server.
     *
     * @param profile the player
     * @return whether the player has access
     */
    public boolean hasAccess(GameProfile profile) {
        if (server.getPlayerList().isOp(profile)) {
            return true;
        }
        OptionalLong discordId = links.discordId(profile.getId());
        return discordId.isPresent() && discordAdmin.test(discordId.getAsLong());
    }

    /**
     * Builds the kick message, with the reason if one was given.
     *
     * @return the kick message
     */
    private Component kickMessage() {
        Status current = status;
        return Component.literal(KICK_MESSAGE + (current == null ? "" : current.reason()
                .map(reason -> "\nReason: " + reason).orElse("")));
    }

    /**
     * Tells the listeners about a change.
     */
    private void notifyListeners() {
        Optional<Status> current = status();
        for (Consumer<Optional<Status>> listener : listeners) {
            try {
                listener.accept(current);
            } catch (RuntimeException e) {
                Constants.LOG.error("Could not handle the maintenance change: {}", e.toString());
            }
        }
    }

    /**
     * Restores a persistent maintenance mode.
     *
     * @param stored the stored maintenance mode
     * @return the status
     */
    private static Status restore(StoredMaintenance stored) {
        Instant since;
        try {
            since = Instant.parse(stored.since());
        } catch (DateTimeParseException | NullPointerException e) {
            since = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        }
        return new Status(true, Optional.ofNullable(stored.reason()),
                stored.moderator() == null ? "unknown" : stored.moderator(), since);
    }

    /**
     * The status of an active maintenance mode.
     *
     * @param persistent whether it stays on after a restart
     * @param reason     the reason, empty if none was given
     * @param moderator  who switched it on
     * @param since      when it was switched on
     */
    public record Status(boolean persistent, Optional<String> reason, String moderator, Instant since) {
    }
}
