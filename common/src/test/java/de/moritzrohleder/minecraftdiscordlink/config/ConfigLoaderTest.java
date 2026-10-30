package de.moritzrohleder.minecraftdiscordlink.config;

import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.ActivityType;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.PermissionMode;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig.WebhookMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void createsFileWithDefaults() throws IOException {
        Path file = dir.resolve("config.toml");

        ModConfig config = ConfigLoader.load(file);

        assertTrue(Files.exists(file));
        String content = Files.readString(file);
        assertTrue(content.contains("[discord.status]"));
        assertTrue(content.contains("# Bot token from the Discord Developer Portal. Keep it secret!"));
        assertTrue(content.contains("{unique_today}"));
        assertTrue(content.indexOf("[discord]") < content.indexOf("[chat]"));
        assertTrue(content.indexOf("[chat]") < content.indexOf("[console]"));

        assertTrue(config.discord().token().isEmpty());
        assertEquals(ActivityType.PLAYING, config.discord().status().type());
        assertEquals(5, config.discord().status().intervalMinutes());
        assertTrue(config.chat().channelId().isEmpty());
        assertEquals(WebhookMode.AUTO, config.chat().webhook().mode());
        assertEquals(10, config.chat().topic().intervalMinutes());
        assertEquals(2000, config.console().batchIntervalMs());
        assertTrue(config.console().permissions().isEmpty());
        assertFalse(config.whitelist().active());
        assertTrue(config.whitelist().enabled());
        assertTrue(config.moderation().channelId().isEmpty());
        assertFalse(config.moderation().banSync().enabled());
        assertEquals(ModConfig.DiscordAction.BAN, config.moderation().banSync().discordAction());
        assertEquals(60, config.linking().codeValidityMinutes());
        assertTrue(content.contains("[[leaderboard.categories]]"));
        assertEquals(10, config.leaderboard().size());
        assertEquals(List.of(
                new ModConfig.Category("minecraft.custom:minecraft.play_time", Optional.of("Play time"), false),
                new ModConfig.Category("minecraft.custom:minecraft.deaths", Optional.of("Deaths"), true)),
                config.leaderboard().categories());
        assertEquals(config, ConfigLoader.load(file));
    }

    @Test
    void updatesValuesKeepingTheRestOfTheFile() throws IOException {
        Path file = dir.resolve("config.toml");
        ConfigLoader.load(file);
        String original = Files.readString(file);
        Files.writeString(file, original.replace("code_validity_minutes = 60", "code_validity_minutes = 30"));

        assertTrue(ConfigLoader.update(file, java.util.Map.of("whitelist.enabled", false,
                "whitelist.role_ids", List.of("123456789012345678"))));

        ModConfig config = ConfigLoader.load(file);
        assertFalse(config.whitelist().enabled());
        assertEquals(List.of(123456789012345678L), config.whitelist().roleIds());
        assertEquals(30, config.linking().codeValidityMinutes());
        assertTrue(Files.readString(file).contains("# Bot token from the Discord Developer Portal. Keep it secret!"));
    }

    @Test
    void doesNotUpdateBrokenFile() throws IOException {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, "[discord\ntoken = ");

        assertFalse(ConfigLoader.update(file, java.util.Map.of("whitelist.enabled", false)));
        assertEquals("[discord\ntoken = ", Files.readString(file));
    }

    @Test
    void readsLeaderboardCategories() throws IOException {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, """
                [leaderboard]
                size = 99

                [[leaderboard.categories]]
                stat = "minecraft.mined:minecraft.diamond_ore"

                [[leaderboard.categories]]
                stat = "minecraft.custom:minecraft.deaths"
                name = "Deaths"
                order = "lowest"

                [[leaderboard.categories]]
                stat = "minecraft.custom:minecraft.deaths"
                order = "lowest"

                [[leaderboard.categories]]
                stat = "no stat"

                [[leaderboard.categories]]
                stat = "minecraft.custom:minecraft.jump"
                order = "sideways"
                """);

        ModConfig config = ConfigLoader.load(file);

        assertEquals(10, config.leaderboard().size());
        assertEquals(List.of(
                new ModConfig.Category("minecraft.mined:minecraft.diamond_ore", Optional.empty(), false),
                new ModConfig.Category("minecraft.custom:minecraft.deaths", Optional.of("Deaths"), true)),
                config.leaderboard().categories());
    }

    @Test
    void keepsValuesAndAddsMissingKeys() throws IOException {
        Path file = dir.resolve("config.toml");
        Files.writeString(file, """
                [discord]
                # My own comment
                token = "abc.def.ghi"

                [chat]
                channel_id = "123456789012345678"
                webhook = ""
                """);

        ModConfig config = ConfigLoader.load(file);

        assertEquals("abc.def.ghi", config.discord().token().orElseThrow());
        assertEquals(123456789012345678L, config.chat().channelId().orElseThrow());
        assertEquals(WebhookMode.NONE, config.chat().webhook().mode());

        String content = Files.readString(file);
        assertTrue(content.contains("My own comment"));
        assertTrue(content.contains("token = \"abc.def.ghi\""));
        assertTrue(content.contains("[chat.topic]"));
        assertTrue(content.contains("code_validity_minutes = 60"));
    }

    @Test
    void doesNotRewriteCompleteFile() throws IOException {
        Path file = dir.resolve("config.toml");
        ConfigLoader.load(file);
        String content = Files.readString(file) + "\n# Trailing comment\n";
        Files.writeString(file, content);

        ConfigLoader.load(file);

        assertEquals(content, Files.readString(file));
    }

    @Test
    void invalidValuesFallBackToDefaults() throws IOException {
        Path file = dir.resolve("config.toml");
        ConfigLoader.load(file);
        Files.writeString(file, Files.readString(file)
                .replace("type = \"playing\"", "type = \"dancing\"")
                .replace("interval_minutes = 10", "interval_minutes = 2")
                .replace("batch_interval_ms = 2000", "batch_interval_ms = \"fast\"")
                .replace("webhook = \"auto\"", "webhook = \"https://example.com/hook\"")
                .replace("join = true", "join = \"yes\""));

        ModConfig config = ConfigLoader.load(file);

        assertEquals(ActivityType.PLAYING, config.discord().status().type());
        assertEquals(10, config.chat().topic().intervalMinutes());
        assertEquals(2000, config.console().batchIntervalMs());
        assertEquals(WebhookMode.AUTO, config.chat().webhook().mode());
        assertTrue(config.chat().events().join());
    }

    @Test
    void readsWebhookUrlStatusTypeAndIds() throws IOException {
        Path file = dir.resolve("config.toml");
        ConfigLoader.load(file);
        Files.writeString(file, Files.readString(file)
                .replace("type = \"playing\"", "type = \"Custom\"")
                .replace("webhook = \"auto\"",
                        "webhook = \"https://discord.com/api/webhooks/123456789012345678/abc-DEF_123\"")
                .replace("[console]\nchannel_id = \"\"", "[console]\nchannel_id = \"invalid\"")
                .replace("role_ids = []", "role_ids = [\"223456789012345678\", \"nope\"]"));

        ModConfig config = ConfigLoader.load(file);

        assertEquals(ActivityType.CUSTOM, config.discord().status().type());
        assertEquals(WebhookMode.URL, config.chat().webhook().mode());
        assertEquals("https://discord.com/api/webhooks/123456789012345678/abc-DEF_123",
                config.chat().webhook().url().orElseThrow());
        assertTrue(config.console().channelId().isEmpty());
        assertEquals(List.of(223456789012345678L), config.whitelist().roleIds());
    }

    @Test
    void readsValidPermissionsAndSkipsInvalidOnes() throws IOException {
        Path file = dir.resolve("config.toml");
        ConfigLoader.load(file);
        Files.writeString(file, Files.readString(file) + """

                [[console.permissions]]
                id = "123456789012345678"
                mode = "blacklist"
                commands = ["/stop", "gamerule   doDaylightCycle"]

                [[console.permissions]]
                id = ""
                mode = "blacklist"
                commands = ["stop"]

                [[console.permissions]]
                id = "223456789012345678"
                mode = "sometimes"
                commands = ["say"]
                """);

        ModConfig config = ConfigLoader.load(file);

        assertEquals(List.of(new ModConfig.Permission(123456789012345678L, PermissionMode.BLACKLIST,
                        List.of("stop", "gamerule dodaylightcycle"))),
                config.console().permissions());
    }

    @Test
    void doesNotOverwriteBrokenFile() throws IOException {
        Path file = dir.resolve("config.toml");
        String broken = "[discord\ntoken = \"abc\"\n";
        Files.writeString(file, broken);

        ModConfig config = ConfigLoader.load(file);

        assertEquals(broken, Files.readString(file));
        assertTrue(config.discord().token().isEmpty());
    }
}
