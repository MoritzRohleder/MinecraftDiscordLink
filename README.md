# MinecraftDiscordLink

A server-side Minecraft mod that links a Minecraft server with a Discord server.
Works on **NeoForge** and **Fabric** (Minecraft 1.21.1) and only needs to be
installed on the dedicated server – players don't need it.

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
- **Whitelist via Discord** – members with configured roles may join, in
  addition to the vanilla whitelist. Unlinked players get a link code when
  they try to join.
- **Slash commands** – `/mc online`, `/mc status`, `/mc whois`,
  `/mc playtime` and more.

## Installation

1. Put the jar for your loader into the server's `mods` folder:
   `minecraftdiscordlink-neoforge-<version>.jar` or
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
   https://discord.com/oauth2/authorize?client_id=APPLICATION_ID&scope=bot+applications.commands&permissions=805391440
   ```

   | Permission | Used for |
   |---|---|
   | View Channels, Send Messages, Embed Links | All messages |
   | Read Message History, Add Reactions | ✅ reaction on console commands |
   | Manage Webhooks | Chat messages as the player (`chat.webhook = "auto"`) |
   | Manage Channels | Channel topic of the chat channel |
   | Manage Roles | Linked role (`linking.linked_role`) |

   Manage Webhooks and Manage Channels can also be granted on the chat channel
   only. The bot's role must be above the linked role.
5. Create a chat channel and a console channel. Make the console channel
   visible only to admins (and the bot). Enable *Developer Mode* in Discord
   (Settings → Advanced), right-click the channels → *Copy Channel ID*, and put
   the IDs into `chat.channel_id` and `console.channel_id`.

## Configuration

All options are documented in the generated
`config/minecraftdiscordlink.toml`. Highlights:

| Option | Default | Meaning |
|---|---|---|
| `discord.token` | – | Bot token |
| `discord.status.*` | `playing`, every 5 min | Bot status text with placeholders |
| `chat.channel_id` | – | Chat channel |
| `chat.webhook` | `"auto"` | `"auto"`, a webhook URL or `""` for plain bot messages |
| `chat.minecraft_format` | `&9[Discord]&r {name}: {message}` | Discord messages in Minecraft, `&` formatting codes allowed |
| `chat.events.*` | all on | Which events are sent |
| `chat.topic.*` | every 10 min | Channel topic with placeholders |
| `console.channel_id` | – | Console channel |
| `[[console.permissions]]` | none | Who may run which commands (see below) |
| `whitelist.role_ids` | `[]` | Roles whitelisted on the server; empty = off |
| `linking.linked_role` | `"auto"` | Role for linked members: `"auto"`, a role ID or `""` |

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

`/discord reload` applies all changes except the bot token and the channel IDs,
which need a restart. Invalid values are logged as errors and replaced by their
default.

## Commands

**Minecraft**

| Command | Who |
|---|---|
| `/discord link <discordname>` – get a link code by DM | everyone |
| `/discord confirm <code>` – complete the link | everyone |
| `/discord unlink` | everyone |
| `/discord whois <name>` | everyone |
| `/discord admin link <player> <discordname>` | admins |
| `/discord reload` | admins |

Admins are operators of level 4 or players with the permission node
`minecraftdiscordlink.admin` (e.g. with LuckPerms).

**Discord**

| Command | Who |
|---|---|
| `/mc online`, `/mc status`, `/mc whois <name>`, `/mc playtime <player>` | everyone |
| `/mc link <code>` – link with the code from the kick message | everyone |
| `/mc unlink` | everyone |
| `/mcadmin link <player> <user>`, `/mcadmin reload` | members with *Administrator* (adjustable in the server's integration settings) |

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

The jars are written to `fabric/build/libs` and `neoforge/build/libs`.
GitHub Actions builds both on every push to `master`.

Concept and specification: [docs/CONCEPT.md](docs/CONCEPT.md),
[docs/SPEC.md](docs/SPEC.md).

## License

GPL-3.0
