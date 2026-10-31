package de.moritzrohleder.minecraftdiscordlink.config;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Validated, immutable view of the config file {@code config/minecraftdiscordlink.toml}.
 * <p>
 * Instances are created by {@link ConfigLoader}. Values that were missing or invalid in the file are replaced by
 * their defaults. Values without a sensible default (bot token, channel IDs) are empty instead, which disables the
 * affected feature.
 *
 * @param server        this Minecraft server among several sharing one Discord server ({@code [server]})
 * @param discord       Discord bot settings ({@code [discord]})
 * @param chat          chat bridge settings ({@code [chat]})
 * @param console       console channel settings ({@code [console]})
 * @param whitelist     whitelist via Discord roles ({@code [whitelist]})
 * @param linking       account linking settings ({@code [linking]})
 * @param moderation    moderation log and ban sync ({@code [moderation]})
 * @param maintenance   the maintenance mode ({@code [maintenance]})
 * @param info          the information commands ({@code [info]})
 * @param monitoring    monitoring channel ({@code [monitoring]})
 * @param notifications direct messages to subscribers ({@code [notifications]})
 * @param leaderboard   leaderboards from the vanilla statistics ({@code [leaderboard]})
 * @param messages      the texts players and Discord members see ({@code [messages]})
 */
public record ModConfig(Server server, Discord discord, Chat chat, Console console, Whitelist whitelist,
                        Linking linking, Moderation moderation, Maintenance maintenance, Info info,
                        Monitoring monitoring, Notifications notifications, Leaderboard leaderboard,
                        Messages messages) {

    /**
     * This Minecraft server, for several servers sharing one Discord server.
     *
     * @param name    the name of this server, empty if it is the only one
     * @param primary whether this server answers slash commands without a {@code server} option
     */
    public record Server(String name, boolean primary) {

        /**
         * Checks whether this server has a name, i.e. shares the Discord server with others.
         *
         * @return whether a name is configured
         */
        public boolean named() {
            return !name.isEmpty();
        }
    }

    /**
     * Discord bot settings.
     *
     * @param token  the bot token, empty if none is configured; the whole mod is inactive without a token
     * @param status the activity shown for the bot
     */
    public record Discord(Optional<String> token, Status status) {
    }

    /**
     * Activity ("status") shown for the bot in Discord.
     *
     * @param enabled         whether a status is shown at all
     * @param type            the kind of activity
     * @param intervalMinutes minutes between status updates, at least 1
     * @param format            status text with placeholders such as {@code {online}}
     * @param maintenanceFormat status text while the maintenance mode is on
     */
    public record Status(boolean enabled, ActivityType type, int intervalMinutes, String format,
                         String maintenanceFormat) {
    }

    /**
     * Kinds of bot activities supported by Discord, as written in the config in lower case.
     */
    public enum ActivityType {
        /** Shown as "Playing &lt;text&gt;". */
        PLAYING,
        /** Shown as "Watching &lt;text&gt;". */
        WATCHING,
        /** Shown as "Listening to &lt;text&gt;". */
        LISTENING,
        /** Shown as "Competing in &lt;text&gt;". */
        COMPETING,
        /** Shown as the text only. */
        CUSTOM
    }

    /**
     * Settings of the chat bridge between Minecraft and the chat channel.
     *
     * @param enabled            whether the chat bridge, the events and the topic are active
     * @param channelId          ID of the chat channel, empty if not configured; the bridge is inactive then
     * @param webhook            how Minecraft messages are sent to Discord
     * @param minecraftFormat    format of Discord messages in Minecraft, placeholders {@code {name}}, {@code {message}}
     * @param linkedNameFormat   name of linked players, placeholders {@code {mc}}, {@code {discord}}
     * @param avatarUrl          URL of the webhook avatar, placeholders {@code {uuid}}, {@code {name}}
     * @param discordFormat      format of Minecraft messages without webhook, placeholders {@code {player}},
     *                           {@code {message}}
     * @param events             Minecraft events sent to the chat channel
     * @param topic              settings of the channel topic
     * @param mentionSuggestions whether players get the Discord usernames as chat suggestions ({@code @} + Tab)
     */
    public record Chat(boolean enabled, OptionalLong channelId, Webhook webhook, String minecraftFormat,
                       String linkedNameFormat, String avatarUrl, String discordFormat, Events events, Topic topic,
                       boolean mentionSuggestions) {
    }

    /**
     * Webhook used to send Minecraft messages to the chat channel.
     *
     * @param mode how messages are sent
     * @param url  the webhook URL, only present for {@link WebhookMode#URL}
     */
    public record Webhook(WebhookMode mode, Optional<String> url) {
    }

    /**
     * Ways to send Minecraft messages to the chat channel.
     */
    public enum WebhookMode {
        /** The bot creates or reuses a webhook itself. Requires the "Manage Webhooks" permission. */
        AUTO,
        /** The webhook URL from the config is used. */
        URL,
        /** No webhook; the bot sends plain messages itself. */
        NONE
    }

    /**
     * Minecraft events that are sent to the chat channel.
     *
     * @param join        player joined the server
     * @param leave       player left the server
     * @param death       player died
     * @param advancement player made an advancement
     * @param serverStart server started
     * @param serverStop  server stopped
     */
    public record Events(boolean join, boolean leave, boolean death, boolean advancement, boolean serverStart,
                         boolean serverStop) {
    }

    /**
     * Settings of the chat channel topic (description).
     *
     * @param enabled         whether the topic is updated
     * @param intervalMinutes minutes between topic updates, at least 5 because of Discord's rate limit
     * @param format            topic text with placeholders such as {@code {online}}
     * @param maintenanceFormat topic text while the maintenance mode is on
     */
    public record Topic(boolean enabled, int intervalMinutes, String format, String maintenanceFormat) {
    }

    /**
     * Settings of the console channel.
     *
     * @param enabled         whether the console channel is active
     * @param channelId       ID of the console channel, empty if not configured; the channel is inactive then
     * @param batchIntervalMs milliseconds between batched console messages, at least 1000
     * @param permissions     who may run which commands from Discord; nobody may if empty
     */
    public record Console(boolean enabled, OptionalLong channelId, int batchIntervalMs,
                          List<Permission> permissions) {
    }

    /**
     * Permission of a Discord role or user to run commands in the console channel.
     *
     * @param id       Discord role or user ID (IDs are unique across both)
     * @param mode     whether {@code commands} lists the allowed or the denied commands
     * @param commands normalized commands, optionally with subcommands (e.g. {@code gamerule dodaylightcycle}),
     *                 see {@link ConfigLoader#normalizeCommand(String)}
     */
    public record Permission(long id, PermissionMode mode, List<String> commands) {
    }

    /**
     * Meaning of the command list of a {@link Permission}.
     */
    public enum PermissionMode {
        /** Only the listed commands are allowed. */
        WHITELIST,
        /** Everything except the listed commands is allowed. */
        BLACKLIST
    }

    /**
     * Discord roles on the whitelist: while the vanilla whitelist is on, linked members with one of the roles may
     * join in addition to operators and the players on the vanilla whitelist.
     *
     * @param enabled whether whitelisting by roles is used
     * @param roleIds IDs of the whitelisted roles; without roles only the vanilla whitelist counts
     */
    public record Whitelist(boolean enabled, List<Long> roleIds) {

        /**
         * Checks whether whitelisting by roles is in effect while the whitelist is on: switched on and at least one
         * role configured.
         *
         * @return whether the roles count
         */
        public boolean active() {
            return enabled && !roleIds.isEmpty();
        }
    }

    /**
     * Settings of the account linking.
     *
     * @param enabled             whether players can link their accounts and the linked role is managed
     * @param codeValidityMinutes how long a link code stays valid, at least 1 minute
     * @param linkedRole          the Discord role of members with a linked account
     */
    public record Linking(boolean enabled, int codeValidityMinutes, LinkedRole linkedRole) {
    }

    /**
     * The Discord role of members with a linked account.
     *
     * @param mode   which role is used
     * @param roleId the configured role, only present for {@link LinkedRoleMode#ROLE}
     */
    public record LinkedRole(LinkedRoleMode mode, OptionalLong roleId) {
    }

    /**
     * Which linked role is used.
     */
    public enum LinkedRoleMode {
        /** The bot creates and uses its own role "Linked Account". Requires the "Manage Roles" permission. */
        AUTO,
        /** The configured role is used. It must be below the bot's highest role. */
        ROLE,
        /** No linked role. */
        NONE
    }

    /**
     * Moderation settings.
     *
     * @param enabled   whether the moderation commands of the mod are available; the log is always written
     * @param channelId ID of the moderation log channel, empty if moderation actions are only logged to the file
     * @param banSync   bans across Minecraft and Discord
     */
    public record Moderation(boolean enabled, OptionalLong channelId, BanSync banSync) {
    }

    /**
     * Settings of the maintenance mode.
     *
     * @param enabled whether the maintenance commands are available
     */
    public record Maintenance(boolean enabled) {
    }

    /**
     * Settings of the information commands {@code online}, {@code status}, {@code whois} and {@code playtime}.
     *
     * @param enabled whether the information commands are available
     */
    public record Info(boolean enabled) {
    }

    /**
     * Bans across Minecraft and Discord for linked accounts.
     *
     * @param enabled       whether bans are synchronized at all
     * @param discordAction what a Minecraft ban does to the linked Discord account
     */
    public record BanSync(boolean enabled, DiscordAction discordAction) {
    }

    /**
     * What a Minecraft ban does to the linked Discord account.
     */
    public enum DiscordAction {
        /** The Discord account is banned; unbans and expired bans lift the Discord ban. */
        BAN,
        /** The Discord account is only kicked from the Discord server. */
        KICK
    }

    /**
     * Settings of the monitoring channel.
     *
     * @param enabled       whether the monitoring channel is active
     * @param channelId     ID of the monitoring channel, empty if monitoring is off
     * @param lag           lag warnings
     * @param crash         crash notices
     * @param dailyReport   the daily report
     * @param statusMessage the status message that is edited regularly
     */
    public record Monitoring(boolean enabled, OptionalLong channelId, Lag lag, Crash crash, DailyReport dailyReport,
                             StatusMessage statusMessage) {
    }

    /**
     * Lag warnings: sent when the TPS stay below a threshold for some time, followed by an all-clear.
     *
     * @param enabled         whether lag warnings are sent
     * @param tpsThreshold    TPS below this value count as lag, 1 to 20
     * @param durationSeconds how long the TPS must stay below the threshold, at least 5
     * @param cooldownMinutes minimum minutes between two warnings, at least 0
     */
    public record Lag(boolean enabled, double tpsThreshold, int durationSeconds, int cooldownMinutes) {
    }

    /**
     * Crash notices.
     *
     * @param enabled whether a crash notice is sent
     */
    public record Crash(boolean enabled) {
    }

    /**
     * The daily report.
     *
     * @param enabled whether the report is sent
     * @param time    when the report is sent, in the server's time zone
     * @param format  text of the report with placeholders such as {@code {unique_players}}
     */
    public record DailyReport(boolean enabled, LocalTime time, String format) {
    }

    /**
     * The status message in the monitoring channel, edited regularly.
     *
     * @param enabled         whether the status message is shown
     * @param intervalMinutes minutes between updates, at least 1
     * @param format          text with placeholders such as {@code {players}}
     * @param pin             whether the bot pins the status message
     */
    public record StatusMessage(boolean enabled, int intervalMinutes, String format, boolean pin) {
    }

    /**
     * Direct messages to Discord users who subscribed to server events or players.
     *
     * @param enabled whether notifications are sent and the subscribe commands work
     */
    public record Notifications(boolean enabled) {
    }

    /**
     * The texts players and Discord members see, with placeholders. Discord texts may contain emoji shortcodes such
     * as {@code :skull:}, Minecraft texts formatting codes with {@code &}.
     *
     * @param join                   join event; {@code {player}}
     * @param leave                  leave event; {@code {player}}
     * @param death                  death event; {@code {message}} (vanilla death message), {@code {player}}
     * @param advancement            advancement event; {@code {message}} (vanilla wording), {@code {player}},
     *                               {@code {advancement}}
     * @param serverStart            server start message in the chat channel
     * @param serverStop             server stop message in the chat channel
     * @param kickNotLinked          kick message of unlinked players; {@code {code}}, {@code {minutes}}
     * @param kickNotWhitelisted     kick message of linked players without a whitelisted role
     * @param kickNoLongerWhitelisted kick message when a player loses access
     * @param kickMaintenance        kick message during maintenance; {@code {reason}}
     * @param linkDm                 direct message with a link code; {@code {player}}, {@code {code}},
     *                               {@code {minutes}}
     * @param notifyServerStart      notification on server start; {@code {server}}, {@code {Server}}
     * @param notifyServerStop       notification on server stop; {@code {server}}, {@code {Server}}
     * @param notifyMaintenanceOn    notification when the maintenance mode is switched on; {@code {server}},
     *                               {@code {Server}}, {@code {reason}}
     * @param notifyMaintenanceOff   notification when the maintenance mode is switched off; {@code {server}},
     *                               {@code {Server}}
     * @param notifyPlayerOnline     notification when a player joins; {@code {player}}, {@code {server}},
     *                               {@code {Server}}
     * @param lagWarningTitle        title of a lag warning; {@code {tps}}
     * @param lagWarning             text of a lag warning; {@code {threshold}}, {@code {duration}}
     * @param lagOverTitle           title of an all-clear; {@code {tps}}
     * @param lagOver                text of an all-clear; {@code {duration}}
     * @param crashTitle             title of a crash notice
     */
    public record Messages(String join, String leave, String death, String advancement, String serverStart,
                           String serverStop, String kickNotLinked, String kickNotWhitelisted,
                           String kickNoLongerWhitelisted, String kickMaintenance, String linkDm,
                           String notifyServerStart, String notifyServerStop, String notifyMaintenanceOn,
                           String notifyMaintenanceOff, String notifyPlayerOnline, String lagWarningTitle,
                           String lagWarning, String lagOverTitle, String lagOver, String crashTitle) {
    }

    /**
     * Leaderboards from the vanilla statistics.
     *
     * @param enabled    whether the leaderboard commands are available
     * @param size       number of players shown by {@code /mc top}, 1 to {@link ConfigLoader#MAX_LEADERBOARD_SIZE}
     * @param categories the ranked statistics, in the configured order; no leaderboards if empty
     */
    public record Leaderboard(boolean enabled, int size, List<Category> categories) {
    }

    /**
     * One ranked statistic.
     *
     * @param stat        the vanilla statistic as scoreboard criterion, e.g. {@code minecraft.custom:minecraft.deaths};
     *                    only the format is validated here, unknown statistics are reported when the leaderboards
     *                    are set up
     * @param name        the name shown in Discord, empty for the statistic's vanilla name
     * @param lowestFirst whether the lowest value is ranked first (e.g. deaths) instead of the highest
     */
    public record Category(String stat, Optional<String> name, boolean lowestFirst) {
    }
}
