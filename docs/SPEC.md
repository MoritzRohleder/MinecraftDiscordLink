# MCDiscordChat – Specification

> Status: **draft**. Based on [CONCEPT.md](CONCEPT.md). Sections marked
> *proposal* are not agreed yet.

## Conventions

- Placeholders in all configurable texts use curly braces: `{name}`.

## 1. Configuration

File: `config/mcdiscordchat.toml`. Created with defaults and comments on first start.
Can be reloaded at runtime (see section 3).

```toml
[discord]
# Bot token from the Discord Developer Portal. Keep it secret!
token = ""

[discord.status]
enabled = true
# Minutes between status updates
interval_minutes = 1
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

## 3. Commands (*proposal*)

### Minecraft

| Command | Who | Description |
|---|---|---|
| `/discord link <discordname>` | everyone | Send link code via DM |
| `/discord confirm <code>` | everyone | Complete link |
| `/discord unlink` | everyone | Remove own link |
| `/discord admin link <player> <discordname>` | admin | Link accounts manually |
| `/discord reload` | admin | Reload config |

Admin = OP level 4 **or** permission node `mcdiscordchat.admin`.

### Discord (slash commands)

| Command | Who | Description |
|---|---|---|
| `/online` | everyone | List online players |
| `/status` | everyone | Uptime, TPS/MSPT, player count, version |
| `/whois <player>` | everyone | Linked account or "not linked" |
| `/playtime <player>` | everyone | Play time from vanilla statistics |
| `/link <code>` | everyone | Complete link with a kick code |
| `/unlink` | everyone | Remove own link |
| `/admin link <player> <user>` | Administrator | Link accounts manually |
| `/reload` | Administrator | Reload config |

Administrator = Discord role with the *Administrator* permission. Enforced by
Discord itself (`default_member_permissions`); server admins can adjust it in
the server's integration settings.
