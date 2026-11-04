package de.moritzrohleder.minecraftdiscordlink.text;

import de.moritzrohleder.minecraftdiscordlink.config.ConfigManager;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.function.Function;

/**
 * Builds the configured texts of {@code [messages]}.
 */
public final class Messages {

    private Messages() {
    }

    /**
     * Builds a Discord text: emoji shortcodes such as {@code :skull:} become emojis, then the placeholders are
     * replaced. Placeholder values are inserted as given, so escape them for Discord Markdown where needed.
     *
     * @param text   selects the text from the messages config
     * @param values the placeholder values by name
     * @return the text
     */
    public static String discord(Function<ModConfig.Messages, String> text, Map<String, String> values) {
        return Placeholders.resolve(DiscordText.emojisToDiscord(text.apply(ConfigManager.get().messages()), Map.of()),
                values);
    }

    /**
     * Builds a Minecraft text with formatting codes; placeholder values are inserted as plain text.
     *
     * @param text   selects the text from the messages config
     * @param values the placeholder values by name
     * @return the text
     */
    public static Component minecraft(Function<ModConfig.Messages, String> text, Map<String, String> values) {
        return MinecraftText.formatted(text.apply(ConfigManager.get().messages()), values);
    }
}
