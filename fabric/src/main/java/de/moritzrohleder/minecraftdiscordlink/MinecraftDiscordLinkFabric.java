package de.moritzrohleder.minecraftdiscordlink;

import net.fabricmc.api.DedicatedServerModInitializer;

public class MinecraftDiscordLinkFabric implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        MinecraftDiscordLink.init();
    }
}
