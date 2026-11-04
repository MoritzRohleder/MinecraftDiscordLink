package de.moritzrohleder.minecraftdiscordlink.discord;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.data.StateStore;
import de.moritzrohleder.minecraftdiscordlink.moderation.Moderation;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationEntry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Bans, unbans, mutes and unmutes on all servers sharing the Discord server.
 * <p>
 * The servers do not talk to each other directly; each runs its own connection with the same bot. The server where
 * a ban on all servers is made posts it to the shared moderation channel as usual; the title of the embed links to the
 * player's NameMC profile, with the ban as machine-readable data in the query (NameMC ignores it). The visible text
 * is formatted for people and configurable, so it is not read back. The other servers read the moderation channel,
 * take only such embeds sent by the bot itself, and apply the ban with the same end, reason and moderator. They log
 * it to their own log file but do not post it again. A server that was not running catches up on start from the
 * last message it checked, stored in {@code state.json}.
 */
final class NetworkBans extends ListenerAdapter {

    /** Start of the data URLs: the NameMC profile of the player. */
    static final String DATA_URL = "https://namemc.com/profile/";
    /** Query parameter marking a data URL. */
    private static final String MARKER = "mdl";
    /** Longest reason carried in the data URL. */
    private static final int MAX_REASON = 500;
    /** Number of messages read per request when catching up. */
    private static final int HISTORY_PAGE = 100;

    private final JDA jda;
    private final MinecraftServer server;
    private final Moderation moderation;
    private final StateStore state;

    /**
     * Creates the receiver; register it as JDA event listener and call {@link #catchUp()} once the server runs.
     *
     * @param jda        the bot
     * @param server     the running server
     * @param moderation the moderation, to apply received bans
     * @param state      the stored state, for the last checked message
     */
    NetworkBans(JDA jda, MinecraftServer server, Moderation moderation, StateStore state) {
        this.jda = jda;
        this.server = server;
        this.moderation = moderation;
        this.state = state;
    }

    /**
     * Builds the data URL of a ban or unban on all servers, for the title of its moderation embed.
     *
     * @param entry the moderation log entry
     * @return the URL, empty if the entry is no ban or unban on all servers made on this server
     */
    static Optional<String> dataUrl(ModerationEntry entry) {
        String action = switch (entry.action()) {
            case BAN, BAN_UPDATED -> "ban";
            case UNBAN -> "unban";
            case MUTE, MUTE_UPDATED -> "mute";
            case UNMUTE -> "unmute";
            default -> null;
        };
        if (action == null || entry.network() != ModerationEntry.Network.ALL_SERVERS || entry.player().isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(MARKER, "1");
        values.put("action", action);
        values.put("uuid", entry.player().get().toString());
        values.put("name", entry.target());
        values.put("until", entry.until().map(time -> String.valueOf(time.getEpochSecond())).orElse("0"));
        values.put("moderator", entry.moderator());
        String reason = entry.reason().orElse("");
        values.put("reason", reason.length() > MAX_REASON ? reason.substring(0, MAX_REASON) : reason);
        values.put("origin", ConfigManager.get().server().name());
        StringBuilder url = new StringBuilder(DATA_URL).append(entry.player().get()).append('?');
        values.forEach((key, value) -> url.append(url.charAt(url.length() - 1) == '?' ? "" : "&").append(key)
                .append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return Optional.of(url.toString());
    }

    /**
     * Applies bans on all servers posted by another server while this server runs.
     *
     * @param event the received message
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        OptionalLong channel = ConfigManager.get().moderation().channelId();
        if (channel.isEmpty() || event.getChannel().getIdLong() != channel.getAsLong()
                || event.getAuthor().getIdLong() != jda.getSelfUser().getIdLong()) {
            return;
        }
        handle(event.getMessage());
    }

    /**
     * Applies the bans on all servers posted while this server was not running. Blocks; call it on the bot's
     * executor once the server has started.
     */
    void catchUp() {
        OptionalLong channelId = ConfigManager.get().moderation().channelId();
        if (channelId.isEmpty() || !ConfigManager.get().server().named()) {
            return;
        }
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, channelId.getAsLong());
        if (channel == null) {
            return;
        }
        OptionalLong last = state.networkMessage();
        if (last.isEmpty()) {
            // First start: older bans are not applied, only the ones from now on
            if (channel.getLatestMessageIdLong() != 0) {
                state.setNetworkMessage(channel.getLatestMessageIdLong());
            }
            return;
        }
        try {
            long after = last.getAsLong();
            List<Message> page;
            do {
                page = new ArrayList<>(channel.getHistoryAfter(after, HISTORY_PAGE).complete().getRetrievedHistory());
                // Oldest first, so a ban and a later unban end as unbanned
                page.sort((first, second) -> Long.compare(first.getIdLong(), second.getIdLong()));
                for (Message message : page) {
                    if (message.getAuthor().getIdLong() == jda.getSelfUser().getIdLong()) {
                        handle(message);
                    }
                    after = message.getIdLong();
                }
                state.setNetworkMessage(after);
            } while (page.size() == HISTORY_PAGE);
        } catch (RuntimeException e) {
            Constants.LOG.error("Could not read the moderation channel for missed bans on all servers: {}",
                    e.getMessage());
        }
    }

    /**
     * Applies a ban on all servers carried by a message of the bot, unless this server made it.
     *
     * @param message the message
     */
    private void handle(Message message) {
        Optional<Map<String, String>> data = message.getEmbeds().stream().map(MessageEmbed::getUrl)
                .filter(url -> url != null && url.startsWith(DATA_URL)).findFirst().map(NetworkBans::parse)
                .filter(values -> "1".equals(values.get(MARKER)));
        state.setNetworkMessage(message.getIdLong());
        if (data.isEmpty() || data.get().getOrDefault("origin", "").equals(ConfigManager.get().server().name())) {
            return;
        }
        Map<String, String> values = data.get();
        try {
            UUID uuid = UUID.fromString(values.get("uuid"));
            String name = values.getOrDefault("name", uuid.toString());
            long until = Long.parseLong(values.getOrDefault("until", "0"));
            String reason = values.getOrDefault("reason", "");
            String moderator = values.getOrDefault("moderator", "unknown") + Moderation.VIA + values.get("origin");
            ModerationContext.Scope scope = ModerationContext.Scope.received(moderator, reason);
            GameProfile profile = new GameProfile(uuid, name);
            server.execute(() -> {
                if ("ban".equals(values.get("action"))) {
                    Optional<Instant> end = until == 0 ? Optional.empty() : Optional.of(Instant.ofEpochSecond(until));
                    if (end.isEmpty() || end.get().isAfter(Instant.now())) {
                        moderation.banUntil(profile, reason, end, scope);
                    }
                } else if ("unban".equals(values.get("action"))) {
                    moderation.unban(profile, reason, scope);
                } else if ("mute".equals(values.get("action")) && until != 0
                        && Instant.ofEpochSecond(until).isAfter(Instant.now())) {
                    moderation.muteUntil(profile, reason, Instant.ofEpochSecond(until), scope);
                } else if ("unmute".equals(values.get("action"))) {
                    moderation.unmute(profile, reason, scope);
                }
            });
        } catch (IllegalArgumentException | NullPointerException e) {
            Constants.LOG.warn("Ignoring an invalid ban on all servers in the moderation channel: {}", e.toString());
        }
    }

    /**
     * Reads the values of a data URL.
     *
     * @param url the URL
     * @return the values by name
     */
    static Map<String, String> parse(String url) {
        Map<String, String> values = new HashMap<>();
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return values;
        }
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0) {
                values.put(pair.substring(0, separator),
                        URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }
}
