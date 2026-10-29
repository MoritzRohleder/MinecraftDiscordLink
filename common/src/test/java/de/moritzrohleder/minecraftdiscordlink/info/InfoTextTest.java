package de.moritzrohleder.minecraftdiscordlink.info;

import de.moritzrohleder.minecraftdiscordlink.linking.Linking.WhoisEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InfoTextTest {

    @Test
    void formatsOnlinePlayers() {
        ServerInfo.Online online = new ServerInfo.Online(List.of("Alex", "Steve"), 20);

        assertEquals("**2/20** players online: **Alex**, **Steve**", InfoText.online(online, true));
        assertEquals("2/20 players online: Alex, Steve", InfoText.online(online, false));
        assertEquals("0/20 players online", InfoText.online(new ServerInfo.Online(List.of(), 20), false));
    }

    @Test
    void formatsStatus() {
        ServerInfo.Status status = new ServerInfo.Status("3h 0m", 19.96, 4.21, 2, 20, "1.21.1");

        assertEquals("Uptime: 3h 0m\nTPS: 20.0\nMSPT: 4.2\nPlayers: 2/20\nVersion: 1.21.1",
                InfoText.status(status, false));
    }

    @Test
    void formatsWhoisForBothSides() {
        List<WhoisEntry> entries = List.of(
                new WhoisEntry(Optional.of("Steve"), OptionalLong.of(123L), Optional.of("steve_dc")),
                new WhoisEntry(Optional.of("Alex"), OptionalLong.empty(), Optional.empty()),
                new WhoisEntry(Optional.empty(), OptionalLong.of(456L), Optional.of("alex_dc")));

        assertEquals("Steve is linked to @steve_dc\nAlex is not linked\n@alex_dc is not linked",
                InfoText.whois("x", entries, false));
        assertEquals("**Steve** is linked to <@123>\n**Alex** is not linked\n<@456> is not linked",
                InfoText.whois("x", entries, true));
    }

    @Test
    void formatsPlaytime() {
        assertEquals("Steve has played 12h 34m",
                InfoText.playtime("Steve", Optional.of(Duration.ofMinutes(12 * 60 + 34)), false));
        assertEquals("Steve has not played on this server yet.", InfoText.playtime("Steve", Optional.empty(), false));
    }
}
