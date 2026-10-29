package de.moritzrohleder.minecraftdiscordlink.config;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Validated, immutable view of the config file. Values that are missing or invalid and have no sensible default
 * are empty, which disables the affected feature.
 */
public record ModConfig(Discord discord, Chat chat, Console console, Whitelist whitelist, Linking linking) {

    public record Discord(Optional<String> token, Status status) {
    }

    public record Status(boolean enabled, ActivityType type, int intervalMinutes, String format) {
    }

    public enum ActivityType {
        PLAYING, WATCHING, LISTENING, COMPETING, CUSTOM
    }

    public record Chat(OptionalLong channelId, Webhook webhook, String minecraftFormat, String linkedNameFormat,
                       String avatarUrl, String discordFormat, Events events, Topic topic) {
    }

    /**
     * @param mode how messages are sent to the chat channel
     * @param url  the webhook URL, only set for {@link WebhookMode#URL}
     */
    public record Webhook(WebhookMode mode, Optional<String> url) {
    }

    public enum WebhookMode {
        /** The bot creates a webhook itself. */
        AUTO,
        /** A webhook URL from the config is used. */
        URL,
        /** No webhook, plain bot messages. */
        NONE
    }

    public record Events(boolean join, boolean leave, boolean death, boolean advancement, boolean serverStart,
                         boolean serverStop) {
    }

    public record Topic(boolean enabled, int intervalMinutes, String format) {
    }

    public record Console(OptionalLong channelId, int batchIntervalMs, List<Permission> permissions) {
    }

    /**
     * @param id       Discord role or user ID
     * @param mode     whether {@code commands} lists the allowed or the denied commands
     * @param commands commands, optionally with subcommands, e.g. {@code gamerule doDaylightCycle}
     */
    public record Permission(long id, PermissionMode mode, List<String> commands) {
    }

    public enum PermissionMode {
        WHITELIST, BLACKLIST
    }

    public record Whitelist(List<Long> roleIds) {

        public boolean enabled() {
            return !roleIds.isEmpty();
        }
    }

    public record Linking(int codeValidityMinutes) {
    }
}
