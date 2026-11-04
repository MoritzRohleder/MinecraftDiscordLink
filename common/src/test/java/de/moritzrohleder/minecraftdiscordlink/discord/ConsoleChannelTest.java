package de.moritzrohleder.minecraftdiscordlink.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleChannelTest {

    @Test
    void lowerCasesOnlyTheCommandName() {
        assertEquals("list", ConsoleChannel.withLowerCaseName("LIST"));
        assertEquals("say Hello World", ConsoleChannel.withLowerCaseName("Say Hello World"));
        assertEquals("whitelist add Test_Steve", ConsoleChannel.withLowerCaseName("WhiteList add Test_Steve"));
        assertEquals("", ConsoleChannel.withLowerCaseName(""));
    }
}
