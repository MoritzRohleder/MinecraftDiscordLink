package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.data.StateStore;
import de.moritzrohleder.minecraftdiscordlink.linking.LinkStore;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;

import java.util.OptionalLong;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Keeps the linked role ({@code linking.linked_role}) in sync with the links: linked members have it, nobody else.
 * <p>
 * The role is synchronized for all members on server start and config reload, and for single members when a link
 * changes or someone changes the member's roles in Discord. For {@code "auto"} the bot creates the role
 * {@value #AUTO_ROLE_NAME} (with a {@code server.name}: {@code Linked Account (<name>)}) and creates it again if
 * it is deleted; when the setting changes away from {@code "auto"},
 * the bot deletes the role it created. The ID of that role is kept in {@link StateStore}.
 * <p>
 * All work runs on the bot's executor, so it never blocks Discord's event threads.
 */
final class LinkedRoles {

    /** Name of the role the bot creates for {@code "auto"}. */
    static final String AUTO_ROLE_NAME = "Linked Account";

    /**
     * Returns the name of the role the bot creates for {@code "auto"}: with the server name when several servers
     * share the Discord server, so every server has its own role.
     *
     * @return e.g. {@code Linked Account} or {@code Linked Account (Survival)}
     */
    static String autoRoleName() {
        ModConfig.Server server = ConfigManager.get().server();
        return server.named() ? AUTO_ROLE_NAME + " (" + server.name() + ")" : AUTO_ROLE_NAME;
    }

    private final Guild guild;
    private final LinkStore links;
    private final StateStore state;
    private final ScheduledExecutorService executor;

    /** The role currently used, {@code null} if none. Only changed on the executor. */
    private volatile Role role;
    /** Whether an error has been logged since the last success, to avoid repeating it for every member. */
    private boolean errorLogged;

    /**
     * Creates the role synchronization; call {@link #apply()} to set it up.
     *
     * @param guild    the Discord server
     * @param links    the stored links
     * @param state    the stored state with the ID of the role created by the bot
     * @param executor the bot's executor
     */
    LinkedRoles(Guild guild, LinkStore links, StateStore state, ScheduledExecutorService executor) {
        this.guild = guild;
        this.links = links;
        this.state = state;
        this.executor = executor;
    }

    /**
     * Applies the current config: deletes a role the bot created if it is no longer used, finds or creates the
     * configured role and synchronizes all members. Called on server start and config reload.
     */
    void apply() {
        run(() -> {
            ModConfig.LinkedRole config = ConfigManager.get().linking().linkedRole();
            if (config.mode() != ModConfig.LinkedRoleMode.AUTO) {
                deleteCreatedRole();
            }
            role = switch (config.mode()) {
                case NONE -> null;
                case ROLE -> configuredRole(config.roleId().getAsLong());
                case AUTO -> createdRole();
            };
            if (role != null) {
                errorLogged = false;
                guild.getMemberCache().forEach(this::sync);
            }
        });
    }

    /**
     * Synchronizes the role of one member, e.g. after the member was linked or unlinked.
     *
     * @param discordId the member's ID
     */
    void sync(long discordId) {
        run(() -> {
            Member member = guild.getMemberById(discordId);
            if (member != null) {
                sync(member);
            }
        });
    }

    /**
     * Reacts to a deleted role: if the bot's own role was deleted, it is created again.
     *
     * @param roleId the ID of the deleted role
     */
    void onRoleDeleted(long roleId) {
        run(() -> {
            if (role != null && role.getIdLong() == roleId) {
                role = null;
                apply();
            }
        });
    }

    /**
     * Checks whether a role is the linked role.
     *
     * @param roleId the role's ID
     * @return whether it is the role currently used as linked role
     */
    boolean isLinkedRole(long roleId) {
        Role current = role;
        return current != null && current.getIdLong() == roleId;
    }

    /**
     * Gives or removes the role so it matches the member's link.
     *
     * @param member the member
     */
    private void sync(Member member) {
        Role current = role;
        if (current == null || member.getUser().isBot()) {
            return;
        }
        boolean linked = links.player(member.getIdLong()).isPresent();
        boolean hasRole = member.getRoles().contains(current);
        try {
            if (linked && !hasRole) {
                guild.addRoleToMember(member, current).queue(null, this::logError);
            } else if (!linked && hasRole) {
                guild.removeRoleFromMember(member, current).queue(null, this::logError);
            }
        } catch (HierarchyException e) {
            logError("The linked role is above the bot's highest role. Move the bot's role above it in the server "
                    + "settings.");
        } catch (InsufficientPermissionException e) {
            logError("The bot needs the 'Manage Roles' permission for the linked role.");
        }
    }

    /**
     * Finds the configured role.
     *
     * @param roleId the configured role ID
     * @return the role, {@code null} if it does not exist
     */
    private Role configuredRole(long roleId) {
        Role configured = guild.getRoleById(roleId);
        if (configured == null) {
            Constants.LOG.error("The linked role {} does not exist on the Discord server. The linked role stays "
                    + "inactive.", roleId);
        }
        return configured;
    }

    /**
     * Finds the role the bot created for {@code "auto"}, or creates it.
     *
     * @return the role, {@code null} if it cannot be created
     */
    private Role createdRole() {
        OptionalLong stored = state.createdLinkedRole();
        if (stored.isPresent()) {
            Role existing = guild.getRoleById(stored.getAsLong());
            if (existing != null) {
                return existing;
            }
        }
        try {
            Role created = guild.createRole().setName(autoRoleName()).setPermissions(0L).setMentionable(false)
                    .complete();
            state.setCreatedLinkedRole(OptionalLong.of(created.getIdLong()));
            Constants.LOG.info("Created the Discord role '{}' for linked accounts", created.getName());
            return created;
        } catch (InsufficientPermissionException e) {
            Constants.LOG.error("Could not create the linked role: the bot needs the 'Manage Roles' permission.");
        } catch (RuntimeException e) {
            Constants.LOG.error("Could not create the linked role: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Deletes the role the bot created for {@code "auto"}, if there is one.
     */
    private void deleteCreatedRole() {
        OptionalLong stored = state.createdLinkedRole();
        if (stored.isEmpty()) {
            return;
        }
        Role created = guild.getRoleById(stored.getAsLong());
        try {
            if (created != null) {
                created.delete().complete();
                Constants.LOG.info("Deleted the Discord role '{}', as linking.linked_role is no longer \"auto\"",
                        created.getName());
            }
            state.setCreatedLinkedRole(OptionalLong.empty());
        } catch (RuntimeException e) {
            Constants.LOG.error("Could not delete the linked role created by the bot: {}", e.getMessage());
        }
    }

    /**
     * Runs a task on the bot's executor; does nothing once the bot is shutting down.
     *
     * @param task the task
     */
    private void run(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // The bot is shutting down
        }
    }

    /**
     * Logs a failed role change once until the next {@link #apply()}.
     *
     * @param error the error
     */
    private void logError(Throwable error) {
        logError(error.getMessage());
    }

    /**
     * Logs a problem with the linked role once until the next {@link #apply()}.
     *
     * @param message the message
     */
    private void logError(String message) {
        if (!errorLogged) {
            errorLogged = true;
            Constants.LOG.error("Could not update the linked role: {}", message);
        }
    }
}
