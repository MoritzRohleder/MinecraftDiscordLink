package de.moritzrohleder.minecraftdiscordlink.discord;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.applications.ApplicationStore;
import de.moritzrohleder.minecraftdiscordlink.applications.ApplicationStore.Answer;
import de.moritzrohleder.minecraftdiscordlink.applications.ApplicationStore.Application;
import de.moritzrohleder.minecraftdiscordlink.applications.ApplicationStore.Refusal;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.text.DiscordText;
import de.moritzrohleder.minecraftdiscordlink.text.Placeholders;
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
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Whitelist applications ({@code features.whitelist.applications}): a message with an apply button in the
 * application channel opens a form with the Minecraft name and the configured questions. Applications go to the
 * review channel with Accept and Deny buttons; accepted applicants are linked and whitelisted, every decision goes
 * to the moderation log with the staff member, and the applicant gets a direct message.
 * <p>
 * Applications are only possible while the whitelist is on. Component IDs end with the server name, so several
 * servers can share the Discord server.
 */
final class Applications extends ListenerAdapter {

    private static final String PREFIX = "mdl|app";
    private static final String APPLY = PREFIX + "apply|";
    private static final String FORM = PREFIX + "form|";
    private static final String ACCEPT = PREFIX + "accept|";
    private static final String DENY = PREFIX + "deny|";
    private static final String DENY_FORM = PREFIX + "denyform|";
    private static final String NAME_FIELD = "name";
    private static final String REASON_FIELD = "reason";
    private static final Pattern MINECRAFT_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final JDA jda;
    private final MinecraftServer server;
    private final Linking linking;
    private final Moderation moderation;
    private final ApplicationStore store;
    private final AccountLinking accounts;
    private final Executor executor;

    /**
     * Creates the applications.
     *
     * @param jda        the bot
     * @param server     the Minecraft server
     * @param linking    the account linking
     * @param moderation the moderation, for the whitelist and the log
     * @param store      the stored applications
     * @param accounts   the account linking on Discord, for direct messages
     * @param executor   the bot's executor, for work that may block
     */
    Applications(JDA jda, MinecraftServer server, Linking linking, Moderation moderation, ApplicationStore store,
                 AccountLinking accounts, Executor executor) {
        this.jda = jda;
        this.server = server;
        this.linking = linking;
        this.moderation = moderation;
        this.store = store;
        this.accounts = accounts;
        this.executor = executor;
    }

    /**
     * Sends, updates or removes the message with the apply button after the start or a reload: switched on with an
     * application channel, the message is there with the current text; otherwise a message sent before is deleted.
     */
    void apply() {
        ModConfig.Applications config = ConfigManager.get().whitelist().applications();
        Optional<long[]> stored = store.buttonMessage();
        OptionalLong channelId = config.enabled() ? config.channelId() : OptionalLong.empty();
        if (stored.isPresent() && (channelId.isEmpty() || stored.get()[0] != channelId.getAsLong())) {
            GuildMessageChannel old = jda.getChannelById(GuildMessageChannel.class, stored.get()[0]);
            if (old != null) {
                old.deleteMessageById(stored.get()[1]).queue(null, error -> { });
            }
            store.setButtonMessage(0, 0);
            stored = Optional.empty();
        }
        if (channelId.isEmpty()) {
            if (config.enabled()) {
                Constants.LOG.error("'whitelist.applications_channel_id' is missing in the config. The whitelist "
                        + "applications stay inactive.");
            }
            return;
        }
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, channelId.getAsLong());
        if (channel == null) {
            Constants.LOG.error("The application channel {} was not found or the bot cannot see it.",
                    channelId.getAsLong());
            return;
        }
        List<ActionRow> button = List.of(ActionRow.of(Button.primary(APPLY + serverName(), config.button())));
        try {
            if (stored.isPresent()) {
                channel.editMessageById(stored.get()[1], config.message()).setComponents(button)
                        .queue(null, error -> sendButtonMessage(channel, config.message(), button));
            } else {
                sendButtonMessage(channel, config.message(), button);
            }
        } catch (InsufficientPermissionException e) {
            PermissionWarnings.missing(e.getPermission().getName(), "#" + channel.getName(), "whitelist applications");
        }
    }

    private void sendButtonMessage(GuildMessageChannel channel, String text, List<ActionRow> button) {
        try {
            channel.sendMessage(text).setComponents(button)
                    .queue(message -> store.setButtonMessage(channel.getIdLong(), message.getIdLong()),
                            error -> Constants.LOG.error("Could not send the apply button: {}", error.getMessage()));
        } catch (InsufficientPermissionException e) {
            PermissionWarnings.missing(e.getPermission().getName(), "#" + channel.getName(), "whitelist applications");
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) {
            return;
        }
        if (id.equals(APPLY + serverName())) {
            openForm(event);
        } else if (id.startsWith(ACCEPT) && id.endsWith("|" + serverName())) {
            parseApplicant(id, ACCEPT).ifPresent(applicant -> accept(event, applicant));
        } else if (id.startsWith(DENY) && id.endsWith("|" + serverName())) {
            parseApplicant(id, DENY).ifPresent(applicant -> openDenyForm(event, applicant));
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (id.equals(FORM + serverName())) {
            submit(event);
        } else if (id.startsWith(DENY_FORM) && id.endsWith("|" + serverName())) {
            parseApplicant(id, DENY_FORM).ifPresent(applicant -> deny(event, applicant));
        }
    }

    // ---- Applying ----

    /**
     * Opens the application form after the apply button, or explains why the member cannot apply.
     *
     * @param event the button interaction
     */
    private void openForm(ButtonInteractionEvent event) {
        ModConfig.Applications config = ConfigManager.get().whitelist().applications();
        Optional<String> problem = applyProblem(event.getUser().getIdLong(), config);
        if (problem.isPresent()) {
            replyError(event, problem.get());
            return;
        }
        Modal.Builder form = Modal.create(FORM + serverName(), "Whitelist application")
                .addComponents(Label.of("Minecraft name", TextInput.create(NAME_FIELD, TextInputStyle.SHORT)
                        .setRequiredRange(3, 16).build()));
        for (int i = 0; i < config.questions().size(); i++) {
            form.addComponents(Label.of(limit(config.questions().get(i), Label.LABEL_MAX_LENGTH),
                    TextInput.create("q" + i, TextInputStyle.PARAGRAPH).setRequiredRange(1, 1000).build()));
        }
        event.replyModal(form.build()).queue();
    }

    /**
     * Checks whether a member may apply now.
     *
     * @param discordId the member
     * @param config    the application settings
     * @return why not, empty if the member may apply
     */
    private Optional<String> applyProblem(long discordId, ModConfig.Applications config) {
        if (!config.enabled()) {
            return Optional.of("Applications are closed.");
        }
        if (!server.getPlayerList().isUsingWhitelist()) {
            return Optional.of("The server has no whitelist right now – you can just join.");
        }
        return store.refusal(discordId, config.reapplyAfter(), Instant.now()).map(refusal -> switch (refusal.kind()) {
            case OPEN -> "Your application is being reviewed. You will get a direct message.";
            case ACCEPTED -> "Your application was accepted already.";
            case WAIT -> "You can apply again <t:" + refusal.until().orElseThrow().getEpochSecond() + ":R>.";
        });
    }

    /**
     * Handles a sent application form: checks the Minecraft name and sends the application for review.
     *
     * @param event the form interaction
     */
    private void submit(ModalInteractionEvent event) {
        ModConfig.Applications config = ConfigManager.get().whitelist().applications();
        long discordId = event.getUser().getIdLong();
        Optional<String> problem = applyProblem(discordId, config);
        if (problem.isPresent()) {
            replyError(event, problem.get());
            return;
        }
        String name = Optional.ofNullable(event.getValue(NAME_FIELD)).map(ModalMapping::getAsString).orElse("")
                .strip();
        if (!MINECRAFT_NAME.matcher(name).matches()) {
            replyError(event, "**" + DiscordText.escape(name) + "** is no valid Minecraft name.");
            return;
        }
        List<Answer> answers = new ArrayList<>();
        for (int i = 0; i < config.questions().size(); i++) {
            ModalMapping answer = event.getValue("q" + i);
            answers.add(new Answer(config.questions().get(i), answer == null ? "" : answer.getAsString().strip()));
        }
        event.deferReply(true).queue();
        // Looking up the player may contact Mojang
        CompletableFuture.supplyAsync(() -> server.getProfileCache().get(name), executor).thenAccept(profile -> {
            Optional<String> error = profile.isEmpty()
                    ? Optional.of("No Minecraft player named **" + DiscordText.escape(name) + "** was found.")
                    : linkProblem(profile.get(), discordId);
            if (error.isPresent()) {
                event.getHook().sendMessageEmbeds(CommandEmbeds.error(error.get())).queue();
                return;
            }
            Application application = Application.open(discordId, profile.get().getId(), profile.get().getName(),
                    answers, Instant.now());
            Optional<GuildMessageChannel> review = reviewChannel();
            if (review.isEmpty()) {
                Constants.LOG.error("Whitelist applications have no review channel: set "
                        + "'whitelist.review_channel_id' or 'moderation.channel_id' in the config.");
                event.getHook().sendMessageEmbeds(CommandEmbeds.error("Applications cannot be reviewed right now. "
                        + "Please tell the staff.")).queue();
                return;
            }
            store.submit(application);
            try {
                review.get().sendMessageEmbeds(SlashCommands.tag(List.of(reviewEmbed(application))))
                        .setComponents(ActionRow.of(
                                Button.success(ACCEPT + discordId + "|" + serverName(), "Accept"),
                                Button.danger(DENY + discordId + "|" + serverName(), "Deny")))
                        .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                        .queue(message -> store.setReviewMessage(discordId, review.get().getIdLong(),
                                message.getIdLong()));
            } catch (InsufficientPermissionException e) {
                PermissionWarnings.missing(e.getPermission().getName(), "#" + review.get().getName(),
                        "whitelist applications");
            }
            event.getHook().sendMessageEmbeds(CommandEmbeds.reply(Linking.Reply.success("Your application for **"
                    + DiscordText.escape(application.name()) + "** was sent. You will get a direct message when "
                    + "it is decided."))).queue();
        });
    }

    /**
     * Checks whether a Minecraft player can be whitelisted and linked to an applicant.
     *
     * @param profile   the player
     * @param discordId the applicant
     * @return why not, empty if possible
     */
    private Optional<String> linkProblem(GameProfile profile, long discordId) {
        String name = "**" + DiscordText.escape(profile.getName()) + "**";
        if (server.getPlayerList().getWhiteList().isWhiteListed(profile)) {
            return Optional.of(name + " is already whitelisted.");
        }
        OptionalLong linkedTo = linking.links().discordId(profile.getId());
        if (linkedTo.isPresent() && linkedTo.getAsLong() != discordId) {
            return Optional.of(name + " is linked to another Discord account.");
        }
        Optional<UUID> ownPlayer = linking.links().player(discordId);
        if (ownPlayer.isPresent() && !ownPlayer.get().equals(profile.getId())) {
            return Optional.of("Your Discord account is linked to another Minecraft player. Apply with that name "
                    + "or unlink it first with `/mc unlink`.");
        }
        return Optional.empty();
    }

    // ---- Reviewing ----

    /**
     * Accepts an application: links the accounts, whitelists the player, logs the decision, updates the review
     * message and tells the applicant.
     *
     * @param event     the button interaction
     * @param applicant the applicant's Discord ID
     */
    private void accept(ButtonInteractionEvent event, long applicant) {
        if (!mayReview(event.getMember())) {
            replyError(event, "You may not review applications.");
            return;
        }
        Optional<Application> open = store.latest(applicant)
                .filter(application -> application.status() == ApplicationStore.Status.OPEN);
        if (open.isEmpty()) {
            replyError(event, "This application was decided already.");
            return;
        }
        Application application = open.get();
        String moderator = Moderation.DISCORD_PREFIX + "@" + event.getUser().getName();
        event.deferReply(true).queue();
        CompletableFuture.supplyAsync(() -> {
            // Linking may contact Mojang; the link is made first, so the log shows the linked account
            if (ConfigManager.get().linking().enabled()
                    && linking.links().discordId(application.player()).isEmpty()) {
                Linking.Reply link = linking.adminLink(application.name(), applicant);
                if (!link.success()) {
                    return link;
                }
            }
            return Linking.Reply.success("");
        }, executor).thenCompose(link -> !link.success() ? CompletableFuture.completedFuture(link)
                : CompletableFuture.supplyAsync(() -> moderation.acceptApplication(
                        new GameProfile(application.player(), application.name()), moderator), server))
                .thenAccept(reply -> {
                    if (!reply.success()) {
                        event.getHook().sendMessageEmbeds(CommandEmbeds.error(DiscordText.escape(reply.message())))
                                .queue();
                        return;
                    }
                    Optional<Application> decided = store.decide(applicant, true, moderator, Optional.empty(),
                            Instant.now());
                    decided.ifPresent(this::updateReviewMessage);
                    accounts.sendDirectMessage(applicant, Placeholders.resolve(
                            ConfigManager.get().whitelist().applications().acceptedDm(),
                            Map.of("player", DiscordText.escape(application.name()))), () -> { }, () -> { });
                    event.getHook().sendMessageEmbeds(CommandEmbeds.reply(Linking.Reply.success("Accepted the "
                            + "application of **" + DiscordText.escape(application.name()) + "**."))).queue();
                });
    }

    /**
     * Opens the form for the reason of a denial.
     *
     * @param event     the button interaction
     * @param applicant the applicant's Discord ID
     */
    private void openDenyForm(ButtonInteractionEvent event, long applicant) {
        if (!mayReview(event.getMember())) {
            replyError(event, "You may not review applications.");
            return;
        }
        event.replyModal(Modal.create(DENY_FORM + applicant + "|" + serverName(), "Deny the application")
                .addComponents(Label.of("Reason (sent to the applicant)",
                        TextInput.create(REASON_FIELD, TextInputStyle.PARAGRAPH).setRequiredRange(1, 500).build()))
                .build()).queue();
    }

    /**
     * Denies an application with the reason from the form: logs the decision, updates the review message and tells
     * the applicant.
     *
     * @param event     the form interaction
     * @param applicant the applicant's Discord ID
     */
    private void deny(ModalInteractionEvent event, long applicant) {
        if (!mayReview(event.getMember())) {
            replyError(event, "You may not review applications.");
            return;
        }
        String reason = Optional.ofNullable(event.getValue(REASON_FIELD)).map(ModalMapping::getAsString).orElse("")
                .strip();
        String moderator = Moderation.DISCORD_PREFIX + "@" + event.getUser().getName();
        Optional<Application> decided = store.decide(applicant, false, moderator, Optional.of(reason),
                Instant.now());
        if (decided.isEmpty()) {
            replyError(event, "This application was decided already.");
            return;
        }
        Application application = decided.get();
        server.execute(() -> moderation.denyApplication(new GameProfile(application.player(), application.name()),
                moderator, reason));
        updateReviewMessage(application);
        accounts.sendDirectMessage(applicant, Placeholders.resolve(
                ConfigManager.get().whitelist().applications().deniedDm(),
                Map.of("player", DiscordText.escape(application.name()), "reason", DiscordText.escape(reason))),
                () -> { }, () -> { });
        event.replyEmbeds(CommandEmbeds.reply(Linking.Reply.success("Denied the application of **"
                + DiscordText.escape(application.name()) + "**."))).setEphemeral(true).queue();
    }

    /**
     * Checks whether a member may accept and deny applications: a member with a reviewer role, or with
     * Administrator if no reviewer roles are configured.
     *
     * @param member the member, {@code null} outside a Discord server
     * @return whether the member may review
     */
    private static boolean mayReview(Member member) {
        if (member == null) {
            return false;
        }
        List<Long> roles = ConfigManager.get().whitelist().applications().reviewerRoleIds();
        if (roles.isEmpty()) {
            return member.hasPermission(Permission.ADMINISTRATOR);
        }
        return member.getRoles().stream().anyMatch(role -> roles.contains(role.getIdLong()));
    }

    /**
     * Shows the decision in the review message and removes its buttons.
     *
     * @param application the decided application
     */
    private void updateReviewMessage(Application application) {
        if (application.reviewChannel().isEmpty() || application.reviewMessage().isEmpty()) {
            return;
        }
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class,
                application.reviewChannel().getAsLong());
        if (channel != null) {
            channel.editMessageEmbedsById(application.reviewMessage().getAsLong(),
                    SlashCommands.tag(List.of(reviewEmbed(application)))).setComponents()
                    .queue(null, error -> Constants.LOG.error("Could not update the application message: {}",
                            error.getMessage()));
        }
    }

    /**
     * Builds the review message of an application: the applicant, the answers and, once decided, the decision.
     *
     * @param application the application
     * @return the embed
     */
    static MessageEmbed reviewEmbed(Application application) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("📨 Whitelist application: " + DiscordText.escape(application.name()),
                        NetworkBans.DATA_URL + application.player())
                .setThumbnail(ChatBridge.avatarUrl(ConfigManager.get().chat(), application.player(),
                        application.name()))
                .addField("Discord", "<@" + application.discordId() + ">", true)
                .addField("Minecraft", DiscordText.escape(application.name()), true)
                .setTimestamp(application.submitted());
        for (Answer answer : application.answers()) {
            embed.addField(limit(answer.question(), MessageEmbed.TITLE_MAX_LENGTH),
                    limit(DiscordText.escape(answer.answer()), MessageEmbed.VALUE_MAX_LENGTH), false);
        }
        switch (application.status()) {
            case OPEN -> embed.setColor(EmbedColors.BLURPLE);
            case ACCEPTED -> embed.setColor(EmbedColors.GREEN).addField("✅ Accepted",
                    "by " + DiscordText.escape(application.decidedBy().orElse("?")), false);
            case DENIED -> embed.setColor(EmbedColors.RED).addField("❌ Denied",
                    "by " + DiscordText.escape(application.decidedBy().orElse("?")) + ": "
                            + DiscordText.escape(application.reason().orElse("")), false);
        }
        return embed.build();
    }

    // ---- Helpers ----

    private Optional<GuildMessageChannel> reviewChannel() {
        OptionalLong id = ConfigManager.get().whitelist().applications().reviewChannelId();
        if (id.isEmpty()) {
            id = ConfigManager.get().moderation().channelId();
        }
        return id.isEmpty() ? Optional.empty()
                : Optional.ofNullable(jda.getChannelById(GuildMessageChannel.class, id.getAsLong()));
    }

    private static Optional<Long> parseApplicant(String id, String prefix) {
        String rest = id.substring(prefix.length());
        int separator = rest.indexOf('|');
        try {
            return Optional.of(Long.parseLong(separator < 0 ? rest : rest.substring(0, separator)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
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
}
