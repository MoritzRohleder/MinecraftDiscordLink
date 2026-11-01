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

    /** Lines documenting the placeholders of the bot status, the topic and the status message. */
    private static final String[] PLACEHOLDER_LINES = {
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
            "  {tps}             ticks per second"};

    /** Comments of the config sections (tables), by path. */
    private static final Map<String, String> SECTIONS = new LinkedHashMap<>();
    /** Config entries with defaults and comments, by path. */
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        // ---- This server and the bot (changes need a restart)

        section("server", comment("This Minecraft server. Changes in this section require a server restart."));
        entry("server.name", "", comment(
                "Name of this server when several Minecraft servers share one Discord server (same bot token).",
                "Shown in answers, notifications and the monitoring and moderation channels; the linked role",
                "\"auto\" is then named \"Linked Account (<name>)\". \"\" = this is the only server."));
        entry("server.primary", true, comment(
                "The primary server answers slash commands without a \"server\" option. Exactly one server",
                "sharing the Discord server should be primary: with none, such commands stay unanswered;",
                "with two, both run them."));

        section("discord", comment("Discord bot. The token requires a server restart."));
        entry("discord.token", "", comment("Bot token from the Discord Developer Portal. Keep it secret!"));

        // ---- All switches

        section("features", comment("Switch features on or off. Their settings follow further below."));

        section("features.chat", comment("Bridge between the Minecraft chat and the chat channel."));
        entry("features.chat.enabled", true, comment("The chat bridge, its events and the topic."));
        entry("features.chat.join", true, comment("Events sent to the chat channel."));
        entry("features.chat.leave", true, null);
        entry("features.chat.death", true, null);
        entry("features.chat.advancement", true, null);
        entry("features.chat.server_start", true, null);
        entry("features.chat.server_stop", true, null);
        entry("features.chat.topic", true, comment("Update the topic of the chat channel."));
        entry("features.chat.mention_suggestions", true,
                comment("Players get the Discord usernames as chat suggestions (@ + Tab)."));

        section("features.bot_status", comment("Activity shown for the bot in Discord."));
        entry("features.bot_status.enabled", true, null);

        section("features.console", comment("Live server console in the console channel."));
        entry("features.console.enabled", true, null);

        section("features.linking", comment("Linking of Minecraft and Discord accounts and the linked role.",
                "Switched off, the linked role is removed; the links are kept."));
        entry("features.linking.enabled", true, null);

        section("features.whitelist", comment(
                "Discord roles on the whitelist: while the whitelist is on (/whitelist on), linked members with",
                "a whitelisted role may join in addition to the vanilla whitelist."));
        entry("features.whitelist.roles", true, null);

        section("features.moderation", comment("Moderation commands. The moderation log is always written."));
        entry("features.moderation.enabled", true, null);
        entry("features.moderation.ban_sync", false, comment(
                "Bans across Minecraft and linked Discord accounts, in both directions.",
                "Needs the bot permissions \"Ban Members\", \"Kick Members\" and \"View Audit Log\"."));
        entry("features.moderation.mute_sync", false, comment(
                "Mutes as Discord timeouts for linked accounts and vice versa.",
                "Needs the bot permissions \"Timeout Members\" and \"View Audit Log\"."));

        section("features.maintenance",
                comment("Maintenance mode: only operators and linked Discord admins may join."));
        entry("features.maintenance.enabled", true, null);

        section("features.info", comment(
                "Information commands: /mc online, /mc status, /mc whois, /mc playtime and /discord whois."));
        entry("features.info.enabled", true, null);

        section("features.leaderboard", comment("Leaderboards: /mc top and /mc stats."));
        entry("features.leaderboard.enabled", true, null);

        section("features.notifications", comment(
                "Direct messages to Discord users who subscribed with /mc subscribe to server events or players."));
        entry("features.notifications.enabled", true, null);

        section("features.monitoring", comment("Monitoring channel."));
        entry("features.monitoring.enabled", true, null);
        entry("features.monitoring.lag", true,
                comment("Warning when the TPS stay below a threshold, all-clear when the lag is over."));
        entry("features.monitoring.crash", true, comment("Notice when the server crashes."));
        entry("features.monitoring.daily_report", true, comment("Report about the players since the last report."));
        entry("features.monitoring.status_message", true,
                comment("A message that is edited regularly with the status of the server."));
        entry("features.monitoring.pin_status_message", true,
                comment("Pin the status message (needs the \"Pin Messages\" permission)."));

        // ---- All texts

        section("texts", comment(
                "Texts players and Discord members see. Discord texts: Discord Markdown and emojis as :name:.",
                "Minecraft texts: formatting codes with & (&0-&9 and &a-&f for colors, &l bold, &o italic,",
                "&n underlined, &m strikethrough, &k obfuscated, &r reset; text without a code is white)."));

        section("texts.chat", comment("Chat bridge and events."));
        entry("texts.chat.minecraft_format", "&9[Discord]&r {name}: {message}",
                comment("Discord messages in Minecraft (Minecraft text). Placeholders: {name}, {message}"));
        entry("texts.chat.discord_format", "**{player}**: {message}",
                comment("Minecraft messages in Discord without webhook. Placeholders: {player}, {message}"));
        entry("texts.chat.linked_name_format", "{mc} ({discord})",
                comment("Name of linked players. Placeholders: {mc}, {discord}"));
        entry("texts.chat.join", ":inbox_tray: **{player}** joined the game", comment("Placeholders: {player}"));
        entry("texts.chat.leave", ":outbox_tray: **{player}** left the game", comment("Placeholders: {player}"));
        entry("texts.chat.death", ":skull: {message}",
                comment("Placeholders: {message} (vanilla death message, name in bold), {player}"));
        entry("texts.chat.advancement", ":trophy: {message}",
                comment("Placeholders: {message} (vanilla wording), {player}, {advancement}"));
        entry("texts.chat.server_start", ":white_check_mark: Server started", null);
        entry("texts.chat.server_stop", ":octagonal_sign: Server stopped", null);

        section("texts.bot_status", comment("Activity text of the bot.", PLACEHOLDER_LINES));
        entry("texts.bot_status.format", "{online}/{max} online", null);
        entry("texts.bot_status.maintenance_format", "Maintenance",
                comment("While the maintenance mode is on."));

        section("texts.topic", comment("Topic of the chat channel.", PLACEHOLDER_LINES));
        entry("texts.topic.format",
                "{online}/{max} online | {unique_today} players today | Uptime: {uptime} "
                        + "| Last updated: {last_updated}",
                null);
        entry("texts.topic.maintenance_format",
                "🔧 Maintenance | {online}/{max} online | Last updated: {last_updated}",
                comment("While the maintenance mode is on."));

        section("texts.linking", comment("Account linking and the whitelist (Minecraft texts except link_dm)."));
        entry("texts.linking.kick_not_linked", String.join("\n",
                        "You are not whitelisted on this server.",
                        "Link your Discord account by running /mc link {code} on the Discord server.",
                        "The code is valid for {minutes} minutes."),
                comment("Kick message of unlinked players. Placeholders: {code}, {minutes}"));
        entry("texts.linking.kick_not_whitelisted", "You are not whitelisted on this server.",
                comment("Kick message of linked players without a whitelisted role."));
        entry("texts.linking.kick_no_longer_whitelisted", "You are no longer whitelisted on this server.",
                comment("Kick message when a player loses access."));
        entry("texts.linking.link_dm", String.join("\n",
                        "The Minecraft player **{player}** wants to link their account to yours.",
                        "Your code: **{code}** (valid for {minutes} minutes)",
                        "Enter it in Minecraft with `/discord confirm {code}`.",
                        "Reply `refresh` to get a new code.",
                        "If this wasn't you, ignore this message."),
                comment("Discord direct message with a link code. Placeholders: {player}, {code}, {minutes}"));

        section("texts.moderation", comment("Moderation."));
        entry("texts.moderation.muted", "&cYou are muted until {until}. Reason: {reason}",
                comment("Note to a muted player who tries to write (Minecraft text).",
                        "Placeholders: {until}, {reason}"));

        section("texts.maintenance", comment("Maintenance mode."));
        entry("texts.maintenance.kick", String.join("\n",
                        "The server is in maintenance mode. Please try again later.",
                        "Reason: {reason}"),
                comment("Kick message (Minecraft text). Placeholders: {reason}"));

        section("texts.notifications", comment(
                "Direct messages to subscribers. {server} = \"the Minecraft server\" (with its name if set),",
                "{Server} = the same with a capital letter."));
        entry("texts.notifications.server_start", ":white_check_mark: {Server} has started.", null);
        entry("texts.notifications.server_stop", ":octagonal_sign: {Server} is stopping.", null);
        entry("texts.notifications.maintenance_on",
                ":wrench: {Server} is now in maintenance mode. Reason: {reason}", comment("Placeholders: {reason}"));
        entry("texts.notifications.maintenance_off",
                ":white_check_mark: The maintenance mode of {server} is over.", null);
        entry("texts.notifications.player_online", ":green_circle: **{player}** is now online on {server}.",
                comment("Placeholders: {player}"));

        section("texts.monitoring", comment("Monitoring channel."));
        entry("texts.monitoring.lag_warning_title", ":warning: Lag: {tps} TPS", comment("Placeholders: {tps}"));
        entry("texts.monitoring.lag_warning", "The TPS are below {threshold} for {duration}.",
                comment("Placeholders: {threshold}, {duration}"));
        entry("texts.monitoring.lag_over_title", ":white_check_mark: Lag over: {tps} TPS",
                comment("Placeholders: {tps}"));
        entry("texts.monitoring.lag_over", "The lag lasted {duration}.", comment("Placeholders: {duration}"));
        entry("texts.monitoring.crash_title", ":boom: Server crashed", null);
        entry("texts.monitoring.daily_report", String.join("\n",
                        "Players: **{unique_players}** ({new_players} new)",
                        "Peak: **{peak_players}** online",
                        "Play time: **{play_time}**",
                        "Top players: {top_players}"),
                comment("Daily report. Placeholders:",
                        "  {date}            day of the report",
                        "  {unique_players}  distinct players",
                        "  {new_players}     players who joined for the first time",
                        "  {peak_players}    most players online at the same time",
                        "  {play_time}       play time of all players together",
                        "  {top_players}     the 3 players with the most play time"));
        entry("texts.monitoring.status_message", String.join("\n",
                        "**{online}/{max}** players online: {players}",
                        "TPS: {tps} · Uptime: {uptime}",
                        "Version: {version}"),
                comment("Status message.", PLACEHOLDER_LINES));

        // ---- Settings per feature

        section("bot_status", comment("Settings of the bot status."));
        entry("bot_status.type", "playing",
                comment("Activity type: \"playing\", \"watching\", \"listening\", \"competing\" or \"custom\"."));
        entry("bot_status.interval_minutes", 5, comment("Minutes between status updates. Minimum: 1."));

        section("chat", comment("Settings of the chat bridge."));
        entry("chat.channel_id", "", comment("ID of the chat channel. Moving it to another Discord server",
                "requires a restart for the slash commands, roles and the ban sync."));
        entry("chat.webhook", "auto", comment(
                "How Minecraft messages are sent to Discord:",
                "  \"auto\"  the bot creates a webhook itself (needs the \"Manage Webhooks\" permission)",
                "  URL     use this webhook",
                "  \"\"      no webhook, plain bot messages"));
        entry("chat.avatar_url", "https://vzge.me/face/256/{uuid}",
                comment("Player head used as webhook avatar. Placeholders: {uuid}, {name}"));
        entry("chat.topic_interval_minutes", 10, comment(
                "Minutes between topic updates. Minimum: 5 (Discord allows about 2 changes per 10 minutes)."));

        section("console", comment(
                "Settings of the console channel.",
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
        entry("console.channel_id", "", comment("ID of the console channel."));
        entry("console.batch_interval_ms", 2000,
                comment("Milliseconds between batched console messages. Minimum: 1000."));

        section("whitelist", comment("Discord roles on the whitelist. The vanilla whitelist is meant for players",
                "without a linked Discord account."));
        entry("whitelist.role_ids", List.of(), comment(
                "IDs of the whitelisted roles. Empty = only the vanilla whitelist counts.",
                "Also changed by /mcadmin rolewhitelist add|remove."));

        section("linking", comment("Settings of the account linking."));
        entry("linking.code_validity_minutes", 60, comment("Validity of link codes in minutes. Minimum: 1."));
        entry("linking.linked_role", "auto", comment(
                "Discord role of members with a linked account:",
                "  \"auto\"  the bot creates and uses a role named \"Linked Account\" (needs \"Manage Roles\")",
                "  role ID use this role (it must be below the bot's highest role)",
                "  \"\"      no role"));

        section("moderation", comment("Settings of the moderation. Every kick, ban and whitelist change is logged",
                "to the console and to minecraftdiscordlink/moderation.log."));
        entry("moderation.channel_id", "", comment(
                "ID of a channel the moderation log is also sent to. \"\" = only the log file.",
                "Bans on all servers need the same channel on all servers sharing the Discord server."));
        entry("moderation.ban_sync_action", "ban", comment(
                "What a Minecraft ban does to the linked Discord account: \"ban\" or \"kick\" (removed from the",
                "Discord server, but can come back with an invite)."));

        section("monitoring", comment("Settings of the monitoring channel."));
        entry("monitoring.channel_id", "", comment("ID of the monitoring channel. \"\" = monitoring off."));
        entry("monitoring.lag_tps_threshold", 15.0, comment("TPS below this value count as lag. 1 to 20."));
        entry("monitoring.lag_duration_seconds", 60,
                comment("Seconds the TPS must stay below the threshold. Minimum: 5."));
        entry("monitoring.lag_cooldown_minutes", 5, comment("Minimum minutes between two lag warnings."));
        entry("monitoring.daily_report_time", "00:00",
                comment("Time of the daily report (HH:mm, server time zone)."));
        entry("monitoring.status_message_interval_minutes", 5,
                comment("Minutes between updates of the status message. Minimum: 1."));

        section("leaderboard", comment(
                "Settings of the leaderboards.",
                "",
                "Every [[leaderboard.categories]] entry is one ranked statistic:",
                "  stat   the statistic as scoreboard criterion, e.g.",
                "         \"minecraft.custom:minecraft.play_time\", \"minecraft.mined:minecraft.diamond_ore\",",
                "         \"minecraft.killed:minecraft.zombie\" or \"minecraft.used:minecraft.diamond_pickaxe\"",
                "  name   name shown in Discord (optional, default: the vanilla name of the statistic)",
                "  order  \"highest\" = the highest value is ranked first, \"lowest\" = the lowest (e.g. deaths)",
                "At most 25 categories."));
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
     * Joins a first line and further lines to a TOML comment, see {@link #comment(String...)}.
     *
     * @param first the first line
     * @param more  the further lines
     * @return the comment text
     */
    private static String comment(String first, String[] more) {
        String[] lines = new String[more.length + 1];
        lines[0] = first;
        System.arraycopy(more, 0, lines, 1, more.length);
        return comment(lines);
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
                    new ModConfig.Server(string("server.name").strip(), bool("server.primary")),
                    new ModConfig.Discord(
                            token(),
                            new ModConfig.Status(
                                    bool("features.bot_status.enabled"),
                                    activityType(),
                                    integer("bot_status.interval_minutes", 1),
                                    string("texts.bot_status.format"),
                                    string("texts.bot_status.maintenance_format"))),
                    new ModConfig.Chat(
                            bool("features.chat.enabled"),
                            channel("chat.channel_id", "chat bridge"),
                            webhook(),
                            string("texts.chat.minecraft_format"),
                            string("texts.chat.linked_name_format"),
                            string("chat.avatar_url"),
                            string("texts.chat.discord_format"),
                            new ModConfig.Events(
                                    bool("features.chat.join"),
                                    bool("features.chat.leave"),
                                    bool("features.chat.death"),
                                    bool("features.chat.advancement"),
                                    bool("features.chat.server_start"),
                                    bool("features.chat.server_stop")),
                            new ModConfig.Topic(
                                    bool("features.chat.topic"),
                                    integer("chat.topic_interval_minutes", 5),
                                    string("texts.topic.format"),
                                    string("texts.topic.maintenance_format")),
                            bool("features.chat.mention_suggestions")),
                    new ModConfig.Console(
                            bool("features.console.enabled"),
                            channel("console.channel_id", "console channel"),
                            integer("console.batch_interval_ms", 1000),
                            permissions()),
                    new ModConfig.Whitelist(bool("features.whitelist.roles"), roleIds()),
                    new ModConfig.Linking(bool("features.linking.enabled"),
                            integer("linking.code_validity_minutes", 1), linkedRole()),
                    new ModConfig.Moderation(
                            bool("features.moderation.enabled"),
                            optionalChannel("moderation.channel_id", "moderation channel"),
                            new ModConfig.BanSync(bool("features.moderation.ban_sync"), discordAction()),
                            bool("features.moderation.mute_sync")),
                    new ModConfig.Maintenance(bool("features.maintenance.enabled")),
                    new ModConfig.Info(bool("features.info.enabled")),
                    new ModConfig.Monitoring(
                            bool("features.monitoring.enabled"),
                            optionalChannel("monitoring.channel_id", "monitoring channel"),
                            new ModConfig.Lag(bool("features.monitoring.lag"),
                                    decimal("monitoring.lag_tps_threshold", 1, 20),
                                    integer("monitoring.lag_duration_seconds", 5),
                                    integer("monitoring.lag_cooldown_minutes", 0)),
                            new ModConfig.Crash(bool("features.monitoring.crash")),
                            new ModConfig.DailyReport(bool("features.monitoring.daily_report"),
                                    time("monitoring.daily_report_time"),
                                    string("texts.monitoring.daily_report")),
                            new ModConfig.StatusMessage(bool("features.monitoring.status_message"),
                                    integer("monitoring.status_message_interval_minutes", 1),
                                    string("texts.monitoring.status_message"),
                                    bool("features.monitoring.pin_status_message"))),
                    new ModConfig.Notifications(bool("features.notifications.enabled")),
                    new ModConfig.Leaderboard(bool("features.leaderboard.enabled"),
                            integer("leaderboard.size", 1, MAX_LEADERBOARD_SIZE), categories()),
                    new ModConfig.Messages(
                            string("texts.chat.join"),
                            string("texts.chat.leave"),
                            string("texts.chat.death"),
                            string("texts.chat.advancement"),
                            string("texts.chat.server_start"),
                            string("texts.chat.server_stop"),
                            string("texts.linking.kick_not_linked"),
                            string("texts.linking.kick_not_whitelisted"),
                            string("texts.linking.kick_no_longer_whitelisted"),
                            string("texts.maintenance.kick"),
                            string("texts.linking.link_dm"),
                            string("texts.notifications.server_start"),
                            string("texts.notifications.server_stop"),
                            string("texts.notifications.maintenance_on"),
                            string("texts.notifications.maintenance_off"),
                            string("texts.notifications.player_online"),
                            string("texts.monitoring.lag_warning_title"),
                            string("texts.monitoring.lag_warning"),
                            string("texts.monitoring.lag_over_title"),
                            string("texts.monitoring.lag_over"),
                            string("texts.monitoring.crash_title"),
                            string("texts.moderation.muted")));
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
            String path = "bot_status.type";
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
            String path = "moderation.ban_sync_action";
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
