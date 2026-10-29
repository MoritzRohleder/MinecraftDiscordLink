package de.moritzrohleder.minecraftdiscordlink.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.ActivityType;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.PermissionMode;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.WebhookMode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Reads, completes and validates the TOML config file.
 * <p>
 * The structure of the file is defined by the sections and entries registered in the static initializer. Their
 * order is the order in the generated file, their comments document the options for server admins.
 * <p>
 * Loading works in three steps:
 * <ol>
 *     <li>The file is parsed, keeping the order of its keys and its comments.</li>
 *     <li>Missing sections and keys are added with their defaults and comments. Only then the file is written, so
 *     an up-to-date file is never touched. A file that cannot be parsed is never overwritten.</li>
 *     <li>All values are validated and converted into a {@link ModConfig}. Invalid values are logged as errors and
 *     replaced by their default. Values without a sensible default stay empty, which disables the affected
 *     feature.</li>
 * </ol>
 */
public final class ConfigLoader {

    /** Name of the config file inside the config directory. */
    public static final String FILE_NAME = Constants.MOD_ID + ".toml";

    /** Discord IDs ("snowflakes") are 17 to 20 digit numbers. */
    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{17,20}");
    /** Webhook URLs as copied from Discord, including the PTB and Canary clients. */
    private static final Pattern WEBHOOK_URL =
            Pattern.compile("https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+");

    /** Comment documenting the placeholders of the status and topic formats. */
    private static final String PLACEHOLDERS = comment(
            "Placeholders:",
            "  {online}          players currently online",
            "  {max}             max players",
            "  {unique_session}  distinct players since server start",
            "  {unique_today}    distinct players today",
            "  {unique_total}    distinct players ever",
            "  {uptime}          time since server start",
            "  {last_updated}    time of this update (HH:mm)",
            "  {version}         Minecraft version",
            "  {motd}            server MOTD");

    /** Comments of the config sections (tables), by path. */
    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();
    /** Config entries with defaults and comments, by path. */
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        section("discord", comment("Discord bot settings. Changes in this section require a server restart,",
                "except for the status."));
        entry("discord.token", "", comment("Bot token from the Discord Developer Portal. Keep it secret!"));

        section("discord.status", comment("Activity shown for the bot in Discord."));
        entry("discord.status.enabled", true, comment("Show a status for the bot."));
        entry("discord.status.type", "playing",
                comment("Activity type: \"playing\", \"watching\", \"listening\", \"competing\" or \"custom\"."));
        entry("discord.status.interval_minutes", 5, comment("Minutes between status updates. Minimum: 1."));
        entry("discord.status.format", "{online}/{max} online", PLACEHOLDERS);

        section("chat", comment("Bridge between the Minecraft chat and a Discord channel."));
        entry("chat.channel_id", "", comment("ID of the chat channel. Changes require a server restart."));
        entry("chat.webhook", "auto", comment(
                "How Minecraft messages are sent to Discord:",
                "  \"auto\"  the bot creates a webhook itself (needs the \"Manage Webhooks\" permission)",
                "  URL     use this webhook",
                "  \"\"      no webhook, plain bot messages"));
        entry("chat.minecraft_format", "&9[Discord]&r {name}: {message}", comment(
                "Discord messages in Minecraft. Placeholders: {name}, {message}",
                "Formatting codes: &0-&9 and &a-&f for colors, &l bold, &o italic, &n underlined,",
                "&m strikethrough, &k obfuscated, &r reset. Text without a code is white."));
        entry("chat.linked_name_format", "{mc} ({discord})",
                comment("Name of linked players. Placeholders: {mc}, {discord}"));
        entry("chat.avatar_url", "https://vzge.me/face/256/{uuid}",
                comment("Player head used as webhook avatar. Placeholders: {uuid}, {name}"));
        entry("chat.discord_format", "**{player}**: {message}",
                comment("Minecraft messages in Discord without webhook. Placeholders: {player}, {message}"));

        section("chat.events", comment("Minecraft events sent to the chat channel."));
        entry("chat.events.join", true, null);
        entry("chat.events.leave", true, null);
        entry("chat.events.death", true, null);
        entry("chat.events.advancement", true, null);
        entry("chat.events.server_start", true, null);
        entry("chat.events.server_stop", true, null);

        section("chat.topic", comment("Description (topic) of the chat channel."));
        entry("chat.topic.enabled", true, comment("Update the channel topic."));
        entry("chat.topic.interval_minutes", 10, comment(
                "Minutes between topic updates. Minimum: 5 (Discord allows about 2 changes per 10 minutes)."));
        entry("chat.topic.format",
                "{online}/{max} online | {unique_today} players today | Uptime: {uptime} | Last updated: {last_updated}",
                PLACEHOLDERS);

        section("console", comment(
                "Live server console in a Discord channel.",
                "",
                "Users allowed to run commands are configured with [[console.permissions]] entries,",
                "one per Discord role or user (IDs are unique across both):",
                "  mode = \"whitelist\": only the listed commands are allowed",
                "  mode = \"blacklist\": everything except the listed commands is allowed",
                "An entry matches a command and all its subcommands:",
                "  \"gamerule\"                  every gamerule command",
                "  \"gamerule doDaylightCycle\"  only this subcommand",
                "If several entries match a user, everything allowed by any of them is allowed,",
                "but an explicitly denied command is always denied.",
                "Without entries nobody can run commands from Discord.",
                "",
                "Example:",
                "  [[console.permissions]]",
                "  id = \"123456789012345678\"",
                "  mode = \"blacklist\"",
                "  commands = [\"stop\", \"op\", \"deop\"]"));
        entry("console.channel_id", "", comment("ID of the console channel. Changes require a server restart."));
        entry("console.batch_interval_ms", 2000,
                comment("Milliseconds between batched console messages. Minimum: 1000."));

        section("whitelist", comment("Whitelist via Discord roles. Works in addition to the vanilla whitelist."));
        entry("whitelist.role_ids", List.of(),
                comment("Members with one of these roles are whitelisted. Empty = feature disabled."));

        section("linking", comment("Linking of Minecraft and Discord accounts."));
        entry("linking.code_validity_minutes", 60, comment("Validity of link codes in minutes. Minimum: 1."));
        entry("linking.linked_role", "auto", comment(
                "Discord role of members with a linked account:",
                "  \"auto\"  the bot creates and uses a role named \"Linked Account\" (needs \"Manage Roles\")",
                "  role ID use this role (it must be below the bot's highest role)",
                "  \"\"      no role"));
    }

    private ConfigLoader() {
    }

    /**
     * Loads the config file.
     * <p>
     * A missing file is created with all defaults. Missing keys are added to an existing file, keeping its values and
     * comments. Problems are logged as errors; this method never throws.
     *
     * @param file the config file
     * @return the validated config
     */
    public static ModConfig load(Path file) {
        CommentedConfig config = newConfig();
        boolean readable = true;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                TomlFormat.instance().createParser().parse(reader, config, ParsingMode.REPLACE);
            } catch (IOException | ParsingException e) {
                // Never overwrite a file the user has to fix
                Constants.LOG.error("Could not read config file {}: {}", file, e.getMessage());
                config = newConfig();
                readable = false;
            }
        }

        if (addMissing(config) && readable) {
            write(file, config);
        }
        return new Values(config).read();
    }

    /**
     * Adds all missing sections and entries with their defaults and comments.
     *
     * @param config the parsed config, modified in place
     * @return whether anything was added
     */
    private static boolean addMissing(CommentedConfig config) {
        boolean changed = false;
        for (Map.Entry<String, String> section : SECTIONS.entrySet()) {
            if (!config.contains(section.getKey())) {
                config.set(section.getKey(), config.createSubConfig());
                config.setComment(section.getKey(), section.getValue());
                changed = true;
            }
        }
        for (Entry entry : ENTRIES.values()) {
            if (!config.contains(entry.path())) {
                config.set(entry.path(), entry.defaultValue());
                if (entry.comment() != null) {
                    config.setComment(entry.path(), entry.comment());
                }
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Writes the config to the file, creating missing directories. Errors are logged.
     *
     * @param file   the config file
     * @param config the config to write
     */
    private static void write(Path file, CommentedConfig config) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                TomlFormat.instance().createWriter().write(config, writer);
            }
        } catch (IOException e) {
            Constants.LOG.error("Could not write config file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Creates an empty config that keeps the insertion order of its keys, so the file keeps its order.
     *
     * @return a new, empty config
     */
    private static CommentedConfig newConfig() {
        return TomlFormat.newConfig(LinkedHashMap::new);
    }

    /**
     * Registers a config section (table).
     *
     * @param path    dotted path of the section, e.g. {@code chat.topic}
     * @param comment comment written above the section, see {@link #comment(String...)}
     */
    private static void section(String path, String comment) {
        SECTIONS.put(path, comment);
    }

    /**
     * Registers a config entry.
     *
     * @param path         dotted path of the entry, e.g. {@code chat.topic.enabled}
     * @param defaultValue value written for a missing entry and used for an invalid one
     * @param comment      comment written above the entry, or {@code null} for none
     */
    private static void entry(String path, Object defaultValue, String comment) {
        ENTRIES.put(path, new Entry(path, defaultValue, comment));
    }

    /**
     * Joins lines to a TOML comment. Every line gets a leading space, as the writer only adds {@code #}.
     *
     * @param lines the lines of the comment
     * @return the comment text
     */
    private static String comment(String... lines) {
        return " " + String.join("\n ", lines);
    }

    /**
     * A registered config entry.
     *
     * @param path         dotted path of the entry
     * @param defaultValue value used for missing and invalid entries
     * @param comment      comment written above the entry, may be {@code null}
     */
    private record Entry(String path, Object defaultValue, String comment) {
    }

    /**
     * Converts the raw config values into a {@link ModConfig}, logging every invalid value.
     *
     * @param config the parsed and completed config
     */
    private record Values(UnmodifiableConfig config) {

        /**
         * Validates all values.
         *
         * @return the validated config
         */
        ModConfig read() {
            return new ModConfig(
                    new ModConfig.Discord(
                            token(),
                            new ModConfig.Status(
                                    bool("discord.status.enabled"),
                                    activityType(),
                                    integer("discord.status.interval_minutes", 1),
                                    string("discord.status.format"))),
                    new ModConfig.Chat(
                            channel("chat.channel_id", "chat bridge"),
                            webhook(),
                            string("chat.minecraft_format"),
                            string("chat.linked_name_format"),
                            string("chat.avatar_url"),
                            string("chat.discord_format"),
                            new ModConfig.Events(
                                    bool("chat.events.join"),
                                    bool("chat.events.leave"),
                                    bool("chat.events.death"),
                                    bool("chat.events.advancement"),
                                    bool("chat.events.server_start"),
                                    bool("chat.events.server_stop")),
                            new ModConfig.Topic(
                                    bool("chat.topic.enabled"),
                                    integer("chat.topic.interval_minutes", 5),
                                    string("chat.topic.format"))),
                    new ModConfig.Console(
                            channel("console.channel_id", "console channel"),
                            integer("console.batch_interval_ms", 1000),
                            permissions()),
                    new ModConfig.Whitelist(roleIds()),
                    new ModConfig.Linking(integer("linking.code_validity_minutes", 1), linkedRole()));
        }

        /**
         * Reads the bot token. A missing token is logged as an error, as the mod stays inactive.
         *
         * @return the bot token, empty if none is configured
         */
        private Optional<String> token() {
            Object value = config.get("discord.token");
            if (value instanceof String token && !token.isBlank()) {
                return Optional.of(token.trim());
            }
            Constants.LOG.error("No Discord bot token configured ('discord.token'). The mod stays inactive.");
            return Optional.empty();
        }

        /**
         * Reads the activity type of the bot status.
         *
         * @return the configured activity type, or the default if invalid
         */
        private ActivityType activityType() {
            String path = "discord.status.type";
            if (config.get(path) instanceof String type) {
                try {
                    return ActivityType.valueOf(type.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // Falls through to the default
                }
            }
            return invalid(path, ActivityType.valueOf(((String) defaultValue(path)).toUpperCase(Locale.ROOT)));
        }

        /**
         * Reads the webhook setting: {@code "auto"}, a Discord webhook URL or an empty string.
         *
         * @return the webhook setting, {@link WebhookMode#AUTO} if invalid
         */
        private ModConfig.Webhook webhook() {
            String path = "chat.webhook";
            if (config.get(path) instanceof String webhook) {
                String value = webhook.trim();
                if (value.isEmpty()) {
                    return new ModConfig.Webhook(WebhookMode.NONE, Optional.empty());
                }
                if (value.equalsIgnoreCase("auto")) {
                    return new ModConfig.Webhook(WebhookMode.AUTO, Optional.empty());
                }
                if (WEBHOOK_URL.matcher(value).matches()) {
                    return new ModConfig.Webhook(WebhookMode.URL, Optional.of(value));
                }
            }
            return invalid(path, new ModConfig.Webhook(WebhookMode.AUTO, Optional.empty()));
        }

        /**
         * Reads the linked role setting: {@code "auto"}, a role ID or an empty string. An invalid value disables the
         * role, as falling back to {@code "auto"} would create a role nobody asked for.
         *
         * @return the linked role setting
         */
        private ModConfig.LinkedRole linkedRole() {
            String path = "linking.linked_role";
            Object value = config.get(path);
            if (value instanceof String text) {
                if (text.isBlank()) {
                    return new ModConfig.LinkedRole(ModConfig.LinkedRoleMode.NONE, OptionalLong.empty());
                }
                if (text.strip().equalsIgnoreCase("auto")) {
                    return new ModConfig.LinkedRole(ModConfig.LinkedRoleMode.AUTO, OptionalLong.empty());
                }
            }
            OptionalLong id = snowflake(value);
            if (id.isPresent()) {
                return new ModConfig.LinkedRole(ModConfig.LinkedRoleMode.ROLE, id);
            }
            Constants.LOG.error("Invalid value '{}' for '{}' in the config. The linked role stays inactive.",
                    value, path);
            return new ModConfig.LinkedRole(ModConfig.LinkedRoleMode.NONE, OptionalLong.empty());
        }

        /**
         * Reads a channel ID. A missing or invalid ID is logged as an error, as the feature stays inactive.
         *
         * @param path    path of the entry
         * @param feature name of the feature for the error message
         * @return the channel ID, empty if missing or invalid
         */
        private OptionalLong channel(String path, String feature) {
            OptionalLong id = snowflake(config.get(path));
            if (id.isEmpty()) {
                Constants.LOG.error("Missing or invalid value for '{}' in the config. The {} stays inactive.",
                        path, feature);
            }
            return id;
        }

        /**
         * Reads the {@code [[console.permissions]]} entries. Invalid entries are logged and ignored.
         *
         * @return the valid permission entries
         */
        private List<ModConfig.Permission> permissions() {
            List<ModConfig.Permission> permissions = new ArrayList<>();
            if (!(config.get("console.permissions") instanceof List<?> entries)) {
                if (config.contains("console.permissions")) {
                    Constants.LOG.error("Invalid value for 'console.permissions' in the config. "
                            + "Nobody can run commands from Discord.");
                }
                return permissions;
            }

            for (int i = 0; i < entries.size(); i++) {
                String name = "console.permissions entry " + (i + 1);
                if (!(entries.get(i) instanceof UnmodifiableConfig entry)) {
                    Constants.LOG.error("Invalid {} in the config. The entry is ignored.", name);
                    continue;
                }
                OptionalLong id = snowflake(entry.get("id"));
                PermissionMode mode = permissionMode(entry.get("mode"));
                List<String> commands = commands(entry.get("commands"));
                if (id.isEmpty() || mode == null || commands == null) {
                    Constants.LOG.error("Invalid {} in the config (needs a valid 'id', 'mode' and 'commands'). "
                            + "The entry is ignored.", name);
                    continue;
                }
                permissions.add(new ModConfig.Permission(id.getAsLong(), mode, commands));
            }
            return List.copyOf(permissions);
        }

        /**
         * Parses the {@code mode} of a permission entry.
         *
         * @param value raw value of a {@code mode} entry
         * @return the permission mode, or {@code null} if invalid
         */
        private static PermissionMode permissionMode(Object value) {
            if (value instanceof String mode) {
                try {
                    return PermissionMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // Invalid mode
                }
            }
            return null;
        }

        /**
         * Parses and normalizes the {@code commands} of a permission entry.
         *
         * @param value raw value of a {@code commands} entry
         * @return the normalized commands, or {@code null} if the list or one of its commands is invalid
         */
        private static List<String> commands(Object value) {
            if (!(value instanceof List<?> list)) {
                return null;
            }
            List<String> commands = new ArrayList<>();
            for (Object element : list) {
                if (!(element instanceof String command)) {
                    return null;
                }
                String normalized = normalizeCommand(command);
                if (normalized.isEmpty()) {
                    return null;
                }
                commands.add(normalized);
            }
            return List.copyOf(commands);
        }

        /**
         * Reads the whitelisted role IDs. Invalid IDs are logged and ignored.
         *
         * @return the valid role IDs
         */
        private List<Long> roleIds() {
            String path = "whitelist.role_ids";
            if (!(config.get(path) instanceof List<?> list)) {
                return invalid(path, List.of());
            }
            List<Long> roleIds = new ArrayList<>();
            for (Object element : list) {
                OptionalLong id = snowflake(element);
                if (id.isPresent()) {
                    roleIds.add(id.getAsLong());
                } else {
                    Constants.LOG.error("Invalid role ID '{}' in '{}' in the config. The ID is ignored.",
                            element, path);
                }
            }
            return List.copyOf(roleIds);
        }

        /**
         * Reads a boolean value.
         *
         * @param path path of the entry
         * @return the boolean value, or the default if invalid
         */
        private boolean bool(String path) {
            if (config.get(path) instanceof Boolean value) {
                return value;
            }
            return invalid(path, (Boolean) defaultValue(path));
        }

        /**
         * Reads an integer value with a lower bound.
         *
         * @param path path of the entry
         * @param min  smallest valid value; smaller values are invalid
         * @return the integer value, or the default if invalid
         */
        private int integer(String path, int min) {
            if (config.get(path) instanceof Number number) {
                long value = number.longValue();
                if (value >= min && value <= Integer.MAX_VALUE) {
                    return (int) value;
                }
            }
            return invalid(path, (Integer) defaultValue(path));
        }

        /**
         * Reads a string value.
         *
         * @param path path of the entry
         * @return the string value, or the default if invalid
         */
        private String string(String path) {
            if (config.get(path) instanceof String value) {
                return value;
            }
            return invalid(path, (String) defaultValue(path));
        }

        /**
         * Parses a Discord ID, given as string or number.
         *
         * @param value the raw value
         * @return the ID, empty if the value is no valid Discord ID
         */
        private static OptionalLong snowflake(Object value) {
            String id = value instanceof Number number ? String.valueOf(number.longValue())
                    : value instanceof String string ? string.trim() : null;
            if (id == null || !SNOWFLAKE.matcher(id).matches()) {
                return OptionalLong.empty();
            }
            try {
                return OptionalLong.of(Long.parseLong(id));
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
        }

        /**
         * Logs an invalid value and returns the default to use instead.
         *
         * @param path         path of the entry
         * @param defaultValue the value used instead
         * @param <T>          type of the value
         * @return {@code defaultValue}
         */
        private <T> T invalid(String path, T defaultValue) {
            Constants.LOG.error("Invalid value '{}' for '{}' in the config. Using the default '{}'.",
                    config.get(path), path, defaultValue);
            return defaultValue;
        }

        /**
         * Looks up the default value of an entry.
         *
         * @param path path of a registered entry
         * @return the default value of the entry
         */
        private static Object defaultValue(String path) {
            return ENTRIES.get(path).defaultValue();
        }
    }

    /**
     * Normalizes a command for permission checks: no leading slash, single spaces, lower case.
     *
     * @param command the command as written by a user or in the config
     * @return the normalized command, empty if {@code command} is blank
     */
    public static String normalizeCommand(String command) {
        String normalized = command.trim().replaceAll("\\s+", " ");
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
