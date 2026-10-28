# MCDiscordChat – Konzept

> Status: **in Arbeit**. Dieses Dokument hält fest, was bereits entschieden ist,
> und listet offene Punkte. Entscheidungen werden gemeinsam getroffen.

## Idee

Eine rein serverseitige Minecraft-Mod, die einen Minecraft-Server mit einem
Discord-Server verbindet. Über eine Discord-App (Bot) werden mindestens zwei
Discord-Kanäle angebunden:

- **Ingame-Chat-Kanal** – bidirektionale Brücke zwischen Minecraft-Chat und Discord.
- **Konsolen-Kanal** – Live-Konsole in Discord, berechtigte Nutzer können Befehle ausführen.

## Entschiedene Punkte

### Rahmen

| Thema | Entscheidung |
|---|---|
| Minecraft-Version | Start mit **1.21.1** |
| Loader | **NeoForge** und **Fabric** |
| Build-Struktur | **MultiLoader-Template** (`common` / `fabric` / `neoforge`), keine Laufzeit-Abhängigkeit wie Architectury. Loader können unabhängig voneinander auf neue MC-Versionen gehoben und released werden; weitere Loader sind später ergänzbar. |
| Discord-Library | **JDA** (wird in die Mod-Jar eingebettet) |
| Sprache | Englisch als Basis, Texte über **Translation Keys** + Sprachdateien |
| Lizenz | GPL-3.0 (bereits im Repo) |

### Discord-Anbindung

- Authentifizierung über den **Bot-Token** (nicht nur die Application-ID).
- Discord-Rate-Limits werden berücksichtigt (Topic-Updates, Nachrichten-Bündelung).

### Ingame-Chat-Kanal

**Minecraft → Discord**

- Gespiegelt werden: öffentliche Chat-Nachrichten, Join/Leave, Tode,
  Advancements, Serverstart/-stopp.
- Darstellung über **Webhook** (Spielername + Skin-Kopf als Avatar).
- `@everyone` / `@here` sind gesperrt; Mentions einzelner Nutzer sind erlaubt
  (Name muss korrekt geschrieben sein).
- Emojis werden als `:name:` geschrieben und in Discord korrekt dargestellt.

**Discord → Minecraft**

- Format: Präfix + Discord-Name + Text.
- Anhänge werden nicht dargestellt (Empfehlung: Anhänge im Kanal deaktivieren).
- Emojis und Mentions werden als Klartext dargestellt (`:emoji:`, `@name`).
- Antworten (Replies) werden wie normale Nachrichten dargestellt.

**Kanalbeschreibung (Topic)**

- Regelmäßige Aktualisierung, Intervall **10 Minuten**.
- Zeitpunkt der letzten Aktualisierung wird angezeigt (z. B. `Last updated: hh:mm`).
- Inhalt **frei konfigurierbar** über Platzhalter, z. B.
  `{online}/{max} | {unique} players | Uptime {uptime}`.
- Uptime = Zeit seit dem letzten Serverstart.

### Konsolen-Kanal

- Die gesamte Konsolenausgabe wird gespiegelt (Filter ggf. später).
- Ausgabe wird gebündelt und als **formatierte Nachricht** gesendet (kein Codeblock).
- Berechtigung zum Ausführen von Befehlen über **User-IDs und Rollen-IDs** in der Config.
- Pro berechtigter Rolle / User-ID ist eine **Whitelist oder Blacklist** von
  Befehlen konfigurierbar.

### Weitere Features

- **Account-Linking** Discord ↔ Minecraft ist gewünscht. Nutzen u. a.:
  Minecraft-Name in Minecraft anzeigen, Mention-Vorschläge im Minecraft-Chat.
- **Slash-Commands** in Discord, z. B. `/online`.

## Offene Punkte

Siehe aktuelle Diskussion – wird nach Klärung oben eingetragen.

- [ ] „Verschiedene Accounts“: Zeitraum / welche Platzhalter-Varianten
- [ ] Konkrete Gestaltung der formatierten Konsolen-Nachricht
- [ ] Webhook: automatisch durch den Bot anlegen oder URL in der Config
- [ ] Übersetzungen bei einer rein serverseitigen Mod (Sprache pro Server?)
- [ ] Emoji-Umwandlung (`:name:` ↔ Unicode) und Mention-Auflösung
- [ ] Datenschutz: IP-Adressen in der Konsolenausgabe
- [ ] Mehrere Rollen pro Nutzer: wie werden Whitelist/Blacklist kombiniert
- [ ] Account-Linking: Ablauf, Speicherung, Anzeige
- [ ] Umfang der Slash-Commands
- [ ] Whitelist per Discord (ja/nein)
