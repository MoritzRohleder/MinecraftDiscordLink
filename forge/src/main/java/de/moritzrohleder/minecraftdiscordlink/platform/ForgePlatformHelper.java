package de.moritzrohleder.minecraftdiscordlink.platform;

import de.moritzrohleder.minecraftdiscordlink.platform.services.IPlatformHelper;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import java.nio.file.Path;

/**
 * {@link IPlatformHelper} implementation for Forge.
 */
public class ForgePlatformHelper implements IPlatformHelper {

    /**
     * The admin permission node {@code minecraftdiscordlink.admin}, OP level 4 by default. Registered in
     * {@code MinecraftDiscordLinkForge}.
     */
    public static final PermissionNode<Boolean> ADMIN = new PermissionNode<>(Constants.MOD_ID, "admin",
            PermissionTypes.BOOLEAN, (player, uuid, context) -> player != null && player.hasPermissions(4));

    /**
     * Created by the mod loader.
     */
    public ForgePlatformHelper() {
    }

    @Override
    public String getPlatformName() {
        return "Forge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isAdmin(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null ? PermissionAPI.getPermission(player, ADMIN) : source.hasPermission(4);
    }
}
