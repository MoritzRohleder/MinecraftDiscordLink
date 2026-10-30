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
Can be reloaded at runtime (see section 3). Changes to the bot token and channel
IDs require a server restart; everything else is applied on reload.

If the token or a channel ID is missing or invalid, the server starts normally,
the mod logs an **error** and the affected part stays inactive (no token: the
whole mod; one channel: only that feature).

```toml
[discord]
# Bot token from the Discord Developer Portal. Keep it secret!
token = ""

[discord.status]
enabled = true
# Activity type: "playing", "watching", "listening", "competing" or "custom"
type = "playing"
# Minutes between status updates
interval_minutes = 5
# Placeholders: see section 2
format = "{online}/{max} online"

[chat]
# ID of the chat channel
channel_id = ""
# "auto"  = the bot creates a webhook itself (needs "Manage Webhooks")
# URL     = use this webhook
# ""      = no webhook, plain bot messages
webhook = "auto"
# Discord -> Minecraft. Placeholders: {name}, {message}
minecraft_format = "&9[Discord]&r {name}: {message}"
# Name of linked users in Minecraft. Placeholders: {mc}, {discord}
linked_name_format = "{mc} ({discord})"
# Player head used as webhook avatar. Placeholders: {uuid}, {name}
avatar_url = "https://vzge.me/face/256/{uuid}"
# Minecraft -> Discord without webhook. Placeholders: {player}, {message}
discord_format = "**{player}**: {message}"

[chat.events]
join = true
leave = true
death = true
advancement = true
server_start = true
server_stop = true

[chat.topic]
enabled = true
# Minutes between topic updates (Discord allows ~2 changes per 10 min)
interval_minutes = 10
# Placeholders: see section 2
format = "{online}/{max} online | {unique_today} players today | Uptime: {uptime} | Last updated: {last_updated}"

[console]
# ID of the console channel
channel_id = ""
# Milliseconds between batched console messages
batch_interval_ms = 2000

# One entry per authorized Discord role or user (IDs are unique across both).
# mode = "whitelist": only the listed commands are allowed
# mode = "blacklist": everything except the listed commands is allowed
# An entry matches a command and all its subcommands:
#   "gamerule"                  -> every gamerule command
#   "gamerule doDaylightCycle"  -> only this subcommand
# Multiple matching entries: union of allowed commands, explicit denies win.
[[console.permissions]]
id = ""
mode = "blacklist"
commands = ["stop"]

[whitelist]
# Whitelist via Discord roles on or off; also switched by /mcadmin rolewhitelist
enabled = true
# Members with one of these roles are whitelisted. Empty = feature disabled.
role_ids = []

[linking]
# Validity of link codes in minutes
code_validity_minutes = 60
# Role for members with a linked account:
# "auto"  = the bot creates and uses a role named "Linked Account"
# role ID = use this role
# ""      = no role
linked_role = "auto"

[moderation]
# Channel for the moderation log, optional ("" = only the log file)
channel_id = ""

[moderation.ban_sync]
# Bans across Minecraft and Discord for linked accounts (opt-in)
enabled = false
# What a Minecraft ban does to the linked Discord account: "ban" or "kick"
discord_action = "ban"

[leaderboard]
# Number of players shown by /mc top (1-25)
size = 10

# One entry per ranked statistic (at most 25), written as scoreboard criterion.
# name:  shown in Discord, optional (default: the vanilla name of the statistic)
# order: "highest" (default) or "lowest" = the lowest value is ranked first
# With "categories = []" the leaderboard commands are not registered.
[[leaderboard.categories]]
stat = "minecraft.custom:minecraft.play_time"
name = "Play time"
order = "highest"

[[leaderboard.categories]]
stat = "minecraft.custom:minecraft.deaths"
name = "Deaths"
order = "lowest"
```

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
| `/discord rolewhitelist on <reason>` | admin | Switch the whitelist via Discord roles on |
| `/discord rolewhitelist off [reason]` | admin | Switch the whitelist via Discord roles off |
| `/discord rolewhitelist status` | admin | Show whether it is on and its roles |
| `/discord reload` | admin | Reload config |

Admin = OP level 4 **or** permission node `minecraftdiscordlink.admin`.

### Discord (slash commands)

Two top-level commands: `/mc` for everyone and `/mcadmin` for administrators.

| Command | Who | Description |
|---|---|---|
| `/mc online` | everyone | List online players |
| `/mc status` | everyone | Uptime, TPS/MSPT, player count, version |
| `/mc whois <name>` | everyone | Linked account or "not linked" (Minecraft or Discord name) |
| `/mc playtime <player>` | everyone | Play time from vanilla statistics |
| `/mc top <category>` | everyone | Best players of a leaderboard category (choices from the config) |
| `/mc stats <player>` | everyone | The player's value and rank in every leaderboard category |
| `/mc link <code>` | everyone | Complete link with a kick code |
| `/mc unlink` | everyone | Remove own link |
| `/mcadmin link <player> <user>` | Administrator | Link accounts manually |
| `/mcadmin reload` | Administrator | Reload config |
| `/mcadmin kick <player> <reason>` | Administrator | Kick an online player |
| `/mcadmin ban <player> <reason> [duration]` | Administrator | Ban a player, permanently without duration |
| `/mcadmin unban <player> <reason>` | Administrator | Lift a ban |
| `/mcadmin banlist` | Administrator | List the banned players with reason, source and end |
| `/mcadmin whitelist add <player> [reason]` | Administrator | Add a player to the vanilla whitelist |
| `/mcadmin whitelist remove <player> [reason]` | Administrator | Remove a player from the vanilla whitelist |
| `/mcadmin whitelist list` | Administrator | List the whitelisted players |
| `/mcadmin whitelist on <reason>` | Administrator | Switch the vanilla whitelist on (not the role whitelist) |
| `/mcadmin whitelist off [reason]` | Administrator | Switch the vanilla whitelist off |
| `/mcadmin rolewhitelist on <reason>` | Administrator | Switch the whitelist via Discord roles on |
| `/mcadmin rolewhitelist off [reason]` | Administrator | Switch the whitelist via Discord roles off |
| `/mcadmin rolewhitelist add <role> [reason]` | Administrator | Add a whitelisted role |
| `/mcadmin rolewhitelist remove <role> [reason]` | Administrator | Remove a whitelisted role |
| `/mcadmin rolewhitelist status` | Administrator | Show whether it is on and its roles |

`whois` searches Minecraft and Discord names. Minecraft names only count for
players who have played on the server or are linked. If a name matches
different people on both sides, both results are shown.

`reload` reloads everything except the bot token and the channel IDs; the
answer says so if one of them changed.

Administrator = Discord role with the *Administrator* permission. Enforced by
Discord itself on `/mcadmin` (`default_member_permissions`); server admins can adjust it in
the server's integration settings.

## 4. Messages

### Minecraft → Discord (chat channel)

Chat messages are sent via webhook as the player. Discord Markdown in chat
messages is allowed (`**bold**` is shown bold). Events are sent via webhook as
**embeds** with a colored bar and an emoji. Event texts are not configurable.

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

Webhook name: the player name. For linked players `linked_name_format` is used
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
- A reason is required for `kick`, `ban`, `tempban`, `unban`,
  `whitelist on` and `rolewhitelist on`; optional for the other whitelist
  commands. Vanilla commands (`/kick`, `/ban`, `/pardon`, …) stay unchanged:
  without a reason the log shows the vanilla default (`Banned by an
  operator.`) or `no reason given`.
- Durations: `30m`, `12h`, `7d`, `2w` and combinations such as `1d12h`.
  Without a duration a ban is permanent.
- Bans are vanilla bans in `banned-players.json`: the source is the
  moderator (`Discord @anedhel` for Discord, the player name or `Server`
  for Minecraft), the end of a temporary ban is the vanilla `expires` field.
- Banned online players are kicked with the vanilla ban message.
- `whitelist add` while the vanilla whitelist is off adds the player
  anyway; the answer says that the whitelist is off.
- Expired temporary bans are removed by the mod within a minute (and on
  login attempts and server start), logged as `ban expired`
  (`2026-11-06 15:40:30 | BAN EXPIRED | Steve`).
- Commands from the Discord console channel are logged with the Discord user
  as moderator (`Discord @anedhel (console)`).
- `moderation.channel_id` can be changed by a reload.

### Whitelist via Discord roles

- Active when `whitelist.enabled = true` and at least one role is in
  `whitelist.role_ids`. `rolewhitelist on` fails without roles.
- `rolewhitelist on|off|add|remove` write `whitelist.enabled` and
  `whitelist.role_ids` into the config file: the file is read again, only
  these values are changed, values and comments are kept (the file is
  rewritten in the generated format: comments at the end of a line move
  above the line, indentation and blank lines are unified). Other changes
  in the file are not applied until `reload`. A file that cannot be read is
  not touched; the command fails then.
- Switching it on and removing a role kick online players who no longer
  have access (operators and players on the vanilla whitelist keep access).

### Moderation log

Every moderation action is logged, from Discord commands as well as from
vanilla commands: kick, ban, temporary ban, unban, ban expired, IP ban and
IP unban (log only), whitelist add/remove/on/off, role whitelist
on/off/add/remove. Bans and whitelist changes by other mods are logged too;
the moderator is then unknown.

- Server console (INFO).
- `minecraftdiscordlink/moderation.log`, one line per action:
  `2026-10-30 15:40:12 | BAN | Steve | by Discord @anedhel | 7d (until 2026-11-06 15:40) | Griefing`
- The moderation channel if `moderation.channel_id` is set: one embed per
  action, kick yellow, ban red, unban and ban expired green, whitelist blue.

Admins in Minecraft see Discord actions like vanilla commands of other
operators: `[@anedhel: Banned Steve: Griefing]` (gray, italic).

### Ban sync (`moderation.ban_sync`)

Opt-in, only for linked accounts:

| Action | Effect on the other side |
|---|---|
| Minecraft ban (any source, also temporary) | `discord_action = "ban"`: the Discord account is banned; `"kick"`: it is kicked from the Discord server |
| Minecraft unban | the Discord ban is lifted (with `"ban"`) |
| Temporary ban expires | the Discord ban is lifted; logged as `ban expired` |
| Discord ban (by anyone, also via the Discord menu) | the Minecraft account is banned, source the Discord moderator, reason the Discord ban reason |
| Discord unban | the Minecraft ban is lifted |
| Kick (either side) | only logged, never synchronized |

- Discord has no temporary bans. The mod removes expired Minecraft bans
  itself (see above) and lifts the Discord ban then. If Discord is
  unavailable, the mod retries every minute while the server runs.
- An action caused by the sync does not trigger the sync again and is
  logged once.
- The Discord ban reason (audit log) is `Minecraft ban: <reason>`; no
  messages are deleted.
- Requires the bot permissions *Ban Members* and *Kick Members*; members
  above the bot's highest role cannot be banned (logged as error).

### Answers

Discord answers are embeds as usual: green on success, e.g.
`✅ Banned **Steve** for 7d (until <date>): Griefing`, red on errors, e.g.
`❌ Steve is not banned.` `banlist` and `whitelist list` show one player per
line (`**Steve** · by Discord @anedhel · until <date> · Griefing`).

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

Pending link codes are kept in memory only and are lost on restart.

## 5a. Account linking and whitelist details

- Codes: six characters without easily confused ones, case-insensitive.
- One Discord account is linked to at most one Minecraft account and vice versa.
- Discord whitelist on login: operators and players on the vanilla whitelist
  may always join. Bans take priority and keep their message.
- Kick messages: unlinked players get the message with a link code; linked
  players without a whitelisted role, and everyone while Discord is
  unavailable, get "You are not whitelisted on this server."
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
