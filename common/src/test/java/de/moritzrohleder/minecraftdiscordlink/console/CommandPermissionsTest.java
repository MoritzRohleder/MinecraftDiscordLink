package de.moritzrohleder.minecraftdiscordlink.console;

import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.Permission;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.PermissionMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPermissionsTest {

    private static final long USER = 100000000000000001L;
    private static final long ROLE_A = 200000000000000001L;
    private static final long ROLE_B = 200000000000000002L;

    @Test
    void deniesUsersWithoutEntry() {
        List<Permission> permissions = List.of(new Permission(ROLE_A, PermissionMode.BLACKLIST, List.of()));

        assertFalse(CommandPermissions.isAllowed(permissions, USER, List.of(ROLE_B), "list"));
    }

    @Test
    void appliesWhitelistWithSubcommands() {
        List<Permission> permissions = List.of(
                new Permission(USER, PermissionMode.WHITELIST, List.of("say", "gamerule dodaylightcycle")));

        assertTrue(CommandPermissions.isAllowed(permissions, USER, List.of(), "say hi"));
        assertTrue(CommandPermissions.isAllowed(permissions, USER, List.of(), "gamerule doDaylightCycle false"));
        assertFalse(CommandPermissions.isAllowed(permissions, USER, List.of(), "gamerule keepInventory true"));
        assertFalse(CommandPermissions.isAllowed(permissions, USER, List.of(), "sayhi"));
    }

    @Test
    void combinesRolesAsUnionWithDeniesWinning() {
        List<Permission> permissions = List.of(
                new Permission(ROLE_A, PermissionMode.WHITELIST, List.of("say", "list", "stop")),
                new Permission(ROLE_B, PermissionMode.BLACKLIST, List.of("stop")));

        // Role A allows only say and list, role B everything but stop: together everything but stop
        assertTrue(CommandPermissions.isAllowed(permissions, USER, List.of(ROLE_A, ROLE_B), "time set day"));
        assertTrue(CommandPermissions.isAllowed(permissions, USER, List.of(ROLE_A, ROLE_B), "say hi"));
        assertFalse(CommandPermissions.isAllowed(permissions, USER, List.of(ROLE_A, ROLE_B), "stop"));
        assertTrue(CommandPermissions.isAllowed(permissions, USER, List.of(ROLE_A), "stop"));
    }
}
