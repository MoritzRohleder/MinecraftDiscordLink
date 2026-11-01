# MinecraftDiscordLink – Specification

> Status: **agreed**. Based on [CONCEPT.md](CONCEPT.md).

## Conventions

- Placeholders in all configurable texts use curly braces: `{name}`.
- Dates and times use the server's time zone.
- The mod only runs on **dedicated servers** (not in singleplayer / LAN worlds).

## 1. Configuration

File: `config/minecraftdiscordlink.toml`. Created with defaults and comments on first start.
The generated file documents all available placeholders in its comments
(the references to section 2 below are only used in this spec).
Can be reloaded at runtime (see section 3). Changes to the bot token and the
`[server]` section require a server restart; everything else, including all
channel IDs, is applied on reload.

All switches are in `[features]`, grouped by feature. Switched off commands stay
registered (all servers sharing a Discord server register the same commands)
and answer `This feature is switched off on this server.`

If the token or a channel ID is missing or invalid, the server starts normally,
the mod logs an **error** and the affected part stays inactive (no token: the
whole mod; one channel: only that feature).

```toml
# ---- This server and the bot (changes need a restart)
[server]
name = ""            # name when several servers share one Discord server
primary = true       # answers slash commands without a "server" option

[discord]
token = ""

# ---- All switches
[features.chat]
enabled = true
join = true
leave = true
death = true
advancement = true
server_start = true
server_stop = true
topic = true
mention_suggestions = true
[features.bot_status]
enabled = true
[features.console]
enabled = true
[features.linking]
enabled = true       # off: the linked role is removed, the links are kept
[features.whitelist]
roles = true         # Discord roles on the whitelist
[features.moderation]
enabled = true       # the moderation commands; the log is always written
ban_sync = false
mute_sync = false    # mutes as Discord timeouts and vice versa
[features.maintenance]
enabled = true
[features.info]
enabled = true       # /mc online, status, whois, playtime and /discord whois
[features.leaderboard]
enabled = true
[features.notifications]
enabled = true
[features.monitoring]
enabled = true
lag = true
crash = true
daily_report = true
status_message = true
pin_status_message = true

# ---- All texts (section 4g)
[texts.chat]
minecraft_format = "&9[Discord]&r {name}: {message}"
discord_format = "**{player}**: {message}"
linked_name_format = "{mc} ({discord})"
join = ":inbox_tray: **{player}** joined the game"
# leave, death, advancement, server_start, server_stop
[texts.bot_status]
format = "{online}/{max} online"
maintenance_format = "Maintenance"
[texts.topic]
format = "{online}/{max} online | {unique_today} players today | Uptime: {uptime} | Last updated: {last_updated}"
maintenance_format = "🔧 Maintenance | {online}/{max} online | Last updated: {last_updated}"
[texts.linking]
# kick_not_linked, kick_not_whitelisted, kick_no_longer_whitelisted, link_dm
[texts.moderation]
muted = "&cYou are muted until {until}. Reason: {reason}"
[texts.maintenance]
# kick
[texts.notifications]
# server_start, server_stop, maintenance_on, maintenance_off, player_online
[texts.monitoring]
# lag_warning_title, lag_warning, lag_over_title, lag_over, crash_title,
# daily_report, status_message

# ---- Settings per feature
[bot_status]
type = "playing"     # "playing", "watching", "listening", "competing", "custom"
interval_minutes = 5

[chat]
channel_id = ""
webhook = "auto"     # "auto", a webhook URL or "" for plain bot messages
avatar_url = "https://vzge.me/face/256/{uuid}"
topic_interval_minutes = 10

[console]
channel_id = ""
batch_interval_ms = 2000
# [[console.permissions]] entries: id, mode ("whitelist"/"blacklist"), commands
#   An entry matches a command and all its subcommands ("gamerule" or
#   "gamerule doDaylightCycle"); several entries combine, explicit denies win.

[whitelist]
role_ids = []        # also changed by /mcadmin rolewhitelist add|remove

[linking]
code_validity_minutes = 60
linked_role = "auto" # "auto" (role "Linked Account"), a role ID or ""

[moderation]
channel_id = ""      # optional; the same on all servers for bans on all servers
ban_sync_action = "ban"  # what a Minecraft ban does in Discord: "ban" or "kick"

[monitoring]
channel_id = ""
lag_tps_threshold = 15.0       # 1 to 20
lag_duration_seconds = 60      # minimum 5
lag_cooldown_minutes = 5
daily_report_time = "00:00"    # HH:mm, server time zone
status_message_interval_minutes = 5

[leaderboard]
size = 10            # players shown by /mc top, 1 to 25
# One [[leaderboard.categories]] entry per statistic (at most 25), written as
# scoreboard criterion; name optional; order "highest" (default) or "lowest".
[[leaderboard.categories]]
stat = "minecraft.custom:minecraft.play_time"
name = "Play time"
order = "highest"
[[leaderboard.categories]]
stat = "minecraft.custom:minecraft.deaths"
name = "Deaths"
order = "lowest"
```

The file is ordered: this server and the bot, then all switches
(`[features]`), all texts (`[texts]`), then the settings per feature.

## 2. Placeholders for topic and bot status

| Placeholder | Meaning |
|---|---|
| `{online}` | Players currently online |
| `{max}` | Max players |
| `{unique_session}` | Distinct players since server start |
| `{unique_today}` | Distinct players today |
| `{unique_total}` | Distinct players ever (persisted) |
| `{uptime}` | Time since server start |
| `{last_updated}` | Time of this update (`HH:mm`) |
| `{version}` | Minecraft version |
| `{motd}` | Server MOTD |
| `{players}` | Names of the online players (`nobody` if empty) |
| `{tps}` | Ticks per second |

## 3. Commands

### Minecraft

| Command | Who | Description |
|---|---|---|
| `/discord link <discordname>` | everyone | Send link code via DM |
| `/discord confirm <code>` | everyone | Complete link |
| `/discord unlink` | everyone | Remove own link |
| `/discord whois <name>` | everyone | Linked account or "not linked" (Minecraft or Discord name) |
| `/discord admin link <player> <discordname>` | admin | Link accounts manually |
| `/discord tempban <player> <duration> <reason>` | admin | Temporary ban, e.g. `/discord tempban Steve 7d Griefing` |
| `/discord networkban <player> <reason>` | admin | Permanent ban on all servers sharing the Discord server |
| `/discord networktempban <player> <duration> <reason>` | admin | Temporary ban on all servers |
| `/discord networkunban <player> <reason>` | admin | Lift a ban on all servers |
| `/discord mute <player> <duration> <reason>` | admin | Mute a player for a time (at most 28 days) |
| `/discord unmute <player> <reason>` | admin | Lift a mute |
| `/discord networkmute <player> <duration> <reason>`, `networkunmute <player> <reason>` | admin | The same on all servers |
| `/discord rolewhitelist status` | admin | Show whether the whitelist is on and the whitelisted roles |
| `/discord maintenance on [persistent] [reason]` | admin | Switch the maintenance mode on; `persistent` = `true` keeps it after a restart |
| `/discord maintenance off [reason]` | admin | Switch the maintenance mode off |
| `/discord maintenance status` | admin | Show the maintenance mode |
| `/discord reload` | admin | Reload config |
| `/discord features` | admin | Show which features are switched on (section 4h) |

Admin = OP level 4 **or** permission node `minecraftdiscordlink.admin`.

### Discord (slash commands)

Two top-level commands: `/mc` for everyone and `/mcadmin` for administrators.

| Command | Who | Description |
|---|---|---|
| `/mc online` | everyone | List online players |
| `/mc status` | everyone | Uptime, TPS/MSPT, player count, version |
| `/mc whois <name>` | everyone | Linked account or "not linked" (Minecraft or Discord name) |
| `/mc playtime <player>` | everyone | Play time from vanilla statistics |
| `/mc top <category>` | everyone | Best players of a leaderboard category (suggested while typing) |
| `/mc stats <player>` | everyone | The player's value and rank in every leaderboard category |
| `/mc subscribe [event] [player]` | everyone | Get a DM on a server event or when a player joins (exactly one of both) |
| `/mc unsubscribe [event] [player]` | everyone | Stop a notification |
| `/mc subscriptions` | everyone | List the own notifications |
| `/mc link <code>` | everyone | Complete link with a kick code |
| `/mc unlink` | everyone | Remove own link |
| `/mcadmin link <player> <user>` | Administrator | Link accounts manually |
| `/mcadmin reload` | Administrator | Reload config |
| `/mcadmin features` | Administrator | Show which features are switched on (section 4h) |
| `/mcadmin kick <player> <reason>` | Administrator | Kick an online player |
| `/mcadmin ban <player> <reason> [duration] [delete_messages] [all_servers]` | Administrator | Ban a player, permanently without duration; a banned player gets the new ban |
| `/mcadmin unban <player> <reason> [all_servers]` | Administrator | Lift a ban |
| `/mcadmin banlist [page]` | Administrator | List the banned players with reason, source and end; ◀ ▶ buttons |
| `/mcadmin mute <player> <reason> <duration> [all_servers]` | Administrator | Mute a player for a time (at most 28 days); a muted player gets the new mute |
| `/mcadmin unmute <player> <reason> [all_servers]` | Administrator | Lift a mute |
| `/mcadmin mutelist [page]` | Administrator | List the muted players; ◀ ▶ buttons |
| `/mcadmin whitelist add <player> [reason]` | Administrator | Add a player to the vanilla whitelist |
| `/mcadmin whitelist remove <player> [reason]` | Administrator | Remove a player from the vanilla whitelist |
| `/mcadmin whitelist list [page]` | Administrator | List the whitelisted players; ◀ ▶ buttons |
| `/mcadmin whitelist on <reason>` | Administrator | Switch the whitelist on (vanilla list and roles) |
| `/mcadmin whitelist off [reason]` | Administrator | Switch the whitelist off |
| `/mcadmin rolewhitelist add <role> [reason]` | Administrator | Add a whitelisted Discord role |
| `/mcadmin rolewhitelist remove <role> [reason]` | Administrator | Remove a whitelisted Discord role |
| `/mcadmin rolewhitelist status` | Administrator | Show whether the whitelist is on and the whitelisted roles |
| `/mcadmin maintenance on [keep_after_restart] [reason]` | Administrator | Switch the maintenance mode on |
| `/mcadmin maintenance off [reason]` | Administrator | Switch the maintenance mode off |
| `/mcadmin maintenance status` | Administrator | Show since when, by whom, why and whether it survives a restart |

`whois` searches Minecraft and Discord names. Minecraft names only count for
players who have played on the server or are linked. If a name matches
different people on both sides, both results are shown.

`reload` reloads everything except the bot token and the `[server]` section;
the answer says so if one of them changed. Changed channels are applied at
once (chat bridge, topic and console move to the new channels).

Administrator = Discord role with the *Administrator* permission. Enforced by
Discord itself on `/mcadmin` (`default_member_permissions`); server admins can adjust it in
the server's integration settings.

## 4. Messages

### Minecraft → Discord (chat channel)

Chat messages are sent via webhook as the player. Discord Markdown in chat
messages is allowed (`**bold**` is shown bold). Events are sent via webhook as
**embeds** with a colored bar and an emoji. The texts are configurable in
`[texts]` (section 4g); the table shows the defaults.

Forwarded: public chat, `/say` and `/me` (vanilla; private messages such as
`/msg` and `/teammsg` are not forwarded). Display:

| Source | Discord |
|---|---|
| `/say Hello` by a player | webhook as the player: `:loudspeaker: Hello` |
| `/say Hello` by the console | bot: `:loudspeaker: **Server**: Hello` |
| `/me waves` | webhook as the player: `*\* waves*` (italic) |

`webhook = "auto"`: the bot uses a webhook named "Minecraft Chat Bridge" in the
chat channel and creates it only if it does not exist yet (Discord does not
allow "discord" in webhook names; player names containing "discord" or
"clyde" get a middle dot, e.g. `Disc·ord`).

| Event | Embed | Color |
|---|---|---|
| Join | `:inbox_tray: **{player}** joined the game` | green |
| Leave | `:outbox_tray: **{player}** left the game` | red |
| Death | `:skull: ` + vanilla death message, e.g. `**Steve** was slain by Zombie` | black |
| Advancement | `:trophy: ` + vanilla wording, e.g. `**{player}** has made the advancement **[{advancement}]**` | gold |
| Server start | `:white_check_mark: Server started` | green |
| Server stop | `:octagonal_sign: Server stopped` | red |

Server start/stop are sent by the bot itself (bot name and avatar).
Without a webhook (`webhook = ""`) the bot sends all embeds itself.

Webhook name: the player name. For linked players `texts.chat.linked_name_format` is used
(default `{mc} ({discord})`), for chat messages and events alike.

Webhook avatar: player head from an external service. The URL is configurable
with the placeholders `{uuid}` and `{name}`. Default:
`https://vzge.me/face/256/{uuid}`.

### Discord → Minecraft

Default `&9[Discord]&r {name}: {message}` – prefix in blue, name and message in
white. The format supports Minecraft formatting codes with `&` (`&0`–`&f`,
`&k`–`&o`, `&r`); text without a code is white. Role colors are not used (may
be added later). Discord Markdown is shown as typed.

- `{name}`: server nickname, otherwise global display name, otherwise username.
- Messages of bots and webhooks are ignored.
- Messages consisting only of attachments are not forwarded.

### Console channel

Lines are batched per `batch_interval_ms`; a batch longer than 2000 characters
is split into several messages. One line per log entry:

`` `HH:mm:ss` **INFO** message ``

### Account linking

DM:

```
The Minecraft player **{player}** wants to link their account to yours.
Your code: **{code}** (valid for {minutes} minutes)
Enter it in Minecraft with `/discord confirm {code}`.
Reply `refresh` to get a new code.
If this wasn't you, ignore this message.
```

Kick message (Discord whitelist, not linked):

```
You are not whitelisted on this server.
Link your Discord account by running /mc link {code} on the Discord server.
The code is valid for {minutes} minutes.
```

Repeated join attempts while a code is valid show the same code.

Kick message (role removed or unlinked):

```
You are no longer whitelisted on this server.
```

### Command responses

Discord answers with embeds (no pings), Minecraft with plain text.

| Command | Discord embed |
|---|---|
| `online` | Title `🟢 2/20 players online`, one player per line, linked players with their Discord account (`Steve · @anedhel`); gray "Nobody is online." if empty |
| `status` | Fields Uptime, TPS, MSPT, Players, Version; bar green from 19 TPS, yellow from 15, otherwise red |
| `whois` | One embed per account: player head and name, fields Minecraft, Discord, Last online, Linked since; gray "Not linked" otherwise |
| `playtime` | Player head, `Play time: 12h 34m`; online players additionally `🟢 Online since <relative time>` with a green bar |
| `link`, `unlink`, `reload` | Green on success, red on errors |

| `top` | Gold, title `🏆 Play time`, one line per player: `🥇 **Steve** · 12h 34m`, from rank 4 `**4.** **Alex** · 2h 5m`; footer `12 players ranked` (plus `· lowest value first`) |
| `stats` | Player head and name, one line per category: `**Play time:** 5th · 17m`, `**Deaths:** 1st · 0`; footer `Ranked among 12 players` |

Minecraft `whois`: `Steve is linked to @steve (online now)` /
`Steve is not linked (last online 2026-10-29 13:10)`.

"Last online" is recorded by the mod when a player leaves (`last_seen` in
`stats.json`); it is "unknown" for players who have not left since.

## 4a. Leaderboards

- Ranked are all players with a statistics file in the world
  (`world/stats/<uuid>.json`); online players with their live values. A
  statistic missing from a file counts as 0.
- Players with the same value share a rank, the next rank is skipped
  (50, 30, 30, 10 → 1st, 2nd, 2nd, 4th).
- Values are shown in the statistic's unit: time as `42s` below a minute,
  otherwise `2d 3h 15m`; distances as `38 cm` / `850 m` / `12.3 km`, damage with one decimal, everything else as number.
- Unknown statistics are logged as errors and left out. Categories without a
  name use the vanilla name (`Time Played`, `Mined: Diamond Ore`).
- The categories are applied on reload (the slash commands are registered
  again).

## 4b. Moderation

### Actions and reasons

- Players are given by Minecraft name; players who never joined are looked
  up like in vanilla (Mojang).
- A reason is required for `kick`, `ban`, `tempban`, `unban` and
  `whitelist on`; optional for the other whitelist commands. Vanilla
  commands (`/kick`, `/ban`, `/pardon`, …) stay unchanged: without a reason
  the log shows the vanilla default (`Banned by an operator.`) or
  `no reason given`.
- Durations: `30m`, `12h`, `7d`, `2w` and combinations such as `1d12h`
  (shown in days, hours and minutes, e.g. `14d`). Without a duration a ban
  is permanent; at most 100 years.
- Banning a player who is already banned replaces the ban: new reason and
  duration, counted from the command (`Updated the ban of Steve: now 7d`,
  logged as `BAN UPDATED`).
- Bans are vanilla bans in `banned-players.json`: the source is the
  moderator (`Discord @anedhel` for Discord, the player name or `Server`
  for Minecraft), the end of a temporary ban is the vanilla `expires` field.
- Banned online players are kicked with the vanilla ban message including
  the reason and, for temporary bans, the end.
- `whitelist add` while the whitelist is off adds the player anyway; the
  answer says that the whitelist is off.
- Expired temporary bans are removed by the mod within a minute (and on
  login attempts and server start), logged with the mod as moderator:
  `… | BAN EXPIRED | Steve | by MinecraftDiscordLink | Temporary ban expired`.
- Commands from the Discord console channel are logged with the Discord user
  as moderator (`Discord @anedhel (console)`).

### Whitelist and Discord roles

- There is one whitelist switch, the vanilla one (`/whitelist on|off`,
  `/mcadmin whitelist on|off`). While it is on, operators, the players on
  the vanilla whitelist and linked members with a whitelisted Discord role
  may join. The vanilla whitelist is meant for players without a linked
  Discord account.
- Whitelisted roles count when `features.whitelist.roles = true` and at least one
  role is configured; otherwise only the vanilla whitelist counts.
- `rolewhitelist add|remove` write `whitelist.role_ids` into the config
  file: the file is read again, only this value is changed, values and
  comments are kept (the file is rewritten in the generated format:
  comments at the end of a line move above the line, indentation and blank
  lines are unified). Other changes in the file are not applied until
  `reload`. A file that cannot be read is not touched; the command fails.
- Removing a role kicks online players who lose access. Switching the
  whitelist on kicks players without access if `enforce-whitelist` is on
  (vanilla behavior, extended by the roles).

### Moderation log

Every moderation action is logged, from Discord commands as well as from
vanilla commands: kick, ban, ban updated, unban, ban expired, IP ban and IP
unban (log only), whitelist add/remove/on/off, role add/remove, maintenance
on/off and failed ban sync actions. Bans and whitelist changes by other mods
are logged too; the moderator is then the stored source or `unknown` (the
vanilla whitelist stores no source).

- Server console (INFO).
- `minecraftdiscordlink/moderation.log`, one line per action:
  `2026-10-30 15:40:12 | BAN | Steve | by Discord @anedhel | 7d (until 2026-11-06 15:40) | Griefing`
  (bans on all servers add `| all servers`).
- The moderation channel if `moderation.channel_id` is set (changeable by a
  reload): one embed per action with moderator, duration, reason and time
  stamp; kick yellow, ban and failed sync red, unban and ban expired green,
  whitelist blue, maintenance blurple.

Admins in Minecraft see Discord actions like vanilla commands of other
operators: `[@anedhel: Banned Steve: Griefing]` (gray, italic).

### Ban sync (`features.moderation.ban_sync`)

Opt-in, only for linked accounts:

| Action | Effect on the other side |
|---|---|
| Minecraft ban (any source, also temporary) | `moderation.ban_sync_action = "ban"`: the Discord account is banned; `"kick"`: it is only removed from the Discord server and can come back with an invite |
| Minecraft unban | the Discord ban is lifted (with `"ban"`) |
| Temporary ban expires | the Discord ban is lifted |
| Discord ban (by anyone, also via the Discord menu) | the Minecraft account is banned, source the Discord moderator, reason the Discord ban reason (`Banned on Discord` if none) |
| Discord unban | the Minecraft ban is lifted |
| Kick (either side) | only logged, never synchronized |
| Ban updated | not synchronized again |

- Discord has no temporary bans. The mod removes expired Minecraft bans
  itself (see above) and lifts the Discord ban then.
- An action caused by the sync does not trigger the sync again and is
  logged once.
- The Discord ban reason (audit log) is `Minecraft ban: <reason>`.
  `/mcadmin ban delete_messages:True` also deletes the Discord messages of
  the last 7 days (default: none).
- A failed Discord action (missing permission, member above the bot's
  highest role, Discord unavailable) is logged as `SYNC FAILED` in the
  moderation log and channel, so moderators can do it by hand. Failed
  unbans are retried every minute while the server runs; those still open
  when the server stops are logged again.
- Requires the bot permissions *Ban Members*, *Kick Members* and *View
  Audit Log* (for the moderator of Discord bans).

### Mutes

- A muted player cannot chat, use `/me` or send private messages (`/msg`,
  `/tell`, `/w`, `/teammsg`, `/tm`); the messages go nowhere, also not to
  Discord. The player sees `texts.moderation.muted` (default `You are muted
  until 2026-11-08 15:40. Reason: Spam`, red). Joining and playing work as
  usual.
- A mute always ends: the duration is required, at most 28 days (the
  longest Discord timeout). Muting a muted player replaces the mute,
  counted from the command (`MUTE UPDATED`).
- Stored in `minecraftdiscordlink/mutes.json` (Minecraft has no mutes).
  Ended mutes are removed within a minute and logged as `MUTE EXPIRED` with
  the mod as moderator.
- Logged like bans: `MUTE`, `MUTE UPDATED`, `UNMUTE`, `MUTE EXPIRED`
  (gold 🔇, unmutes green 🔊).
- Mutes on all servers (`all_servers`, `/discord networkmute`,
  `/discord networkunmute`) work like bans on all servers.

### Mute sync (`features.moderation.mute_sync`)

Opt-in, only for linked accounts:

| Action | Effect on the other side |
|---|---|
| Minecraft mute | the Discord account is timed out until the same end (reason `Minecraft mute: <reason>`) |
| Minecraft unmute | the Discord timeout is removed |
| Discord timeout (by anyone) | the Minecraft account is muted until the end of the timeout, moderator from the audit log, reason the timeout reason (`Timed out on Discord` if none) |
| Discord timeout removed | the Minecraft mute is lifted |
| Mute ends | both end at the same time; nothing to synchronize |

Requires the bot permissions *Timeout Members* and *View Audit Log*; failed
actions are logged as `SYNC FAILED` like for the ban sync.

### Bans on all servers

With several servers sharing the Discord server (section 4f), a ban or unban
can apply to all of them: `/mcadmin ban … all_servers:True`,
`/mcadmin unban … all_servers:True`, `/discord networkban`,
`/discord networktempban`, `/discord networkunban`. Vanilla `/ban` stays
local, kicks are never sent to other servers.

- Requires `server.name` and the same `moderation.channel_id` on all
  servers; otherwise the command fails.
- The servers do not talk to each other directly. The server where the ban
  is made posts it to the moderation channel as usual (footer
  `All servers`); the title links to a URL carrying the ban as data. The
  other servers read the channel, take only such embeds sent by the bot
  itself, and apply the ban with the same end, reason and moderator
  (`Discord @anedhel via Survival`, source with ` (all servers)`). They log
  it to their own file but do not post it again.
- A server that was not running catches up after its next start, from the
  last message it checked (`network_message` in `state.json`); bans that
  have ended in the meantime are skipped. On the very first start nothing
  older is applied.
- The ban sync to Discord runs only on the server where the ban was made.
  An expiring ban on all servers is reported only by that server.
- Updating a ban on all servers works like a new one: the other servers
  replace their ban.

### Answers

Discord answers are embeds as usual: green on success, e.g.
`✅ Banned **Steve** for 7d: Griefing`, red on errors, e.g.
`❌ Steve is not banned.` `banlist` and `whitelist list` show 15 entries per
page (`**Steve** · by Discord @anedhel · until <date> · Griefing`); the ◀ ▶
buttons turn the pages in the same message, only for the user who ran the
command; `page` chooses the first page shown.

## 4c. Maintenance mode

- While it is on, only operators and players linked to a Discord member with
  the *Administrator* permission may join (while Discord is unavailable only
  operators). It overrides the whitelist: these players may join even if
  they are not whitelisted, and role changes do not kick them. Bans still
  apply.
- Switching it on kicks all other online players. Kick and login message:
  `texts.maintenance.kick` (default `The server is in maintenance mode.
  Please try again later.` and `Reason: …`).
- By default it ends with the next restart, also when switched on from
  Minecraft; with `keep_after_restart:True` / `/discord maintenance on true`
  it is stored in `state.json` and active again after a restart.
- The reason is optional. Switching on and off is logged in the moderation
  log (`MAINTENANCE ON`, `MAINTENANCE OFF`).
- While it is on, the channel topic uses `texts.topic.maintenance_format`
  (default `🔧 Maintenance | {online}/{max} online | Last updated:
  {last_updated}`) and the bot status `texts.bot_status.maintenance_format`
  (default `Maintenance`); both are updated immediately when it changes.

## 4d. Monitoring channel

All messages are embeds in `monitoring.channel_id` with a time stamp; the
channel can be changed by a reload. The texts of the lag and crash messages
are in `[texts.monitoring]`.

| Message | Content |
|---|---|
| Lag warning | Yellow, `⚠️ Lag: 11.2 TPS`, "The TPS are below 15.0 for 1m.", lowest TPS and MSPT. Sent when the TPS stay below `lag_tps_threshold` for `lag_duration_seconds` (measured every 5 seconds), at most once per `lag_cooldown_minutes` |
| All-clear | Green, `✅ Lag over: 20.0 TPS`, how long the lag lasted and the lowest TPS; always follows its warning |
| Crash notice | Red, `💥 Server crashed`, title and exception of the crash report (no console lines: they are in the console channel and the crash report); sent before the server stops (a killed process cannot be reported) |
| Daily report | Blurple, `📅 Daily report · 2026-10-30`, `texts.monitoring.daily_report` with the numbers since the last report, at `daily_report_time` |
| Status message | One message, edited every `status_message_interval_minutes`: `🟢 Server online` (green) or `🔧 Server in maintenance` (blurple) with `texts.monitoring.status_message`; `🔴 Server offline` when the server stops. Updated at once on reload and maintenance changes; a deleted message is sent again. Pinned by the bot with `features.monitoring.pin_status_message` (needs *Pin Messages*) |

Daily report numbers are collected since the last report and stored in
`minecraftdiscordlink/report.json`, so a restart keeps them; a report missed
while the server was down is sent after the next start. `{date}` is the day
the period started; play time counts sessions until the report, players
online at that moment count for the next period too. `{top_players}`:
`Steve (3h 12m), Alex (45m)`.

The ID of the status message is stored in `state.json` (`status_message`).

## 4e. Notifications

- Events: `server_start` (when the server has started and the bot is
  ready), `server_stop` (when the server stops; the stop message in the
  chat channel and the offline status message go first, the DMs get the
  rest of the 5 second shutdown time), `maintenance` (on and off, with the
  reason).
- Players: a DM every time the player joins (no cooldown; unsubscribe if
  it gets too much); users are not told about their own linked player.
- The answers of the subscribe commands are only visible to the user
  (ephemeral). DMs are embeds with a footer how to unsubscribe, e.g.
  `🟢 **Steve** is now online on the Minecraft server.`
- Users who do not accept DMs are skipped (logged as warning); their
  subscriptions stay. With `features.notifications.enabled = false` nothing is sent
  and the commands answer that notifications are switched off.
- Stored in `minecraftdiscordlink/subscriptions.json`
  (`{"subscriptions": [{"discord_id": "…", "events": ["server_start"],
  "players": ["<uuid>"]}]}`).

## 4f. Several Minecraft servers in one Discord server

- Every server runs the mod with the same bot token, its own chat and
  console channel and its own `server.name`; exactly one is
  `server.primary = true`. Moderation and monitoring channels may be
  shared.
- All servers register the same slash commands; every subcommand has an
  optional `server` option. The server with that name answers (case
  insensitive), without the option the primary server. For an unknown name
  the primary server answers `No Minecraft server named … is online.` after
  2 seconds.
- `/mc link <code>` is answered by the server that gave out the code; if no
  server has it, the primary server answers after 2 seconds that it is
  invalid. `refresh` in DMs is answered by the server with the pending
  code, "nothing pending" only by the primary server.
- `/mc top` suggests the categories of the answering server
  (autocomplete), as they may differ between servers.
- Answers, moderation and monitoring embeds of a named server show the name
  in the footer; notifications name it in the text (`the Minecraft server
  **Survival**`) and in the unsubscribe hint (`server:Survival`).
- Links, subscriptions, the maintenance mode and all other data are per
  server. The linked role `auto` is named `Linked Account (<name>)`.
- The bot status is set by every server with `features.bot_status.enabled`; for
  several servers enable it on one server only, e.g. with the text
  "Watching several servers" (the servers do not communicate).
- The primary server is set by each server itself (`server.primary`).
  Without a primary server, commands without `server` option stay
  unanswered; with two, both run them (e.g. a ban on both servers).
- Bans on all servers: see section 4b.

## 4g. Configurable texts (`[texts]`)

All texts players and Discord members see are in `[texts]`, grouped by
feature. Discord texts use Discord Markdown and emoji shortcodes
(`:skull:`), Minecraft texts formatting codes with `&` (marked *MC* below).
Placeholder values are inserted as plain text.

| Key | Where | Placeholders |
|---|---|---|
| `texts.chat.minecraft_format` (*MC*) | Discord message in Minecraft | `{name}`, `{message}` |
| `texts.chat.discord_format` | Minecraft message without webhook | `{player}`, `{message}` |
| `texts.chat.linked_name_format` | name of linked players | `{mc}`, `{discord}` |
| `texts.chat.join`, `leave` | chat channel | `{player}` |
| `texts.chat.death` | chat channel | `{message}` (vanilla death message, name in bold), `{player}` |
| `texts.chat.advancement` | chat channel | `{message}` (vanilla wording), `{player}`, `{advancement}` |
| `texts.chat.server_start`, `server_stop` | chat channel | – |
| `texts.bot_status.format`, `maintenance_format` | bot status | section 2 |
| `texts.topic.format`, `maintenance_format` | channel topic | section 2 |
| `texts.linking.kick_not_linked` (*MC*) | kick | `{code}`, `{minutes}` |
| `texts.linking.kick_not_whitelisted`, `kick_no_longer_whitelisted` (*MC*) | kick | – |
| `texts.linking.link_dm` | DM with a link code | `{player}`, `{code}`, `{minutes}` |
| `texts.maintenance.kick` (*MC*) | kick during maintenance | `{reason}` |
| `texts.moderation.muted` (*MC*) | note to a muted player who tries to write | `{until}`, `{reason}` |
| `texts.notifications.server_start`, `server_stop`, `maintenance_off` | DM | `{server}`, `{Server}` |
| `texts.notifications.maintenance_on` | DM | `{server}`, `{Server}`, `{reason}` |
| `texts.notifications.player_online` | DM | `{player}`, `{server}`, `{Server}` |
| `texts.monitoring.lag_warning_title`, `lag_over_title` | monitoring channel | `{tps}` |
| `texts.monitoring.lag_warning` | monitoring channel | `{threshold}`, `{duration}` |
| `texts.monitoring.lag_over` | monitoring channel | `{duration}` |
| `texts.monitoring.crash_title` | monitoring channel | – |
| `texts.monitoring.daily_report` | monitoring channel | `{date}`, `{unique_players}`, `{new_players}`, `{peak_players}`, `{play_time}`, `{top_players}` |
| `texts.monitoring.status_message` | monitoring channel | section 2 |

`{server}` is `the Minecraft server` (with `server.name`: `the Minecraft
server **Survival**`), `{Server}` the same with a capital letter. A missing
reason is shown as `no reason given`. Command answers and errors are not
configurable.

## 4h. Feature overview and missing permissions

- `/mcadmin features` (Discord) and `/discord features` (Minecraft, admins)
  list every feature and its parts as switched on (✅/✔) or off (❌/✘), with a
  note when a switched on feature cannot work, e.g. `no channel configured`
  or `no roles configured`.
- A missing bot permission is logged as an error once per permission and
  place, e.g. `The bot needs the 'Pin Messages' permission in the monitoring
  channel for pinning the status message.`, and posted once as
  `⚠️ Missing permission` in the monitoring channel (also the warnings from
  before the monitoring channel was ready). Covered: Manage Webhooks, Manage
  Channels (topic), Manage Roles, Add Reactions, Pin Messages, Ban/Kick
  Members and View Audit Log.
- A reload that moves the chat or console channel to another Discord server
  works for the chat and the console at once, but slash commands, roles and
  the ban sync stay on the previous Discord server until a restart; the mod
  logs a warning.

## 5. Data storage

All data lives in `minecraftdiscordlink/` in the server directory. Accounts are stored
by ID only (Minecraft UUID, Discord user ID); names are resolved at runtime,
because both can change.

`minecraftdiscordlink/links.json`:

```json
{
  "links": [
    {
      "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "discord_id": "123456789012345678",
      "linked_at": "2026-10-29T10:29:33Z"
    }
  ]
}
```

`minecraftdiscordlink/stats.json` (for the unique player placeholders):

```json
{
  "total": ["069a79f4-44e9-4726-a5be-fca90e38aaf5"],
  "today": { "date": "2026-10-28", "players": ["069a79f4-44e9-4726-a5be-fca90e38aaf5"] }
}
```

Further files: `moderation.log`, `mutes.json`, `report.json`,
`subscriptions.json` and `state.json` (sections 4b–4e).

Pending link codes are kept in memory only and are lost on restart.

## 5a. Account linking and whitelist details

- Codes: six characters without easily confused ones, case-insensitive.
- One Discord account is linked to at most one Minecraft account and vice versa.
- Whitelist on login (section 4b): while the whitelist is on, operators and
  players on the vanilla whitelist may always join, linked members with a
  whitelisted role too. Bans take priority and keep their message; the
  maintenance mode overrides the whitelist.
- Kick messages: unlinked players get the message with a link code (only
  with `features.linking.enabled`); linked players without a whitelisted role, and
  everyone while Discord is unavailable, get "You are not whitelisted on
  this server."
- Mention suggestions: players get the usernames of all Discord members as
  chat suggestions (`@` + Tab), updated when members join or leave.

## 5b. Linked role

- Members with a linked account get the linked role, it is removed on unlink.
  The role always reflects the current links.
- On server start and on config reload, all members are synchronized: linked
  members get the role, all others lose it. `linked_role` can be changed by a
  reload.
- Changes in Discord are corrected immediately: a deleted `auto` role is
  created again, the role is removed from members without a link and given back
  to linked members.
- The ID of the role created for `auto` is stored in
  `minecraftdiscordlink/state.json`, so the bot never deletes a role it did not
  create.
- `auto`: the bot creates the role "Linked Account". If `linked_role` is changed from `auto` to something else, the
  bot deletes the role it created.
- Requires the bot permission *Manage Roles*; the role must be below the bot's
  highest role.

## 6. Project structure

| Item | Value |
|---|---|
| Mod name | MinecraftDiscordLink |
| Mod ID | `minecraftdiscordlink` |
| Java package | `de.moritzrohleder.minecraftdiscordlink` |
| Java | 21 |
| Modules | `common`, `fabric`, `neoforge` |
| NeoForge | 21.1.x |
| Fabric | Fabric Loader + Fabric API |
