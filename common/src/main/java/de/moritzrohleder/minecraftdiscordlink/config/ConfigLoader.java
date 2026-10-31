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
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
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

    /** Discord allows at most 25 choices for an option, one per leaderboard category. */
    private static final int MAX_CATEGORIES = 25;
    /** Largest number of players shown by {@code /mc top}, so the answer stays readable. */
    public static final int MAX_LEADERBOARD_SIZE = 25;

    /** Discord IDs ("snowflakes") are 17 to 20 digit numbers. */
    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{17,20}");
    /** Webhook URLs as copied from Discord, including the PTB and Canary clients. */
    private static final Pattern WEBHOOK_URL =
            Pattern.compile("https://(?:ptb\\.|canary\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+");
    /** Vanilla scoreboard criteria of statistics, e.g. {@code minecraft.mined:minecraft.diamond_ore}. */
    private static final Pattern STAT = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.-]+");

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
            "  {motd}            server MOTD",
            "  {players}         names of the online players",
            "  {tps}             ticks per second");

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
        entry("discord.status.maintenance_format", "Maintenance",
                comment("Status text while the maintenance mode is on. Same placeholders."));

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
                "{online}/{max} online | {unique_today} players today | Uptime: {uptime} "
                        + "| Last updated: {last_updated}",
                PLACEHOLDERS);
        entry("chat.topic.maintenance_format",
                "\uD83D\uDD27 Maintenance | {online}/{max} online | Last updated: {last_updated}",
                comment("Topic while the maintenance mode is on. Same placeholders."));

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

        section("whitelist", comment("Whitelist via Discord roles. Works in addition to the vanilla whitelist.",
                "Also changed by the commands /mcadmin rolewhitelist and /discord rolewhitelist."));
        entry("whitelist.enabled", true, comment("Switch the whitelist via Discord roles on or off."));
        entry("whitelist.role_ids", List.of(),
                comment("Members with one of these roles are whitelisted. Empty = feature disabled."));

        section("linking", comment("Linking of Minecraft and Discord accounts."));
        entry("linking.code_validity_minutes", 60, comment("Validity of link codes in minutes. Minimum: 1."));
        entry("linking.linked_role", "auto", comment(
                "Discord role of members with a linked account:",
                "  \"auto\"  the bot creates and uses a role named \"Linked Account\" (needs \"Manage Roles\")",
                "  role ID use this role (it must be below the bot's highest role)",
                "  \"\"      no role"));

        section("moderation", comment("Moderation: every kick, ban and whitelist change is logged to the console and",
                "to minecraftdiscordlink/moderation.log."));
        entry("moderation.channel_id", "", comment(
                "ID of a channel the moderation log is also sent to. \"\" = only the log file."));

        section("moderation.ban_sync", comment("Bans across Minecraft and Discord for linked accounts.",
                "Needs the bot permissions \"Ban Members\", \"Kick Members\" and \"View Audit Log\"."));
        entry("moderation.ban_sync.enabled", false, comment(
                "A Minecraft ban also bans the linked Discord account and vice versa, same for unbans."));
        entry("moderation.ban_sync.discord_action", "ban", comment(
                "What a Minecraft ban does to the linked Discord account: \"ban\" or \"kick\"."));

        section("monitoring", comment("Monitoring channel: lag warnings, crash notices, a daily report and a status",
                "message that is edited regularly."));
        entry("monitoring.channel_id", "", comment("ID of the monitoring channel. \"\" = monitoring off."));

        section("monitoring.lag",
                comment("Warning when the TPS stay below a threshold, all-clear when the lag is over."));
        entry("monitoring.lag.enabled", true, null);
        entry("monitoring.lag.tps_threshold", 15.0, comment("TPS below this value count as lag. 1 to 20."));
        entry("monitoring.lag.duration_seconds", 60,
                comment("Seconds the TPS must stay below the threshold. Minimum: 5."));
        entry("monitoring.lag.cooldown_minutes", 5, comment("Minimum minutes between two warnings."));

        section("monitoring.crash", comment(
                "Notice with the reason and the last console lines when the server crashes.",
                "A killed server process cannot be reported."));
        entry("monitoring.crash.enabled", true, null);
        entry("monitoring.crash.log_lines", 20, comment("Number of console lines in the notice. 1 to 50."));

        section("monitoring.daily_report", comment("Report about the players since the last report."));
        entry("monitoring.daily_report.enabled", true, null);
        entry("monitoring.daily_report.time", "00:00", comment("Time of the report (HH:mm, server time zone)."));
        entry("monitoring.daily_report.format", String.join("\n",
                        "Players: **{unique_players}** ({new_players} new)",
                        "Peak: **{peak_players}** online",
                        "Play time: **{play_time}**",
                        "Top players: {top_players}"),
                comment("Text of the report (Discord Markdown). Placeholders:",
                        "  {date}            day of the report",
                        "  {unique_players}  distinct players",
                        "  {new_players}     players who joined for the first time",
                        "  {peak_players}    most players online at the same time",
                        "  {play_time}       play time of all players together",
                        "  {top_players}     the 3 players with the most play time"));

        section("monitoring.status_message", comment("A message in the monitoring channel that is edited regularly."));
        entry("monitoring.status_message.enabled", true, null);
        entry("monitoring.status_message.interval_minutes", 5, comment("Minutes between updates. Minimum: 1."));
        entry("monitoring.status_message.format", String.join("\n",
                        "**{online}/{max}** players online: {players}",
                        "TPS: {tps} · Uptime: {uptime}",
                        "Version: {version}"),
                comment("Text of the status message (Discord Markdown). Placeholders: as for the topic."));

        section("notifications", comment("Direct messages to Discord users who subscribed with /mc subscribe to",
                "server events (start, stop, maintenance) or to players coming online."));
        entry("notifications.enabled", true, null);
        entry("notifications.player_cooldown_minutes", 10, comment(
                "Minimum minutes between two notices about the same player to the same user."));

        section("leaderboard", comment(
                "Leaderboards from the vanilla statistics: /mc top <category> and /mc stats <player>.",
                "",
                "Every [[leaderboard.categories]] entry is one ranked statistic:",
                "  stat   the statistic as scoreboard criterion, e.g.",
                "         \"minecraft.custom:minecraft.play_time\", \"minecraft.mined:minecraft.diamond_ore\",",
                "         \"minecraft.killed:minecraft.zombie\" or \"minecraft.used:minecraft.diamond_pickaxe\"",
                "  name   name shown in Discord (optional, default: the vanilla name of the statistic)",
                "  order  \"highest\" = the highest value is ranked first, \"lowest\" = the lowest (e.g. deaths)",
                "At most 25 categories. With \"categories = []\" the leaderboard commands are not available."));
        entry("leaderboard.size", 10, comment("Number of players shown by /mc top. Minimum: 1, maximum: "
                + MAX_LEADERBOARD_SIZE + "."));
        entry("leaderboard.categories", List.of(
                category("minecraft.custom:minecraft.play_time", "Play time", "highest"),
                category("minecraft.custom:minecraft.deaths", "Deaths", "lowest")), null);
    }

    /**
     * Creates a default {@code [[leaderboard.categories]]} entry.
     *
     * @param stat  the statistic as scoreboard criterion
     * @param name  the name shown in Discord
     * @param order {@code "highest"} or {@code "lowest"}
     * @return the entry
     */
    private static CommentedConfig category(String stat, String name, String order) {
        CommentedConfig category = newConfig();
        category.set("stat", stat);
        category.set("name", name);
        category.set("order", order);
        return category;
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
     * Changes values in the config file, keeping all other values, the order and the comments. The file is read
     * again first, so changes made to it since the last load are kept (but not applied). The file is not touched if
     * it cannot be read.
     *
     * @param file   the config file
     * @param values the new values, by dotted path
     * @return whether the file was written
     */
    public static boolean update(Path file, Map<String, Object> values) {
        CommentedConfig config = newConfig();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                TomlFormat.instance().createParser().parse(reader, config, ParsingMode.REPLACE);
            } catch (IOException | ParsingException e) {
                Constants.LOG.error("Could not read config file {}, so it was not changed: {}", file, e.getMessage());
                return false;
            }
        }
        addMissing(config);
        values.forEach(config::set);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                TomlFormat.instance().createWriter().write(config, writer);
            }
            return true;
        } catch (IOException e) {
            Constants.LOG.error("Could not write config file {}: {}", file, e.getMessage());
            return false;
        }
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
                                    string("discord.status.format"),
                                    string("discord.status.maintenance_format"))),
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
                                    string("chat.topic.format"),
                                    string("chat.topic.maintenance_format"))),
                    new ModConfig.Console(
                            channel("console.channel_id", "console channel"),
                            integer("console.batch_interval_ms", 1000),
                            permissions()),
                    new ModConfig.Whitelist(bool("whitelist.enabled"), roleIds()),
                    new ModConfig.Linking(integer("linking.code_validity_minutes", 1), linkedRole()),
                    new ModConfig.Moderation(
                            optionalChannel("moderation.channel_id", "moderation channel"),
                            new ModConfig.BanSync(bool("moderation.ban_sync.enabled"), discordAction())),
                    new ModConfig.Monitoring(
                            optionalChannel("monitoring.channel_id", "monitoring channel"),
                            new ModConfig.Lag(bool("monitoring.lag.enabled"),
                                    decimal("monitoring.lag.tps_threshold", 1, 20),
                                    integer("monitoring.lag.duration_seconds", 5),
                                    integer("monitoring.lag.cooldown_minutes", 0)),
                            new ModConfig.Crash(bool("monitoring.crash.enabled"),
                                    integer("monitoring.crash.log_lines", 1, 50)),
                            new ModConfig.DailyReport(bool("monitoring.daily_report.enabled"),
                                    time("monitoring.daily_report.time"),
                                    string("monitoring.daily_report.format")),
                            new ModConfig.StatusMessage(bool("monitoring.status_message.enabled"),
                                    integer("monitoring.status_message.interval_minutes", 1),
                                    string("monitoring.status_message.format"))),
                    new ModConfig.Notifications(bool("notifications.enabled"),
                            integer("notifications.player_cooldown_minutes", 0)),
                    new ModConfig.Leaderboard(integer("leaderboard.size", 1, MAX_LEADERBOARD_SIZE), categories()));
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
         * Reads an optional channel ID: an empty value means no channel, an invalid one is logged as an error.
         *
         * @param path    path of the entry
         * @param feature name of the feature for the error message
         * @return the channel ID, empty if not configured or invalid
         */
        private OptionalLong optionalChannel(String path, String feature) {
            Object value = config.get(path);
            if (value instanceof String text && text.isBlank()) {
                return OptionalLong.empty();
            }
            return channel(path, feature);
        }

        /**
         * Reads what a Minecraft ban does to the linked Discord account.
         *
         * @return the configured action, or the default if invalid
         */
        private ModConfig.DiscordAction discordAction() {
            String path = "moderation.ban_sync.discord_action";
            if (config.get(path) instanceof String action) {
                try {
                    return ModConfig.DiscordAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // Falls through to the default
                }
            }
            return invalid(path, ModConfig.DiscordAction.BAN);
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
         * Reads the {@code [[leaderboard.categories]]} entries. Invalid entries, entries repeating a statistic and
         * entries beyond the 25th (Discord's limit of choices) are logged and ignored.
         *
         * @return the valid categories
         */
        private List<ModConfig.Category> categories() {
            String path = "leaderboard.categories";
            if (!(config.get(path) instanceof List<?> entries)) {
                Constants.LOG.error("Invalid value for '{}' in the config. The leaderboards stay inactive.", path);
                return List.of();
            }
            List<ModConfig.Category> categories = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                String name = path + " entry " + (i + 1);
                ModConfig.Category category = entries.get(i) instanceof UnmodifiableConfig entry
                        ? category(entry) : null;
                if (category == null) {
                    Constants.LOG.error("Invalid {} in the config (needs a valid 'stat' and 'order' = \"highest\" "
                            + "or \"lowest\"). The entry is ignored.", name);
                } else if (categories.stream().anyMatch(other -> other.stat().equals(category.stat()))) {
                    Constants.LOG.error("The statistic '{}' of {} is already a category. The entry is ignored.",
                            category.stat(), name);
                } else if (categories.size() == MAX_CATEGORIES) {
                    Constants.LOG.error("More than {} entries in '{}' in the config. {} is ignored.",
                            MAX_CATEGORIES, path, name);
                } else {
                    categories.add(category);
                }
            }
            return List.copyOf(categories);
        }

        /**
         * Parses one {@code [[leaderboard.categories]]} entry.
         *
         * @param entry the entry
         * @return the category, or {@code null} if invalid
         */
        private static ModConfig.Category category(UnmodifiableConfig entry) {
            if (!(entry.get("stat") instanceof String stat) || !STAT.matcher(stat.strip()).matches()) {
                return null;
            }
            Object name = entry.get("name");
            if (name != null && !(name instanceof String)) {
                return null;
            }
            Optional<String> displayName = Optional.ofNullable((String) name).map(String::strip)
                    .filter(text -> !text.isEmpty());
            Object order = entry.get("order");
            if (order == null || "highest".equals(order)) {
                return new ModConfig.Category(stat.strip(), displayName, false);
            }
            if ("lowest".equals(order)) {
                return new ModConfig.Category(stat.strip(), displayName, true);
            }
            return null;
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
            return integer(path, min, Integer.MAX_VALUE);
        }

        /**
         * Reads an integer value with a lower and an upper bound.
         *
         * @param path path of the entry
         * @param min  smallest valid value; smaller values are invalid
         * @param max  largest valid value; larger values are invalid
         * @return the integer value, or the default if invalid
         */
        private int integer(String path, int min, int max) {
            if (config.get(path) instanceof Number number) {
                long value = number.longValue();
                if (value >= min && value <= max) {
                    return (int) value;
                }
            }
            return invalid(path, (Integer) defaultValue(path));
        }

        /**
         * Reads a decimal value within bounds.
         *
         * @param path path of the entry
         * @param min  smallest valid value
         * @param max  largest valid value
         * @return the value, or the default if invalid
         */
        private double decimal(String path, double min, double max) {
            if (config.get(path) instanceof Number number) {
                double value = number.doubleValue();
                if (value >= min && value <= max) {
                    return value;
                }
            }
            return invalid(path, ((Number) defaultValue(path)).doubleValue());
        }

        /**
         * Reads a time of day ({@code HH:mm}).
         *
         * @param path path of the entry
         * @return the time, or the default if invalid
         */
        private LocalTime time(String path) {
            if (config.get(path) instanceof String text) {
                try {
                    return LocalTime.parse(text.strip());
                } catch (DateTimeParseException ignored) {
                    // Falls through to the default
                }
            }
            return invalid(path, LocalTime.parse((String) defaultValue(path)));
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
