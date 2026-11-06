package de.moritzrohleder.minecraftdiscordlink.discord;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import de.moritzrohleder.minecraftdiscordlink.platform.Services;
import de.moritzrohleder.minecraftdiscordlink.reports.ReportStore;
import de.moritzrohleder.minecraftdiscordlink.reports.ReportStore.Report;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Durations;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports of players ({@code features.moderation.reports}): {@code /report <player> <reason>} in Minecraft and
 * {@code /mc report} in Discord, optionally with a reported Discord message. A report goes to the report channel
 * with buttons to warn, mute, kick or ban the player, or to mark it as done or dismiss it; online admins in
 * Minecraft are told as well. Actions are normal moderation actions by the staff member.
 * <p>
 * Component IDs end with the server name, so several servers can share the Discord server.
 */
public final class Reports extends ListenerAdapter {

    private static final String PREFIX = "mdlr|";
    private static final String FORM = PREFIX + "form|";
    private static final String REASON_FIELD = "reason";
    private static final String DURATION_FIELD = "duration";
    /** A Discord message link: server, channel and message ID. */
    private static final Pattern MESSAGE_LINK = Pattern.compile(
            "https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/channels/(\\d+)/(\\d+)/(\\d+)");
    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{17,20}");
    private static final int MAX_QUOTE = 1000;

    /**
     * What the staff can do with a report.
     */
    private enum Action {
        WARN("Warn"), MUTE("Mute"), KICK("Kick"), BAN("Ban"), DONE("Done"), DISMISS("Dismiss");

        private final String label;

        Action(String label) {
            this.label = label;
        }
    }

    private final JDA jda;
    private final MinecraftServer server;
    private final Linking linking;
    private final Moderation moderation;
    private final ReportStore store;
    private final Executor executor;

    /**
     * Creates the reports.
     *
     * @param jda        the bot
     * @param server     the Minecraft server
     * @param linking    the account linking, to find the Minecraft account of a Discord reporter
     * @param moderation the moderation, for the actions
     * @param store      the stored reports
     * @param executor   the bot's executor, for work that may block
     */
    Reports(JDA jda, MinecraftServer server, Linking linking, Moderation moderation, ReportStore store,
            Executor executor) {
        this.jda = jda;
        this.server = server;
        this.linking = linking;
        this.moderation = moderation;
        this.store = store;
        this.executor = executor;
    }

    // ---- Reporting ----

    /**
     * Sends a report from Minecraft ({@code /report}). Must be called on the server thread.
     *
     * @param source the reporter
     * @param target the reported player
     * @param reason the reason
     * @return why the report was not sent, empty if it was sent
     */
    public Optional<String> fromMinecraft(CommandSourceStack source, GameProfile target, String reason) {
        ServerPlayer reporter = source.getPlayer();
        if (reporter != null && reporter.getUUID().equals(target.getId())) {
            return Optional.of("You cannot report yourself.");
        }
        Optional<Instant> wait = store.claimCooldown(reporter == null ? "console" : "mc:" + reporter.getUUID(),
                config().cooldown(), Instant.now());
        if (wait.isPresent()) {
            return Optional.of("You can report again in " + Durations.formatCompact(
                    Duration.between(Instant.now(), wait.get()).plusSeconds(59)) + ".");
        }
        Report report = store.add(Report.open(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                source.getTextName(), OptionalLong.empty(), target.getId(), target.getName(), reason,
                Optional.empty()));
        Optional<String> reporterLocation = Optional.ofNullable(reporter).map(Reports::location);
        Optional<String> targetLocation = Optional.ofNullable(server.getPlayerList().getPlayer(target.getId()))
                .map(Reports::location);
        notifyAdmins(report, reporter);
        executor.execute(() -> post(report, reporterLocation, targetLocation, Optional.empty()));
        return Optional.empty();
    }

    /**
     * Runs {@code /mc report <player> <reason> [message]} in Discord.
     *
     * @param event the command interaction
     */
    void fromDiscord(SlashCommandInteractionEvent event) {
        String player = event.getOption("player", OptionMapping::getAsString);
        String reason = event.getOption("reason", OptionMapping::getAsString);
        String message = event.getOption("message", OptionMapping::getAsString);
        long reporterId = event.getUser().getIdLong();
        String reporter = Moderation.DISCORD_PREFIX + "@" + event.getUser().getName();
        Optional<MessageRef> reference = Optional.empty();
        if (message != null) {
            reference = parseMessage(message.strip(), event.getGuild() == null ? 0 : event.getGuild().getIdLong(),
                    event.getChannel().getIdLong());
            if (reference.isEmpty()) {
                replyError(event, "**" + DiscordText.escape(message) + "** is no message link or message ID.");
                return;
            }
        }
        Optional<MessageRef> messageRef = reference;
        event.deferReply(true).queue();
        CompletableFuture.supplyAsync(() -> server.getProfileCache().get(player), executor).thenAccept(profile -> {
            if (profile.isEmpty()) {
                event.getHook().sendMessageEmbeds(CommandEmbeds.error("No Minecraft player named **"
                        + DiscordText.escape(player) + "** was found.")).queue();
                return;
            }
            if (linking.links().player(reporterId).map(profile.get().getId()::equals).orElse(false)) {
                event.getHook().sendMessageEmbeds(CommandEmbeds.error("You cannot report yourself.")).queue();
                return;
            }
            Optional<Instant> wait = store.claimCooldown("discord:" + reporterId, config().cooldown(), Instant.now());
            if (wait.isPresent()) {
                event.getHook().sendMessageEmbeds(CommandEmbeds.error("You can report again <t:"
                        + wait.get().getEpochSecond() + ":R>.")).queue();
                return;
            }
            Optional<QuotedMessage> quoted = messageRef.map(this::retrieve);
            Report report = store.add(Report.open(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                    reporter, OptionalLong.of(reporterId), profile.get().getId(), profile.get().getName(), reason,
                    messageRef.map(MessageRef::link)));
            CompletableFuture.supplyAsync(() -> {
                notifyAdmins(report, null);
                return Optional.ofNullable(server.getPlayerList().getPlayer(profile.get().getId()))
                        .map(Reports::location);
            }, server).thenAccept(targetLocation -> {
                post(report, Optional.empty(), targetLocation, quoted);
                event.getHook().sendMessageEmbeds(CommandEmbeds.reply(Linking.Reply.success("Thanks! Your report "
                        + "about **" + DiscordText.escape(report.name()) + "** was sent to the staff."))).queue();
            });
        });
    }

    /**
     * Tells the online admins in Minecraft about a report. Must be called on the server thread.
     *
     * @param report   the report
     * @param reporter the reporting player, who is not told twice; {@code null} if not a player
     */
    private void notifyAdmins(Report report, ServerPlayer reporter) {
        Component line = Component.literal("[Report #" + report.id() + "] " + report.reporter() + " reported "
                + report.name() + ": " + report.reason()).withStyle(ChatFormatting.GOLD);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player != reporter && Services.PLATFORM.isAdmin(player.createCommandSourceStack())) {
                player.sendSystemMessage(line);
            }
        }
    }

    /**
     * Sends a report to the report channel with its buttons. Runs on the bot's executor.
     *
     * @param report           the report
     * @param reporterLocation where the reporting player was
     * @param targetLocation   where the reported player was, if online
     * @param quoted           the reported Discord message
     */
    private void post(Report report, Optional<String> reporterLocation, Optional<String> targetLocation,
                      Optional<QuotedMessage> quoted) {
        Optional<GuildMessageChannel> channel = channel();
        if (channel.isEmpty()) {
            Constants.LOG.error("Report #{} could not be sent: set 'moderation.reports_channel_id' or "
                    + "'moderation.channel_id' in the config.", report.id());
            return;
        }
        try {
            channel.get().sendMessageEmbeds(SlashCommands.tag(List.of(embed(report, reporterLocation,
                            targetLocation, quoted))))
                    .setComponents(buttons(report.id()))
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                    .queue(message -> store.setMessage(report.id(), channel.get().getIdLong(), message.getIdLong()),
                            error -> Constants.LOG.error("Could not send report #{}: {}", report.id(),
                                    error.getMessage()));
        } catch (InsufficientPermissionException e) {
            PermissionWarnings.missing(e.getPermission().getName(), "#" + channel.get().getName(), "reports");
        }
    }

    // ---- Acting on reports ----

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Optional<Target> target = parse(event.getComponentId(), PREFIX);
        if (target.isEmpty()) {
            return;
        }
        if (!mayHandle(event.getMember())) {
            replyError(event, "You may not act on reports.");
            return;
        }
        Optional<Report> report = store.get(target.get().reportId())
                .filter(open -> open.status() == ReportStore.Status.OPEN);
        if (report.isEmpty()) {
            replyError(event, "This report was closed already.");
            return;
        }
        Action action = target.get().action();
        if (action == Action.DONE || action == Action.DISMISS) {
            close(event, report.get(), action == Action.DONE ? ReportStore.Status.HANDLED
                    : ReportStore.Status.DISMISSED, Optional.empty());
            return;
        }
        Modal.Builder form = Modal.create(FORM + action.name().toLowerCase(Locale.ROOT) + "|" + report.get().id()
                        + "|" + serverName(), action.label + " " + report.get().name())
                .addComponents(Label.of("Reason", TextInput.create(REASON_FIELD, TextInputStyle.PARAGRAPH)
                        .setRequiredRange(1, 500).setValue(limit(report.get().reason(), 500)).build()));
        if (action == Action.MUTE || action == Action.BAN) {
            form.addComponents(Label.of(action == Action.MUTE ? "Duration, e.g. 1h or 1d (at most 28d)"
                    : "Duration, e.g. 7d; empty = permanent", TextInput.create(DURATION_FIELD, TextInputStyle.SHORT)
                    .setRequired(action == Action.MUTE).setMaxLength(20).build()));
        }
        event.replyModal(form.build()).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Optional<Target> target = parse(event.getModalId(), FORM);
        if (target.isEmpty()) {
            return;
        }
        if (!mayHandle(event.getMember())) {
            replyError(event, "You may not act on reports.");
            return;
        }
        Optional<Report> found = store.get(target.get().reportId())
                .filter(open -> open.status() == ReportStore.Status.OPEN);
        if (found.isEmpty()) {
            replyError(event, "This report was closed already.");
            return;
        }
        Report report = found.get();
        String reason = Optional.ofNullable(event.getValue(REASON_FIELD)).map(ModalMapping::getAsString).orElse("")
                .strip();
        String durationText = Optional.ofNullable(event.getValue(DURATION_FIELD)).map(ModalMapping::getAsString)
                .orElse("").strip();
        Optional<Duration> duration = durationText.isEmpty() ? Optional.empty() : Durations.parse(durationText);
        if (!durationText.isEmpty() && duration.isEmpty()) {
            replyError(event, "Invalid duration **" + DiscordText.escape(durationText)
                    + "**. Use e.g. 30m, 12h, 7d, 2w or 1d12h.");
            return;
        }
        String moderator = Moderation.DISCORD_PREFIX + "@" + event.getUser().getName();
        ModerationContext.Scope scope = ModerationContext.Scope.of(moderator, reason);
        GameProfile profile = new GameProfile(report.player(), report.name());
        event.deferReply(true).queue();
        CompletableFuture.supplyAsync(() -> switch (target.get().action()) {
            case WARN -> moderation.warn(profile, reason, scope);
            case MUTE -> moderation.mute(profile, reason, duration.orElse(Duration.ZERO), scope);
            case KICK -> moderation.kick(report.name(), reason, scope);
            default -> moderation.ban(profile, reason, duration, scope);
        }, server).thenAccept(result -> {
            if (result.success()) {
                store.close(report.id(), ReportStore.Status.HANDLED, moderator, Optional.of(result.message()))
                        .ifPresent(this::updateMessage);
            }
            event.getHook().sendMessageEmbeds(CommandEmbeds.reply(new Linking.Reply(result.success(),
                    DiscordText.escape(result.message())))).queue();
        });
    }

    /**
     * Marks a report as done or dismissed.
     *
     * @param event   the button interaction
     * @param report  the report
     * @param status  the new state
     * @param outcome what was done
     */
    private void close(ButtonInteractionEvent event, Report report, ReportStore.Status status,
                       Optional<String> outcome) {
        String moderator = Moderation.DISCORD_PREFIX + "@" + event.getUser().getName();
        Optional<Report> closed = store.close(report.id(), status, moderator, outcome);
        if (closed.isEmpty()) {
            replyError(event, "This report was closed already.");
            return;
        }
        event.editMessageEmbeds(SlashCommands.tag(List.of(updatedEmbed(event.getMessage(), closed.get()))))
                .setComponents().queue();
    }

    /**
     * Shows the outcome in the message of a closed report and removes its buttons.
     *
     * @param report the closed report
     */
    private void updateMessage(Report report) {
        if (report.channelId() == 0 || report.messageId() == 0) {
            return;
        }
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, report.channelId());
        if (channel == null) {
            return;
        }
        channel.retrieveMessageById(report.messageId())
                .flatMap(message -> message.editMessageEmbeds(SlashCommands.tag(List.of(updatedEmbed(message,
                        report)))).setComponents())
                .queue(null, error -> Constants.LOG.error("Could not update report #{}: {}", report.id(),
                        error.getMessage()));
    }

    /**
     * Adds the outcome to the embed of a report message.
     *
     * @param message the report message
     * @param report  the closed report
     * @return the changed embed
     */
    private static MessageEmbed updatedEmbed(Message message, Report report) {
        EmbedBuilder embed = message.getEmbeds().isEmpty() ? new EmbedBuilder()
                : new EmbedBuilder(message.getEmbeds().getFirst());
        String by = DiscordText.escape(report.closedBy().orElse("?"));
        if (report.status() == ReportStore.Status.DISMISSED) {
            embed.setColor(EmbedColors.GRAY).addField("Dismissed", "by " + by, false);
        } else {
            embed.setColor(EmbedColors.GREEN).addField("✅ Handled", "by " + by
                    + report.outcome().map(outcome -> ": " + DiscordText.escape(outcome)).orElse(""), false);
        }
        return embed.build();
    }

    // ---- Helpers ----

    /**
     * Builds the embed of a new report.
     *
     * @param report           the report
     * @param reporterLocation where the reporting player was
     * @param targetLocation   where the reported player was
     * @param quoted           the reported Discord message
     * @return the embed
     */
    static MessageEmbed embed(Report report, Optional<String> reporterLocation, Optional<String> targetLocation,
                              Optional<QuotedMessage> quoted) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🚩 Report #" + report.id() + ": " + DiscordText.escape(report.name()),
                        NetworkBans.DATA_URL + report.player())
                .setThumbnail(ChatBridge.avatarUrl(ConfigManager.get().chat(), report.player(), report.name()))
                .setColor(EmbedColors.ORANGE)
                .setTimestamp(report.time())
                .addField("Reported by", report.reporterDiscordId().isPresent()
                        ? "<@" + report.reporterDiscordId().getAsLong() + ">"
                        : DiscordText.escape(report.reporter()), true);
        reporterLocation.ifPresent(location -> embed.addField("Reporter at", location, true));
        targetLocation.ifPresent(location -> embed.addField(DiscordText.escape(report.name()) + " at", location,
                true));
        embed.addField("Reason", limit(DiscordText.escape(report.reason()), MessageEmbed.VALUE_MAX_LENGTH), false);
        report.message().ifPresent(link -> embed.addField("Reported message", quoted
                .map(message -> limit("**" + DiscordText.escape(message.author()) + ":** "
                        + DiscordText.escape(message.content()), 900) + "\n" + link)
                .orElse(link), false));
        return embed.build();
    }

    private static List<ActionRow> buttons(long reportId) {
        String suffix = "|" + reportId + "|" + serverName();
        return List.of(
                ActionRow.of(
                        Button.secondary(PREFIX + "warn" + suffix, Action.WARN.label),
                        Button.secondary(PREFIX + "mute" + suffix, Action.MUTE.label),
                        Button.secondary(PREFIX + "kick" + suffix, Action.KICK.label),
                        Button.danger(PREFIX + "ban" + suffix, Action.BAN.label)),
                ActionRow.of(
                        Button.success(PREFIX + "done" + suffix, Action.DONE.label),
                        Button.secondary(PREFIX + "dismiss" + suffix, Action.DISMISS.label)));
    }

    /**
     * Describes where a player is, e.g. {@code overworld 12 64 -30}.
     *
     * @param player the player
     * @return the dimension and block position
     */
    private static String location(ServerPlayer player) {
        return player.serverLevel().dimension().location().getPath() + " " + player.getBlockX() + " "
                + player.getBlockY() + " " + player.getBlockZ();
    }

    /**
     * Reads a reported message for the report, waiting for Discord. Runs on the bot's executor.
     *
     * @param reference the message
     * @return the author and content, empty if the message cannot be read
     */
    private QuotedMessage retrieve(MessageRef reference) {
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, reference.channelId());
        if (channel == null) {
            return null;
        }
        try {
            Message message = channel.retrieveMessageById(reference.messageId()).complete();
            String content = message.getContentDisplay().isBlank() ? "(no text)" : message.getContentDisplay();
            return new QuotedMessage(message.getAuthor().getName(), limit(content, MAX_QUOTE));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads a message link, or a message ID in the channel the command was used in.
     *
     * @param text           the option
     * @param guild          the Discord server the command was used in
     * @param commandChannel the channel the command was used in
     * @return the message, empty if the text is neither
     */
    static Optional<MessageRef> parseMessage(String text, long guild, long commandChannel) {
        Matcher link = MESSAGE_LINK.matcher(text);
        if (link.matches()) {
            return Optional.of(new MessageRef(Long.parseLong(link.group(2)), Long.parseLong(link.group(3)), text));
        }
        if (SNOWFLAKE.matcher(text).matches()) {
            return Optional.of(new MessageRef(commandChannel, Long.parseLong(text),
                    "https://discord.com/channels/" + guild + "/" + commandChannel + "/" + text));
        }
        return Optional.empty();
    }

    private Optional<GuildMessageChannel> channel() {
        OptionalLong id = config().channelId();
        if (id.isEmpty()) {
            id = ConfigManager.get().moderation().channelId();
        }
        return id.isEmpty() ? Optional.empty()
                : Optional.ofNullable(jda.getChannelById(GuildMessageChannel.class, id.getAsLong()));
    }

    /**
     * Checks whether a member may act on reports: a member with a handler role, or with Administrator if no roles
     * are configured.
     *
     * @param member the member, {@code null} outside a Discord server
     * @return whether the member may act
     */
    private static boolean mayHandle(Member member) {
        if (member == null) {
            return false;
        }
        List<Long> roles = config().handlerRoleIds();
        if (roles.isEmpty()) {
            return member.hasPermission(Permission.ADMINISTRATOR);
        }
        return member.getRoles().stream().anyMatch(role -> roles.contains(role.getIdLong()));
    }

    /**
     * Reads the action and report of a component ID such as {@code mdlr|mute|12|Survival}.
     *
     * @param id     the component ID
     * @param prefix the expected start
     * @return the action and report, empty if the ID is not for this server
     */
    private static Optional<Target> parse(String id, String prefix) {
        if (!id.startsWith(prefix) || id.startsWith(FORM) != prefix.equals(FORM)) {
            return Optional.empty();
        }
        String[] parts = id.substring(prefix.length()).split("\\|", 3);
        if (parts.length < 3 || !parts[2].equals(serverName())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Target(Action.valueOf(parts[0].toUpperCase(Locale.ROOT)),
                    Long.parseLong(parts[1])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static ModConfig.Reports config() {
        return ConfigManager.get().moderation().reports();
    }

    private static String serverName() {
        return ConfigManager.get().server().name();
    }

    private static void replyError(IReplyCallback event, String message) {
        event.replyEmbeds(CommandEmbeds.error(message)).setEphemeral(true).queue();
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /**
     * An action on a report from a component ID.
     *
     * @param action   the action
     * @param reportId the report
     */
    private record Target(Action action, long reportId) {
    }

    /**
     * A reported Discord message.
     *
     * @param channelId the channel
     * @param messageId the message
     * @param link      the link shown in the report
     */
    record MessageRef(long channelId, long messageId, String link) {
    }

    /**
     * The author and text of a reported message.
     *
     * @param author  the author's name
     * @param content the text
     */
    record QuotedMessage(String author, String content) {
    }
}
