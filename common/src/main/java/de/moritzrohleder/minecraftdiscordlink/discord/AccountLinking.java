package de.moritzrohleder.minecraftdiscordlink.discord;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.data.StateStore;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * Discord side of the account linking and the Discord whitelist.
 * <p>
 * Provides {@link Linking} with what it needs from Discord (members, roles, direct messages) and reacts to Discord
 * events: {@code refresh} replies to link code DMs, and role or membership changes, which update the linked role and
 * kick players who are no longer whitelisted.
 */
public final class AccountLinking extends ListenerAdapter {

    private final JDA jda;
    private final Guild guild;
    private final MinecraftServer server;
    private final Linking linking;
    private final LinkedRoles linkedRoles;

    /**
     * Creates the Discord side of the linking; register it as JDA event listener and call {@link #apply()}.
     *
     * @param jda      the connected bot
     * @param guild    the Discord server of the mod's channels
     * @param server   the running server
     * @param linking  the Minecraft side of the linking
     * @param state    the stored state with the ID of the role created by the bot
     * @param executor the bot's executor
     */
    AccountLinking(JDA jda, Guild guild, MinecraftServer server, Linking linking, StateStore state,
                   ScheduledExecutorService executor) {
        this.jda = jda;
        this.guild = guild;
        this.server = server;
        this.linking = linking;
        this.linkedRoles = new LinkedRoles(guild, linking.links(), state, executor);
    }

    /**
     * Applies the config: sets up and synchronizes the linked role and kicks online players who are no longer
     * whitelisted. Called on server start and config reload.
     */
    void apply() {
        linkedRoles.apply();
        server.execute(linking::recheckAll);
    }

    /**
     * Finds a member by username, ignoring case.
     *
     * @param username the Discord username (not the display name)
     * @return the member's ID, empty if there is no such member
     */
    public OptionalLong findMember(String username) {
        return guild.getMemberCache().stream()
                .filter(member -> member.getUser().getName().equalsIgnoreCase(username))
                .mapToLong(Member::getIdLong)
                .findFirst();
    }

    /**
     * Finds members by name: by username first, otherwise by display name (nickname or global display name), both
     * ignoring case.
     *
     * @param name the name, with or without leading {@code @}
     * @return the IDs of the matching members, empty if none
     */
    public List<Long> findMembersByName(String name) {
        String search = name.startsWith("@") ? name.substring(1) : name;
        OptionalLong byUsername = findMember(search);
        if (byUsername.isPresent()) {
            return List.of(byUsername.getAsLong());
        }
        return guild.getMemberCache().stream()
                .filter(member -> member.getEffectiveName().equalsIgnoreCase(search))
                .map(Member::getIdLong)
                .toList();
    }

    /**
     * Returns the username of a member.
     *
     * @param discordId the member's ID
     * @return the username, empty if the user is not a member of the Discord server
     */
    public Optional<String> username(long discordId) {
        return Optional.ofNullable(guild.getMemberById(discordId)).map(member -> member.getUser().getName());
    }

    /**
     * Returns the roles of a member.
     *
     * @param discordId the member's ID
     * @return the IDs of the member's roles, empty if the user is not a member of the Discord server
     */
    public Set<Long> roles(long discordId) {
        Member member = guild.getMemberById(discordId);
        return member == null ? Set.of()
                : member.getRoles().stream().map(Role::getIdLong).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Checks whether a member has the Administrator permission on the Discord server.
     *
     * @param discordId the member's ID
     * @return whether the member is an administrator; {@code false} if not a member
     */
    public boolean isAdministrator(long discordId) {
        Member member = guild.getMemberById(discordId);
        return member != null && member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR);
    }

    /**
     * Returns the display name of a member: server nickname, global display name or username.
     *
     * @param discordId the member's ID
     * @return the name, empty if the user is not a member of the Discord server
     */
    public Optional<String> displayName(long discordId) {
        return Optional.ofNullable(guild.getMemberById(discordId)).map(Member::getEffectiveName);
    }

    /**
     * Sends the usernames of the Discord members to players as chat suggestions, so typing {@code @} and pressing
     * Tab in the Minecraft chat completes mentions. Replaces suggestions sent before. Must be called on the server
     * thread.
     *
     * @param players the players
     */
    public void sendMentionSuggestions(Collection<ServerPlayer> players) {
        if (!ConfigManager.get().chat().mentionSuggestions()) {
            return;
        }
        List<String> mentions = guild.getMemberCache().stream()
                .filter(member -> !member.getUser().isBot())
                .map(member -> "@" + member.getUser().getName())
                .sorted()
                .toList();
        ClientboundCustomChatCompletionsPacket packet = new ClientboundCustomChatCompletionsPacket(
                ClientboundCustomChatCompletionsPacket.Action.SET, mentions);
        players.forEach(player -> player.connection.send(packet));
    }

    /**
     * Sends the current mention suggestions to all online players, e.g. after a member joined or left.
     */
    private void updateMentionSuggestions() {
        server.execute(() -> sendMentionSuggestions(server.getPlayerList().getPlayers()));
    }

    /**
     * Sends a direct message.
     *
     * @param discordId the recipient's ID
     * @param text      the message
     * @param onSent    called when the message was sent
     * @param onFailed  called when it could not be sent, e.g. because the user does not accept direct messages
     */
    public void sendDirectMessage(long discordId, String text, Runnable onSent, Runnable onFailed) {
        jda.retrieveUserById(discordId)
                .flatMap(user -> user.openPrivateChannel())
                .flatMap(channel -> channel.sendMessage(text))
                .queue(message -> onSent.run(), error -> onFailed.run());
    }

    /**
     * Updates Discord after a link of a user was created or removed.
     *
     * @param discordId the user's ID
     */
    public void onLinksChanged(long discordId) {
        linkedRoles.sync(discordId);
    }

    /**
     * Answers {@code refresh} in a direct message with a new link code.
     *
     * @param event the received message
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromType(ChannelType.PRIVATE) || event.getAuthor().isBot()
                || !event.getMessage().getContentRaw().strip().equalsIgnoreCase("refresh")) {
            return;
        }
        Optional<String> refreshed = linking.refreshDmCode(event.getAuthor().getIdLong());
        if (refreshed.isEmpty() && !ConfigManager.get().server().primary()) {
            // Several servers share the Discord server: only the primary one answers that nothing is pending
            return;
        }
        String reply = refreshed.orElse("There is no pending link request for your account. Start one in Minecraft "
                + "with `/discord link <your Discord username>`.");
        event.getChannel().sendMessage(reply).queue(null, error ->
                Constants.LOG.error("Could not answer a direct message: {}", error.getMessage()));
    }

    /**
     * A member got a role: the linked role may have to be removed again.
     *
     * @param event the event
     */
    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        if (event.getGuild().getIdLong() == guild.getIdLong()) {
            onMemberChanged(event.getMember(), event.getRoles());
        }
    }

    /**
     * A member lost a role: the linked role may have to be given back, the player may no longer be whitelisted.
     *
     * @param event the event
     */
    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        if (event.getGuild().getIdLong() == guild.getIdLong()) {
            onMemberChanged(event.getMember(), event.getRoles());
            recheck(event.getMember().getIdLong());
        }
    }

    /**
     * A member joined the Discord server: linked members get the linked role again, the mention suggestions are
     * updated.
     *
     * @param event the event
     */
    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (event.getGuild().getIdLong() == guild.getIdLong()) {
            linkedRoles.sync(event.getMember().getIdLong());
            updateMentionSuggestions();
        }
    }

    /**
     * A member left the Discord server: the player is no longer whitelisted by role, the mention suggestions are
     * updated.
     *
     * @param event the event
     */
    @Override
    public void onGuildMemberRemove(GuildMemberRemoveEvent event) {
        if (event.getGuild().getIdLong() == guild.getIdLong()) {
            recheck(event.getUser().getIdLong());
            updateMentionSuggestions();
        }
    }

    /**
     * A role was deleted: the linked role is created again if it was the bot's own.
     *
     * @param event the event
     */
    @Override
    public void onRoleDelete(RoleDeleteEvent event) {
        if (event.getGuild().getIdLong() == guild.getIdLong()) {
            linkedRoles.onRoleDeleted(event.getRole().getIdLong());
        }
    }

    /**
     * Corrects the linked role after the roles of a member changed.
     *
     * @param member the member
     * @param roles  the added or removed roles
     */
    private void onMemberChanged(Member member, List<Role> roles) {
        if (roles.stream().anyMatch(role -> linkedRoles.isLinkedRole(role.getIdLong()))) {
            linkedRoles.sync(member.getIdLong());
        }
    }

    /**
     * Kicks the player linked to a Discord user if the player is online and no longer whitelisted.
     *
     * @param discordId the Discord user's ID
     */
    private void recheck(long discordId) {
        Optional<UUID> player = linking.links().player(discordId);
        player.ifPresent(uuid -> server.execute(() -> linking.recheck(uuid)));
    }
}
