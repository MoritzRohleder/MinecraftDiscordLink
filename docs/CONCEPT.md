# MCDiscordChat – Concept

> Status: **work in progress**. This document records what has been decided and
> lists open points. All decisions are made jointly.

## Idea

A purely server-side Minecraft mod that connects a Minecraft server with a
Discord server. Using a Discord app (bot), at least two Discord channels are
connected:

- **Chat channel** – bidirectional bridge between the Minecraft chat and Discord.
- **Console channel** – live server console in Discord; authorized users can run commands.

## General conventions

- Everything in the repository (code, docs, config, comments) is written in **English**.

## Decisions

### Platform

| Topic | Decision |
|---|---|
| Minecraft version | Start with **1.21.1** |
| Loaders | **NeoForge** and **Fabric** |
| Build structure | **MultiLoader template** (`common` / `fabric` / `neoforge`), no runtime dependency such as Architectury. Each loader can be updated to a new Minecraft version and released independently; more loaders can be added later. |
| Discord library | **JDA** (bundled into the mod jar) |
| Language | **English only** (server-side mod, clients do not have the mod's language files) |
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

### Whitelist via Discord

- One or more role IDs can be configured. Members with one of these roles are
  whitelisted on the Minecraft server.
- If no role is configured, this feature is disabled.

### Account linking

- Linking Discord ↔ Minecraft accounts is **optional**.
- Flow: `/discord link` in Minecraft → the bot sends a code via **Discord DM** →
  the player enters the code in Minecraft. Access to both accounts is required.
- Links are stored in a simple **JSON file** in the server directory.
- Uses: show Minecraft names, mention suggestions in the Minecraft chat
  (custom chat completions, possible server-side since 1.19).

### Discord slash commands

- `/online`, `/status`, `/whois <player>`, `/playtime <player>`
- `/link` / `/unlink`: to be discussed together with account linking.

## Open points

- [ ] Emoji conversion (`:name:` ↔ Unicode) – include if the effort is low
- [ ] Combining permissions of multiple roles (exact rule)
- [ ] Account linking: how the bot knows which Discord user to DM, DM failures, code expiry
- [ ] Display name of linked Discord users in Minecraft
- [ ] Whitelist via Discord: relation to linking and to the vanilla whitelist
- [ ] Slash commands: who may use them, visibility of responses
- [ ] Final list of topic placeholders (spec)
