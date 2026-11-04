package de.moritzrohleder.minecraftdiscordlink;

import de.moritzrohleder.minecraftdiscordlink.command.DiscordCommand;
import de.moritzrohleder.minecraftdiscordlink.platform.ForgePlatformHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;

/**
 * Forge entry point. The mod only works on dedicated servers; on a client it does nothing.
 */
@Mod(Constants.MOD_ID)
public class MinecraftDiscordLinkForge {

    /**
     * Initializes the mod and registers the permission node, the commands and the server lifecycle and player
     * listeners.
     */
    public MinecraftDiscordLinkForge() {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            Constants.LOG.info("{} only works on dedicated servers and is inactive", Constants.MOD_NAME);
            return;
        }
        MinecraftDiscordLink.init();
        MinecraftForge.EVENT_BUS.addListener((PermissionGatherEvent.Nodes event) ->
                event.addNodes(ForgePlatformHelper.ADMIN));
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                DiscordCommand.register(event.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent event) ->
                MinecraftDiscordLink.onServerStarting(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) ->
                MinecraftDiscordLink.onServerStarted(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                MinecraftDiscordLink.onServerStopping(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                MinecraftDiscordLink.onPlayerJoin(player);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                MinecraftDiscordLink.onPlayerLeave(player);
            }
        });
    }
}
