package de.moritzrohleder.minecraftdiscordlink;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

// Only loaded on dedicated servers
@Mod(value = Constants.MOD_ID, dist = Dist.DEDICATED_SERVER)
public class MinecraftDiscordLinkNeoForge {

    public MinecraftDiscordLinkNeoForge(IEventBus eventBus) {
        MinecraftDiscordLink.init();
    }
}
