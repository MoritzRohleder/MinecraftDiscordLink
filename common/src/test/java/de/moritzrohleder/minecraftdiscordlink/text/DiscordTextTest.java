package de.moritzrohleder.minecraftdiscordlink.text;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordTextTest {

    @Test
    void convertsShortcodesToDiscordEmojis() {
        String text = DiscordText.emojisToDiscord("I died :skull: by a :Creeper: :unknown_emoji:",
                Map.of("creeper", "<:creeper:123456789012345678>"));

        assertEquals("I died 💀 by a <:creeper:123456789012345678> :unknown_emoji:", text);
    }

    @Test
    void convertsUnicodeEmojisToShortcodes() {
        assertEquals("I died :skull:", DiscordText.emojisToMinecraft("I died 💀"));
    }

    @Test
    void convertsResolvableMentions() {
        String text = DiscordText.mentionsToDiscord("hi @steve and @nobody, mail me at a@b.de",
                name -> name.equals("steve") ? OptionalLong.of(123456789012345678L) : OptionalLong.empty());

        assertEquals("hi <@123456789012345678> and @nobody, mail me at a@b.de", text);
    }

    @Test
    void sanitizesWebhookNames() {
        String name = DiscordText.webhookName("Steve (DiscordFan)");

        assertEquals("Steve (D\u200BiscordFan)", name);
        assertTrue(DiscordText.webhookName("x".repeat(100)).length() <= 80);
    }

    @Test
    void escapesAndHighlightsNames() {
        // A single underscore is no Markdown, a pair would make the name italic
        assertEquals("**Steve_Builder** was slain by Zombie",
                DiscordText.boldName("Steve_Builder was slain by Zombie", "Steve_Builder"));
        assertEquals("**\\_Steve\\_** was slain by Zombie",
                DiscordText.boldName("_Steve_ was slain by Zombie", "_Steve_"));
    }
}
