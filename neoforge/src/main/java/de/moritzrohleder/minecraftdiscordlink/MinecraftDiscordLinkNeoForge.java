package de.moritzrohleder.minecraftdiscordlink;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * NeoForge entry point. Only loaded on dedicated servers.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.DEDICATED_SERVER)
public class MinecraftDiscordLinkNeoForge {

    /**
     * Initializes the mod and registers the server lifecycle listeners.
     *
     * @param eventBus the mod event bus
     */
    public MinecraftDiscordLinkNeoForge(IEventBus eventBus) {
        MinecraftDiscordLink.init();
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) ->
                MinecraftDiscordLink.onServerStarting(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                MinecraftDiscordLink.onServerStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                MinecraftDiscordLink.onServerStopping(event.getServer()));
    }
}
