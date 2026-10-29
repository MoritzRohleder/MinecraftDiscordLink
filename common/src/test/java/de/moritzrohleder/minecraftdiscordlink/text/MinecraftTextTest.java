package de.moritzrohleder.minecraftdiscordlink.text;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftTextTest {

    @Test
    void appliesFormattingCodesAndInsertsTextLiterally() {
        Component component = MinecraftText.discordMessage("&9[Discord]&r {name}: &l{message}", "Alex", "hi &c{name}");

        assertEquals("[Discord] Alex: hi &c{name}", component.getString());
        List<Component> parts = component.getSiblings();
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLUE), parts.get(0).getStyle().getColor());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE), parts.get(1).getStyle().getColor());
        assertTrue(parts.getLast().getStyle().isBold());
    }

    @Test
    void usesWhiteWithoutCodes() {
        Component component = MinecraftText.discordMessage("{name}: {message}", "Alex", "hi");

        component.getSiblings().forEach(part ->
                assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE), part.getStyle().getColor()));
    }
}
