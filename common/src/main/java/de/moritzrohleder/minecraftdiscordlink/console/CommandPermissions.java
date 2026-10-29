package de.moritzrohleder.minecraftdiscordlink.console;

import de.moritzrohleder.minecraftdiscordlink.config.ConfigLoader;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.Permission;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.PermissionMode;

import java.util.Collection;
import java.util.List;

/**
 * Decides whether a Discord user may run a command in the console channel, based on {@code [[console.permissions]]}.
 * <p>
 * The entries matching a user are those for the user's ID and for the IDs of the user's roles. A command is allowed
 * if at least one matching entry allows it (union), unless a matching blacklist entry lists it: explicit denies always
 * win. Users without a matching entry may not run any command.
 * <p>
 * A listed command matches itself and all its subcommands: {@code gamerule} matches {@code gamerule keepInventory
 * true}, {@code gamerule doDaylightCycle} only matches that subcommand. Commands are compared normalized, see
 * {@link ConfigLoader#normalizeCommand(String)}.
 */
public final class CommandPermissions {

    private CommandPermissions() {
    }

    /**
     * Checks whether a user may run a command.
     *
     * @param permissions the configured permission entries
     * @param userId      the Discord user's ID
     * @param roleIds     the IDs of the user's roles
     * @param command     the command as written, without leading slash
     * @return whether the command may be run
     */
    public static boolean isAllowed(List<Permission> permissions, long userId, Collection<Long> roleIds,
                                    String command) {
        String normalized = ConfigLoader.normalizeCommand(command);
        if (normalized.isEmpty()) {
            return false;
        }
        boolean allowed = false;
        for (Permission permission : permissions) {
            if (permission.id() != userId && !roleIds.contains(permission.id())) {
                continue;
            }
            boolean listed = permission.commands().stream().anyMatch(listedCommand -> matches(listedCommand,
                    normalized));
            if (permission.mode() == PermissionMode.BLACKLIST) {
                if (listed) {
                    return false;
                }
                allowed = true;
            } else if (listed) {
                allowed = true;
            }
        }
        return allowed;
    }

    /**
     * Checks whether a listed command covers a command.
     *
     * @param listed  the normalized command from the config
     * @param command the normalized command to run
     * @return whether {@code command} is {@code listed} or one of its subcommands
     */
    private static boolean matches(String listed, String command) {
        return command.equals(listed) || command.startsWith(listed + " ");
    }
}
