package de.moritzrohleder.minecraftdiscordlink;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class MinecraftDiscordLinkNeoForge {

    public MinecraftDiscordLinkNeoForge(IEventBus eventBus) {
        MinecraftDiscordLink.init();
    }
}
