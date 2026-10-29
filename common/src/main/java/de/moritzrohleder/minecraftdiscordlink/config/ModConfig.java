package de.moritzrohleder.minecraftdiscordlink.config;

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
 * @param discord   Discord bot settings ({@code [discord]})
 * @param chat      chat bridge settings ({@code [chat]})
 * @param console   console channel settings ({@code [console]})
 * @param whitelist whitelist via Discord roles ({@code [whitelist]})
 * @param linking   account linking settings ({@code [linking]})
 */
public record ModConfig(Discord discord, Chat chat, Console console, Whitelist whitelist, Linking linking) {

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
     * @param format          status text with placeholders such as {@code {online}}
     */
    public record Status(boolean enabled, ActivityType type, int intervalMinutes, String format) {
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
     * @param channelId        ID of the chat channel, empty if not configured; the bridge is inactive then
     * @param webhook          how Minecraft messages are sent to Discord
     * @param minecraftFormat  format of Discord messages in Minecraft, placeholders {@code {name}}, {@code {message}}
     * @param linkedNameFormat name of linked players, placeholders {@code {mc}}, {@code {discord}}
     * @param avatarUrl        URL of the webhook avatar, placeholders {@code {uuid}}, {@code {name}}
     * @param discordFormat    format of Minecraft messages without webhook, placeholders {@code {player}},
     *                         {@code {message}}
     * @param events           Minecraft events sent to the chat channel
     * @param topic            settings of the channel topic
     */
    public record Chat(OptionalLong channelId, Webhook webhook, String minecraftFormat, String linkedNameFormat,
                       String avatarUrl, String discordFormat, Events events, Topic topic) {
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
     * @param format          topic text with placeholders such as {@code {online}}
     */
    public record Topic(boolean enabled, int intervalMinutes, String format) {
    }

    /**
     * Settings of the console channel.
     *
     * @param channelId       ID of the console channel, empty if not configured; the channel is inactive then
     * @param batchIntervalMs milliseconds between batched console messages, at least 1000
     * @param permissions     who may run which commands from Discord; nobody may if empty
     */
    public record Console(OptionalLong channelId, int batchIntervalMs, List<Permission> permissions) {
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
     * Whitelist via Discord roles, in addition to the vanilla whitelist.
     *
     * @param roleIds IDs of the roles whose members are whitelisted; the feature is disabled if empty
     */
    public record Whitelist(List<Long> roleIds) {

        /**
         * Checks whether the whitelist via Discord roles is active, i.e. at least one role is configured.
         *
         * @return whether the whitelist via Discord roles is active
         */
        public boolean enabled() {
            return !roleIds.isEmpty();
        }
    }

    /**
     * Settings of the account linking.
     *
     * @param codeValidityMinutes how long a link code stays valid, at least 1 minute
     */
    public record Linking(int codeValidityMinutes) {
    }
}
