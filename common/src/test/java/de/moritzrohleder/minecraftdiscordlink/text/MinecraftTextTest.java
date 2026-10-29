package de.moritzrohleder.minecraftdiscordlink.text;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftTextTest {

    @Test
    void insertsNameAndMessageAndColorsPrefix() {
        Component component = MinecraftText.discordMessage("[Discord] {name}: {message}", "Alex", "hi [Discord] {name}");

        assertEquals("[Discord] Alex: hi [Discord] {name}", component.getString());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLUE),
                component.getSiblings().getFirst().getStyle().getColor());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE), component.getStyle().getColor());
    }
}
