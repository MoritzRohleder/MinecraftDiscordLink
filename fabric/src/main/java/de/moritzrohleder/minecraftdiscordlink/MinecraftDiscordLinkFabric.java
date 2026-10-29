package de.moritzrohleder.minecraftdiscordlink;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Fabric entry point. Only loaded on dedicated servers.
 */
public class MinecraftDiscordLinkFabric implements DedicatedServerModInitializer {

    /**
     * Created by the mod loader.
     */
    public MinecraftDiscordLinkFabric() {
    }

    @Override
    public void onInitializeServer() {
        MinecraftDiscordLink.init();
        ServerLifecycleEvents.SERVER_STARTING.register(MinecraftDiscordLink::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(MinecraftDiscordLink::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(MinecraftDiscordLink::onServerStopping);
    }
}
