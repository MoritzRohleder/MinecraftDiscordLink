package de.moritzrohleder.minecraftdiscordlink.console;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleFormatTest {

    @Test
    void escapesMarkdownAndRemovesColorCodes() {
        String line = ConsoleFormat.line(0, "INFO", "\u001B[32m<_Steve_> **hi**\u001B[0m \u00A7aGreen");

        assertTrue(line.matches("`\\d\\d:\\d\\d:\\d\\d` \\*\\*INFO\\*\\* .*"), line);
        assertTrue(line.endsWith("<\\_Steve\\_> \\*\\*hi\\*\\* Green"), line);
    }

    @Test
    void joinsLinesIntoMessages() {
        String line = "x".repeat(900);

        List<String> messages = ConsoleFormat.messages(List.of(line, line, line, "y".repeat(4500)));

        assertEquals(List.of(line + "\n" + line, line, "y".repeat(2000), "y".repeat(2000), "y".repeat(500)),
                messages);
        messages.forEach(message -> assertTrue(message.length() <= ConsoleFormat.MAX_MESSAGE_LENGTH));
    }
}
