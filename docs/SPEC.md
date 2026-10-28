# MCDiscordChat – Specification

> Status: **draft**. Based on [CONCEPT.md](CONCEPT.md). Sections marked
> *proposal* are not agreed yet.

## 1. Configuration (*proposal*)

File: `config/mcdiscordchat.toml`. Created with defaults and comments on first start.

```toml
[discord]
# Bot token from the Discord Developer Portal. Keep it secret!
token = ""

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

# One entry per authorized Discord role or user.
# mode = "whitelist": only the listed commands are allowed
# mode = "blacklist": everything except the listed commands is allowed
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

## 2. Topic placeholders (*proposal*)

| Placeholder | Meaning |
|---|---|
| `{online}` | Players currently online |
| `{max}` | Max players |
| `{unique_session}` | Distinct players since server start |
| `{unique_today}` | Distinct players today |
| `{unique_total}` | Distinct players ever (persisted) |
| `{uptime}` | Time since server start |
| `{last_updated}` | Time of this topic update (`HH:mm`) |
| `{version}` | Minecraft version |
| `{motd}` | Server MOTD |
