# MinecraftDiscordLink

A server-side Minecraft mod that links a Minecraft server with a Discord server.
Works on **NeoForge**, **Forge** and **Fabric** (Minecraft 1.21.1) and only
needs to be installed on the dedicated server – players don't need it.

## Features

- **Chat bridge** – the Minecraft chat, `/say` and `/me` appear in a Discord
  channel as the player (name and skin head), Discord messages appear in
  Minecraft. Emojis (`:skull:`) and mentions (`@name`, with Tab completion in
  Minecraft) work in both directions; `@everyone` and `@here` never ping.
- **Events** – joins, leaves, deaths, advancements and server start/stop as
  embeds in the chat channel.
- **Channel topic and bot status** – configurable texts with placeholders such
  as players online, distinct players today and uptime.
- **Live console** – the server console in a Discord channel; authorized roles
  or users can run commands there.
- **Account linking** – players link their Minecraft and Discord accounts;
  linked members get a Discord role and appear with both names.
- **Whitelist via Discord** – while the whitelist is on, linked members with
  configured roles may join, in addition to the vanilla whitelist. Unlinked
  players get a link code when they try to join. Optional whitelist
  applications: members apply with a form in Discord, staff accepts or denies
  them with a button.
- **Moderation** – kick, (temporary) ban, unban and whitelist from Discord,
  temporary bans also in Minecraft. Every moderation action, also from
  vanilla commands, goes to a moderation log file and optionally a Discord
  channel. Temporary mutes (chat and private messages). Warnings with
  configurable rules that mute, kick or ban automatically. Every action has
  a number for the history and later changes. Reports from Minecraft and
  Discord with buttons for the staff. Optional ban and mute
  sync with linked Discord accounts (bans and timeouts), bans and mutes on all
  servers sharing the Discord server.
- **Maintenance mode** – only operators and linked Discord admins may join;
  switched from Discord or Minecraft, optionally kept after a restart.
- **Monitoring channel** – lag warnings, crash notices, a daily report and a
  status message that is kept up to date.
- **Notifications** – Discord users subscribe to server start/stop, the
  maintenance mode or players coming online and get a direct message.
- **Leaderboards** – rankings from the vanilla statistics (default: play time
  and deaths) and every player's ranks.
- **Slash commands** – `/mc online`, `/mc status`, `/mc whois`,
  `/mc top`, `/mc stats` and more.
- **Configurable** – every feature can be switched off (all switches in
  `[features]`), all texts players and members see are in `[texts]`.

## Installation

1. Put the jar for your loader into the server's `mods` folder:
   `minecraftdiscordlink-neoforge-<version>.jar`,
   `minecraftdiscordlink-forge-<version>.jar` or
   `minecraftdiscordlink-fabric-<version>.jar`. On Fabric the
   [Fabric API](https://modrinth.com/mod/fabric-api) is required.
2. Start the server once. The mod creates `config/minecraftdiscordlink.toml`
   and logs errors until the bot token and the channels are configured – the
   server itself starts normally.
3. Set up the Discord bot (below), fill in the config and restart the server.

## Discord bot setup

1. Create an application in the
   [Discord Developer Portal](https://discord.com/developers/applications).
2. **Bot** page:
   - Copy the **token** into `discord.token` in the config. Keep it secret.
   - Enable the privileged intents **Server Members Intent** and
     **Message Content Intent**. The Presence Intent is not needed.
   - Turning off **Public Bot** is recommended, so only you can add the bot.
3. **Installation** page: use **Guild Install** with the scopes `bot` and
   `applications.commands`. Interactions endpoint, linked roles, terms of
   service and privacy policy URLs are not needed.
4. Invite the bot with this link (replace `APPLICATION_ID`):

   ```
   https://discord.com/oauth2/authorize?client_id=APPLICATION_ID&scope=bot+applications.commands&permissions=2252900130704598
   ```

   | Permission | Used for |
   |---|---|
   | View Channels, Send Messages, Embed Links | All messages |
   | Read Message History, Add Reactions | ✅/⛔ reactions on console commands, 🔇 on messages of muted players |
   | Manage Webhooks | Chat messages as the player (`chat.webhook = "auto"`) |
   | Manage Channels | Channel topic of the chat channel |
   | Manage Roles | Linked role (`linking.linked_role`) |
   | Ban Members, Kick Members, View Audit Log | Ban sync (`features.moderation.ban_sync`) |
   | Pin Messages | Pinning the status message (`features.monitoring.pin_status_message`) |
   | Timeout Members | Mute sync (`features.moderation.mute_sync`) |

   Manage Webhooks and Manage Channels can also be granted on the chat channel
   only. The bot's role must be above the linked role.
5. Create a chat channel and a console channel. Make the console channel
   visible only to admins (and the bot). Enable *Developer Mode* in Discord
   (Settings → Advanced), right-click the channels → *Copy Channel ID*, and put
   the IDs into `chat.channel_id` and `console.channel_id`.

## Several Minecraft servers

Several servers can share one Discord server and bot: use the same token,
a chat and console channel per server, a unique `server.name` on every
server and `server.primary = true` on exactly one (with none, commands
without `server` option stay unanswered; with two, both run them). Slash
commands then take an optional `server` option; without it the primary
server answers. Enable the bot status (`features.bot_status.enabled`) on one
server only. For bans on all servers, all servers use the same
`moderation.channel_id`.

## Configuration

All options are documented in the generated
`config/minecraftdiscordlink.toml`. Highlights:

The file is ordered: this server and the bot, then **all switches**
(`[features]`, grouped by feature), **all texts** (`[texts]`), then the
settings per feature.

| Option | Default | Meaning |
|---|---|---|
| `server.name`, `server.primary` | `""`, `true` | Several servers in one Discord server (see above) |
| `discord.token` | – | Bot token |
| `[features.*]` | all on except `moderation.ban_sync` | Switch every feature and its parts on or off |
| `[texts.*]` | English texts | Every text players and members see, with placeholders |
| `bot_status.type`, `interval_minutes` | `playing`, 5 min | Bot status |
| `chat.channel_id` | – | Chat channel |
| `chat.webhook` | `"auto"` | `"auto"`, a webhook URL or `""` for plain bot messages |
| `chat.topic_interval_minutes` | 10 | Topic updates |
| `console.channel_id` | – | Console channel |
| `[[console.permissions]]` | none | Who may run which commands (see below) |
| `whitelist.role_ids` | `[]` | Discord roles on the whitelist (while `/whitelist on`); also `/mcadmin rolewhitelist add\|remove` |
| `whitelist.applications_channel_id` | – | Channel with the apply button (`features.whitelist.applications`) |
| `whitelist.review_channel_id` | – | Channel for the applications to review; empty = moderation channel |
| `whitelist.reviewer_role_ids` | `[]` | Roles that may accept and deny; empty = members with *Administrator* |
| `whitelist.questions` | 2 questions | Questions of the form besides the Minecraft name, at most 4 |
| `whitelist.reapply_after` | `"7d"` | Wait after a denial before applying again |
| `linking.linked_role` | `"auto"` | Role for linked members: `"auto"`, a role ID or `""` |
| `moderation.channel_id` | – | Channel for the moderation log (optional) |
| `moderation.ban_sync_action` | `"ban"` | What a Minecraft ban does in Discord: `"ban"` or `"kick"` |
| `moderation.reports_channel_id` | – | Channel for reports; empty = moderation channel |
| `moderation.report_role_ids` | `[]` | Roles that may act on reports; empty = members with *Administrator* |
| `moderation.report_cooldown` | `"5m"` | Wait between two reports of one reporter |
| `moderation.warning_expire_after` | `""` | How long a warning counts, e.g. `"90d"`; empty = forever |
| `[[moderation.warning_rules]]` | none | Punishments for warnings: `count`, optional `within`, `action` (`kick`/`mute`/`ban`), `duration`, `repeat` (see the comment in the config) |
| `monitoring.channel_id` | – | Monitoring channel (lag, crashes, daily report, pinned status message) |
| `monitoring.*` | 15 TPS / 60 s, `00:00`, 5 min | Lag threshold, report time, status message interval |
| `leaderboard.size` | `10` | Players shown by `/mc top` |
| `[[leaderboard.categories]]` | play time, deaths | Ranked statistics (see below) |

`/mcadmin features` and `/discord features` show which features are on.
Missing bot permissions are logged and posted once in the monitoring
channel.

Console permissions – one entry per role or user; without entries nobody can
run commands from Discord:

```toml
[[console.permissions]]
id = "123456789012345678"   # role or user ID
mode = "blacklist"          # "whitelist": only these; "blacklist": all but these
commands = ["stop", "op", "deop"]
```

An entry covers a command and its subcommands (`gamerule` or only
`gamerule doDaylightCycle`). Several matching entries combine; an explicit
deny always wins.

Leaderboard categories – one entry per statistic, written as scoreboard
criterion; `order = "lowest"` ranks the lowest value first:

```toml
[[leaderboard.categories]]
stat = "minecraft.custom:minecraft.deaths"
name = "Deaths"     # optional, default: the vanilla name
order = "lowest"    # "highest" (default) or "lowest"

[[leaderboard.categories]]
stat = "minecraft.mined:minecraft.diamond_ore"
```

`/discord reload` applies all changes except the bot token and the `[server]`
section, which need a restart; changed channels are applied at once (moving
a channel to another Discord server needs a restart for the slash commands,
roles and the ban sync). Invalid
values are logged as errors and replaced by their default.

## Commands

**Minecraft**

| Command | Who |
|---|---|
| `/discord link <discordname>` – get a link code by DM | everyone |
| `/discord confirm <code>` – complete the link | everyone |
| `/discord unlink` | everyone |
| `/discord whois <name>` | everyone |
| `/discord admin link <player> <discordname>` | admins |
| `/discord tempban <player> <duration> <reason>` – e.g. `7d`, `12h`, `1d12h` | admins |
| `/discord networkban`, `networktempban`, `networkunban` – the same on all servers | admins |
| `/discord mute <player> <duration> <reason>`, `unmute`, `networkmute`, `networkunmute` | admins |
| `/discord history <player> [page]` – moderation actions on a player | admins |
| `/report <player> <reason>` – report a player to the staff | everyone |
| `/discord warn <player> <reason>`, `networkwarn`, `unwarn <id> <reason>`, `warnings <player>` | admins |
| `/discord case <id>`, `case reason <id> <reason>`, `case reactivate <id> <reason>` | admins |
| `/discord rolewhitelist status` | admins |
| `/discord maintenance on [persistent] [reason]`, `off [reason]`, `status` | admins |
| `/discord reload`, `/discord features` | admins |

Admins are operators of level 4 or players with the permission node
`minecraftdiscordlink.admin` (e.g. with LuckPerms).

**Discord**

| Command | Who |
|---|---|
| `/mc online`, `/mc status`, `/mc whois <name>`, `/mc playtime <player>` | everyone |
| `/mc top <category>`, `/mc stats <player>` – leaderboards | everyone |
| `/mc subscribe`, `/mc unsubscribe`, `/mc subscriptions` – DM notifications | everyone |
| `/mc report <player> <reason> [message]` – report a player, optionally with a Discord message | everyone |
| `/mc link <code>` – link with the code from the kick message | everyone |
| `/mc unlink` | everyone |
| `/mcadmin kick`, `kickall [include_operators]`, `ban [duration] [delete_messages] [all_servers]`, `unban [all_servers]`, `banlist [page]` – reason required except for `banlist` | members with *Administrator* |
| `/mcadmin mute <duration> [all_servers]`, `unmute [all_servers]`, `mutelist [page]` – mutes last at most 28 days | members with *Administrator* |
| `/mcadmin history <player> [page]` – moderation actions on a player, with their numbers | members with *Administrator* |
| `/mcadmin warn <player> <reason> [all_servers]`, `unwarn <id> <reason>`, `warnings <player> [page]` | members with *Administrator* |
| `/mcadmin case show <id>`, `case reason <id> <reason>`, `case reactivate <id> <reason>` | members with *Administrator* |
| `/mcadmin whitelist add\|remove\|list\|on\|off`, `/mcadmin rolewhitelist add\|remove\|status` | members with *Administrator* |
| `/mcadmin maintenance on [keep_after_restart] [reason]`, `off`, `status` | members with *Administrator* |
| `/mcadmin link <player> <user>`, `/mcadmin reload`, `/mcadmin features` | members with *Administrator* (adjustable in the server's integration settings) |

## Data files

Stored in `minecraftdiscordlink/` in the server directory:

| File | Content |
|---|---|
| `links.json` | Linked accounts; edit it while the server is stopped to remove links |
| `stats.json` | Distinct players for the placeholders |
| `state.json` | ID of the role the bot created for `linked_role = "auto"` |

A file that cannot be read is never overwritten; the mod logs an error instead.

## Building

Requires Java 21.

```bash
./gradlew build
```

The jars are written to `fabric/build/libs`, `neoforge/build/libs` and
`forge/build/libs` (the Forge jar without the `-slim` suffix contains the
libraries). GitHub Actions builds all three on every push to `master`.

Concept and specification: [docs/CONCEPT.md](docs/CONCEPT.md),
[docs/SPEC.md](docs/SPEC.md).

## License

GPL-3.0
