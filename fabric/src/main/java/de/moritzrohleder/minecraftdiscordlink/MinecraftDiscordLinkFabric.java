package de.moritzrohleder.minecraftdiscordlink;

import net.fabricmc.api.ModInitializer;

public class MinecraftDiscordLinkFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        MinecraftDiscordLink.init();
    }
}
