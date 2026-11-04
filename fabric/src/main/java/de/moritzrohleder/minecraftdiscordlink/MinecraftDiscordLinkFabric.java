package de.moritzrohleder.minecraftdiscordlink;

import de.moritzrohleder.minecraftdiscordlink.command.DiscordCommand;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

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
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DiscordCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(MinecraftDiscordLink::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(MinecraftDiscordLink::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(MinecraftDiscordLink::onServerStopping);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                MinecraftDiscordLink.onPlayerJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MinecraftDiscordLink.onPlayerLeave(handler.getPlayer()));
    }
}
