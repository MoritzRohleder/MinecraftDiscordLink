# MCDiscordChat – Specification

> Status: **draft**. Based on [CONCEPT.md](CONCEPT.md). Sections marked
> *proposal* are not agreed yet.

## Conventions

- Placeholders in all configurable texts use curly braces: `{name}`.
- Dates and times use the server's time zone.

## 1. Configuration

File: `config/mcdiscordchat.toml`. Created with defaults and comments on first start.
The generated file documents all available placeholders in its comments
(the references to section 2 below are only used in this spec).
Can be reloaded at runtime (see section 3).

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
minecraft_format = "[Discord] {name}: {message}"
# Name of linked users in Minecraft. Placeholders: {mc}, {discord}
linked_name_format = "{mc} ({discord})"
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
# Members with one of these roles are whitelisted. Empty = feature disabled.
role_ids = []

[linking]
# Validity of link codes in minutes
code_validity_minutes = 60
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
| `/discord reload` | admin | Reload config |

Admin = OP level 4 **or** permission node `mcdiscordchat.admin`.

### Discord (slash commands)

Two top-level commands: `/mc` for everyone and `/mcadmin` for administrators.

| Command | Who | Description |
|---|---|---|
| `/mc online` | everyone | List online players |
| `/mc status` | everyone | Uptime, TPS/MSPT, player count, version |
| `/mc whois <name>` | everyone | Linked account or "not linked" (Minecraft or Discord name) |
| `/mc playtime <player>` | everyone | Play time from vanilla statistics |
| `/mc link <code>` | everyone | Complete link with a kick code |
| `/mc unlink` | everyone | Remove own link |
| `/mcadmin link <player> <user>` | Administrator | Link accounts manually |
| `/mcadmin reload` | Administrator | Reload config |

`whois` searches Minecraft and Discord names. If a name matches different
people on both sides, both results are shown.

Administrator = Discord role with the *Administrator* permission. Enforced by
Discord itself on `/mcadmin` (`default_member_permissions`); server admins can adjust it in
the server's integration settings.

## 4. Messages (*proposal*)

### Minecraft → Discord (chat channel)

Chat messages are sent via webhook as the player (plain text). Events are sent
via webhook as **embeds** with a colored bar and an emoji. Event texts are not
configurable.

| Event | Embed | Color |
|---|---|---|
| Join | `:inbox_tray: **{player}** joined the game` | green |
| Leave | `:outbox_tray: **{player}** left the game` | red |
| Death | `:skull: ` + vanilla death message, e.g. `**Steve** was slain by Zombie` | black |
| Advancement | `:trophy: ` + vanilla wording, e.g. `**{player}** has made the advancement **[{advancement}]**` | gold |
| Server start | `:white_check_mark: Server started` | green |
| Server stop | `:octagonal_sign: Server stopped` | red |

Without a webhook (`webhook = ""`) the bot sends the same embeds itself.

Webhook avatar: player head from an external service. The URL is configurable
with the placeholders `{uuid}` and `{name}` (default service to be chosen).

### Discord → Minecraft

`[Discord] {name}: {message}` – prefix in blue, name and message in white.
Role colors are not used (may be added later).

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

| Command | Response |
|---|---|
| `online` | `**{online}/{max}** players online: Steve, Alex` |
| `status` | Uptime, TPS, MSPT, players, version – one per line |
| `whois` | `Steve is linked to @steve` / `Steve is not linked` |
| `playtime` | `Steve has played 12h 34m` |
