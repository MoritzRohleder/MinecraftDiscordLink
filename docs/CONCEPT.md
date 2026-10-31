# MinecraftDiscordLink – Concept

> Status: **complete**. This document records what has been decided and
> lists open points. All decisions are made jointly.

## Idea

A purely server-side Minecraft mod that connects a Minecraft server with a
Discord server. Using a Discord app (bot), at least two Discord channels are
connected:

- **Chat channel** – bidirectional bridge between the Minecraft chat and Discord.
- **Console channel** – live server console in Discord; authorized users can run commands.

## General conventions

- Everything in the repository (code, docs, config, comments) is written in **English**.
- Code is documented with **JavaDoc** as its technical documentation: every class, record, method and field
  gets a description of what it does and why, with `@param`, `@return` and `@throws` where applicable.

## Decisions

### Platform

| Topic | Decision |
|---|---|
| Minecraft version | Start with **1.21.1** |
| Loaders | **NeoForge** and **Fabric** |
| Build structure | **MultiLoader template** (`common` / `fabric` / `neoforge`), no runtime dependency such as Architectury. Each loader can be updated to a new Minecraft version and released independently; more loaders can be added later. |
| Discord library | **JDA** (bundled into the mod jar) |
| Language | **English only** (server-side mod, clients do not have the mod's language files). Configurable language files may be added later. |
| Config | Own config handling in `common`, format **TOML** via night-config (shipped by NeoForge, bundled into the Fabric jar). Same file on all loaders. |
| Emoji library | **JEmoji** (shortcode ↔ Unicode conversion incl. Discord aliases) |
| License | GPL-3.0 |

### Discord connection

- Authentication uses the **bot token** (the application ID alone is not enough).
- Required privileged intents: **Message Content** (read messages) and
  **Server Members** (resolve mentions, roles).
- Discord rate limits are respected (topic updates, batching of console output).

### Chat channel

**Minecraft → Discord**

- Mirrored events: public chat messages, join/leave, deaths, advancements,
  server start/stop.
- Webhook setting, three modes:
  - `auto` – the bot creates/uses a webhook itself (requires *Manage Webhooks* permission).
  - a webhook URL – entered manually.
  - empty – no webhook, plain bot messages.
- With a webhook, messages show the player name and the player's skin head as avatar.
- `@everyone` / `@here` are blocked. Mentions of individual users are allowed
  and are resolved by **username and server nickname**.

**Discord → Minecraft**

- Format: prefix + Discord name + text.
- Attachments are not shown (recommendation: disable attachments in the channel).
- Mentions are shown as plain text (`@name`).
- Emojis are converted in both directions: `:name:` in Minecraft becomes a real
  emoji in Discord; Unicode emojis from Discord are shown as `:name:` in Minecraft.
  Custom server emojis are shown as `:name:`.
- Display name of linked users is configurable with the placeholders `<mc>` and
  `<discord>`; default: `<mc> (<discord>)`.
- Replies are shown like normal messages.

**Channel topic**

- Updated every **10 minutes**, showing the time of the last update
  (e.g. `Last updated: hh:mm`).
- Content is **configurable via placeholders**, e.g.
  `{online}/{max} | {unique_total} players | Uptime {uptime}`.
- Several "unique players" placeholders are offered (e.g. since start, today, total).
- Uptime = time since the last server start.

### Console channel

- The entire console output is mirrored (filters may be added later).
  The channel should only be visible to people who may see the console anyway.
- Output is batched and sent as **Discord Markdown** formatted messages (no code block).
- Command execution is authorized by **user IDs and role IDs** in the config.
- A **whitelist or blacklist** of commands can be configured per role / user ID.
- Multiple roles: permissions are the **union** of everything the user's roles /
  user ID allow; an **explicit deny always wins**.

### Whitelist via Discord

- One or more role IDs can be configured. Members with one of these roles are
  whitelisted on the Minecraft server.
- If no role is configured, this feature is disabled.
- When enabled, **account linking becomes mandatory** for players who rely on it.
  Unlinked players are kicked on join with a link code in the kick message.
- The **vanilla whitelist stays active in addition**: a player may join if they
  are on the vanilla whitelist **or** have a whitelisted role.
- A player who loses the role and is not on the vanilla whitelist is **kicked immediately**.
- If Discord is unreachable, only the vanilla whitelist applies.

### Account linking

- Linking Discord ↔ Minecraft accounts is **optional** (mandatory only with the
  Discord whitelist enabled).
- Flow:
  1. `/discord link <discordname>` in Minecraft → the bot sends a code via **Discord DM**.
  2. The code is valid for **1 hour**. Replying `refresh` in the DM issues a new code.
  3. `/discord confirm <code>` in Minecraft completes the link.
- The Discord account is identified by its **username** (unique, DMs go there).
- The DM states which Minecraft player requested the link and contains the hint
  "If this wasn't you, ignore this message".
- If the DM cannot be delivered, the player gets an error message in Minecraft
  asking them to enable DMs.
- An already linked account cannot be linked again without unlinking first.
- Alternative flow for players kicked by the Discord whitelist: the kick message
  contains a code, which is entered in Discord via `/link <code>`. `/link <code>`
  only accepts such kick codes.
- **Unlinking** is possible from both sides (`/discord unlink` in Minecraft,
  `/unlink` in Discord) and removes the link for both accounts.
- Admins can link accounts manually via an admin command. There is no admin
  unlink command; admins can edit the JSON file instead.
- A player who was whitelisted only via role and unlinks is kicked.
- Links are stored in a simple **JSON file** in the server directory.
- Uses: show Minecraft names, mention suggestions in the Minecraft chat
  (custom chat completions, possible server-side since 1.19).

### Discord slash commands

- `/online`, `/status`, `/whois <player>`, `/playtime <player>`
- `/link <code>`, `/unlink` (account linking).
- Usable by everyone on the Discord server; responses are **public** in the channel.
- `/whois` for an unlinked player reports that the account is not linked.

## Open points

- [ ] Final list of topic placeholders (spec)

## Next features

Status: all implemented (see SPEC sections 4a–4f), role synchronization
excepted.

- **Monitoring channel** – a third, read-only/admin-only channel
  (`monitoring.channel_id`), shared by all servers:
  - Lag warning below a TPS threshold for a duration (default 15 TPS for
    60 s), all-clear when the lag is over, cooldown between warnings
    (default 5 minutes); all configurable.
  - Crash notice with the reason and the last log lines (a killed process
    cannot be reported).
  - Daily report with placeholders (default: distinct players, new players,
    peak players, total play time, top 3), default at midnight, time
    configurable.
  - Persistent status message, edited at a configurable interval, content
    configurable with placeholders (default: status and player list).
- **Bans across both sides** – opt-in, bidirectional; configurable whether a
  Minecraft ban bans or only kicks from Discord. Built together with the
  moderation commands; temporary bans are lifted on both sides when they
  expire.
- **Notifications** – subscription system: Discord users subscribe to events
  (server start, server stop, …) or to players coming online and get a DM.
- **Leaderboards** – configurable rankings from the vanilla statistics,
  default play time and deaths, each ranked highest or lowest first (fewest
  deaths is first). `/mc top <category>` shows a ranking, `/mc stats <player>`
  a player's value and rank in every category.
- **whois** – additionally shows "last online", recorded by the mod on every
  leave ("online now" for online players, "unknown" for older data).
- **Moderation commands** – kick, (temporary) ban, unban and whitelist from
  Discord, temporary bans also from Minecraft; a reason is required for
  kick, ban, unban and switching the whitelist on. Every moderation action,
  including vanilla commands, goes to the console, a moderation log file and
  optionally a moderation channel. See SPEC section 4b.
- **Maintenance mode** – bidirectional commands (Discord and Minecraft), a
  temporary whitelist: only operators and linked Discord admins may join.
  Whether it survives a restart is a parameter of the command. Shown in the
  channel topic.
- **Maintenance details** – "linked Discord admins" are members with the
  *Administrator* permission, as for `/mcadmin` (a configurable role may come
  later). Commands `/mcadmin maintenance on [keep_after_restart]` and
  `/discord maintenance on [persistent]`, default: ends with the restart.
  Players without access are kicked when it is switched on.
- **Notifications** – `/mc subscribe event:<…>`, `/mc subscribe player:<name>`,
  `/mc unsubscribe …`, `/mc subscriptions`; events at first: server start,
  server stop, maintenance on/off.
- **Several Minecraft servers in one Discord server** – one bot (same token on
  every server), a chat and a console channel per server, one shared
  monitoring channel.
  - Every server has a `server.name`, shown in the monitoring channel and in
    answers.
  - Slash commands get an optional `server` option; without it the server
    marked as primary answers.
  - Links are per server, each server has its own linked role (`auto`:
    "Linked Account (<server name>)").
  - The bot status is only set by servers with `discord.status.enabled`; for
    networks it is enabled on one server only, e.g. "Watching several
    servers" (no communication between servers).

Postponed: role synchronization (depends on the permission mod used).

## Later

- Automatic Modrinth upload when a GitHub release is published (once the
  first release is out and the Modrinth project exists).
- Console filters, translatable texts, more Minecraft versions, Discord replies
  and attachments in Minecraft, role colors, word filters.
