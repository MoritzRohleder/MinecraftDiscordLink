package de.moritzrohleder.minecraftdiscordlink.linking;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The pending link codes. They are only kept in memory and are lost on restart.
 * <p>
 * There are two kinds of codes:
 * <ul>
 *     <li><b>DM codes</b>: requested in Minecraft with {@code /discord link <name>}, sent to the Discord user by
 *     direct message and entered in Minecraft with {@code /discord confirm <code>}. Each player has at most one; a
 *     new request or a {@code refresh} replaces it.</li>
 *     <li><b>Kick codes</b>: shown to players kicked by the Discord whitelist and entered in Discord with
 *     {@code /mc link <code>}. Repeated join attempts show the same code while it is valid.</li>
 * </ul>
 * Codes are six characters without easily confused ones (no {@code 0/O}, {@code 1/I}). All methods are thread-safe.
 */
public final class LinkCodes {

    /** Characters of a code. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** Length of a code. */
    private static final int LENGTH = 6;

    private final Clock clock;
    private final Supplier<Duration> validity;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, DmCode> dmCodes = new HashMap<>();
    private final Map<String, KickCode> kickCodes = new HashMap<>();

    /**
     * Creates an empty set of codes.
     *
     * @param clock    the clock for the expiry of codes
     * @param validity how long new codes are valid, read for every new code so config changes apply
     */
    public LinkCodes(Clock clock, Supplier<Duration> validity) {
        this.clock = clock;
        this.validity = validity;
    }

    /**
     * Creates a DM code for a player and a Discord user, replacing the player's previous DM code.
     *
     * @param player    the player's UUID
     * @param discordId the ID of the Discord user the code is sent to
     * @return the code
     */
    public synchronized String createDmCode(UUID player, long discordId) {
        removeExpired();
        dmCodes.values().removeIf(code -> code.player().equals(player));
        String code = newCode();
        dmCodes.put(code, new DmCode(player, discordId, clock.instant().plus(validity.get())));
        return code;
    }

    /**
     * Replaces the DM code sent to a Discord user with a new one, e.g. when the user replies {@code refresh}.
     *
     * @param discordId the Discord user's ID
     * @return the new code and the player who requested it, empty if no valid DM code was sent to the user
     */
    public synchronized Optional<RefreshedCode> refreshDmCode(long discordId) {
        removeExpired();
        Optional<DmCode> existing = dmCodes.values().stream().filter(code -> code.discordId() == discordId)
                .findFirst();
        return existing.map(code -> new RefreshedCode(code.player(), createDmCode(code.player(), discordId)));
    }

    /**
     * A DM code that replaced an older one.
     *
     * @param player the player who requested the link
     * @param code   the new code
     */
    public record RefreshedCode(UUID player, String code) {
    }

    /**
     * Redeems a DM code entered in Minecraft.
     *
     * @param player the player entering the code
     * @param code   the entered code, case-insensitive
     * @return the ID of the Discord user the code was sent to, empty if the code is invalid, expired or belongs to
     *     another player
     */
    public synchronized Optional<Long> redeemDmCode(UUID player, String code) {
        removeExpired();
        String key = code.strip().toUpperCase(Locale.ROOT);
        DmCode dmCode = dmCodes.get(key);
        if (dmCode == null || !dmCode.player().equals(player)) {
            return Optional.empty();
        }
        dmCodes.remove(key);
        return Optional.of(dmCode.discordId());
    }

    /**
     * Returns the kick code of a player, creating one if the player has no valid kick code yet.
     *
     * @param player the player's UUID
     * @param name   the player's name, kept with the code as Minecraft only remembers names of players who joined
     * @return the code
     */
    public synchronized String kickCode(UUID player, String name) {
        removeExpired();
        for (Map.Entry<String, KickCode> entry : kickCodes.entrySet()) {
            if (entry.getValue().player().equals(player)) {
                return entry.getKey();
            }
        }
        String code = newCode();
        kickCodes.put(code, new KickCode(player, name, clock.instant().plus(validity.get())));
        return code;
    }

    /**
     * Redeems a kick code entered in Discord.
     *
     * @param code the entered code, case-insensitive
     * @return the player the code was shown to, empty if the code is invalid or expired
     */
    public synchronized Optional<KickedPlayer> redeemKickCode(String code) {
        removeExpired();
        KickCode kickCode = kickCodes.remove(code.strip().toUpperCase(Locale.ROOT));
        return kickCode != null ? Optional.of(new KickedPlayer(kickCode.player(), kickCode.name()))
                : Optional.empty();
    }

    /**
     * The player a kick code was shown to.
     *
     * @param uuid the player's UUID
     * @param name the player's name
     */
    public record KickedPlayer(UUID uuid, String name) {
    }

    /**
     * Creates a random code that is not in use.
     *
     * @return the code
     */
    private String newCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            code = builder.toString();
        } while (dmCodes.containsKey(code) || kickCodes.containsKey(code));
        return code;
    }

    /**
     * Removes all expired codes.
     */
    private void removeExpired() {
        Instant now = clock.instant();
        dmCodes.values().removeIf(code -> !code.expires().isAfter(now));
        kickCodes.values().removeIf(code -> !code.expires().isAfter(now));
    }

    /**
     * Checks whether a kick code exists and is still valid, without redeeming it.
     *
     * @param code the code, case-insensitive
     * @return whether the code is a valid kick code
     */
    public synchronized boolean hasKickCode(String code) {
        removeExpired();
        return kickCodes.containsKey(code.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * A code sent by direct message.
     *
     * @param player    the player who requested it
     * @param discordId the Discord user it was sent to
     * @param expires   when it expires
     */
    private record DmCode(UUID player, long discordId, Instant expires) {
    }

    /**
     * A code shown in a kick message.
     *
     * @param player  the kicked player
     * @param name    the kicked player's name
     * @param expires when it expires
     */
    private record KickCode(UUID player, String name, Instant expires) {
    }
}
