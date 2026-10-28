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

## Later

- Automatic Modrinth upload when a GitHub release is published (once the
  first release is out and the Modrinth project exists).
