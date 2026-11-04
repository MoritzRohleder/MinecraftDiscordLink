package de.moritzrohleder.minecraftdiscordlink;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import de.moritzrohleder.minecraftdiscordlink.command.DiscordCommand;
import de.moritzrohleder.minecraftdiscordlink.platform.NeoForgePlatformHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * NeoForge entry point. Only loaded on dedicated servers.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.DEDICATED_SERVER)
public class MinecraftDiscordLinkNeoForge {

    /**
     * Initializes the mod and registers the permission node, the commands and the server lifecycle and player
     * listeners.
     *
     * @param eventBus the mod event bus
     */
    public MinecraftDiscordLinkNeoForge(IEventBus eventBus) {
        MinecraftDiscordLink.init();
        NeoForge.EVENT_BUS.addListener((PermissionGatherEvent.Nodes event) ->
                event.addNodes(NeoForgePlatformHelper.ADMIN));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                DiscordCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) ->
                MinecraftDiscordLink.onServerStarting(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                MinecraftDiscordLink.onServerStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                MinecraftDiscordLink.onServerStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                MinecraftDiscordLink.onPlayerJoin(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                MinecraftDiscordLink.onPlayerLeave(player);
            }
        });
    }
}
