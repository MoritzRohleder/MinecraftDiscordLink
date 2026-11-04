package de.moritzrohleder.minecraftdiscordlink.info;

import de.moritzrohleder.minecraftdiscordlink.linking.Linking;
import de.moritzrohleder.minecraftdiscordlink.linking.Linking.WhoisEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InfoTextTest {

    @Test
    void formatsWhois() {
        List<WhoisEntry> entries = List.of(
                new WhoisEntry(Optional.empty(), Optional.of("Steve"), OptionalLong.of(123L), Optional.of("steve_dc"),
                        Optional.empty(), new Linking.LastOnline(true, Optional.empty())),
                new WhoisEntry(Optional.empty(), Optional.of("Alex"), OptionalLong.empty(), Optional.empty(),
                        Optional.empty(), Linking.LastOnline.UNKNOWN),
                new WhoisEntry(Optional.empty(), Optional.empty(), OptionalLong.of(456L), Optional.of("alex_dc"),
                        Optional.empty(), Linking.LastOnline.UNKNOWN));

        assertEquals("Steve is linked to @steve_dc (online now)\nAlex is not linked\n@alex_dc is not linked",
                InfoText.whois("x", entries));
        assertEquals("No Minecraft player or Discord member named x was found.", InfoText.whois("x", List.of()));
    }
}
