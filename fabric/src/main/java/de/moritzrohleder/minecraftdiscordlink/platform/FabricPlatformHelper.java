package de.moritzrohleder.minecraftdiscordlink.platform;

import de.moritzrohleder.minecraftdiscordlink.platform.services.IPlatformHelper;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;

import java.nio.file.Path;

/**
 * {@link IPlatformHelper} implementation for Fabric.
 */
public class FabricPlatformHelper implements IPlatformHelper {

    /**
     * Created by the mod loader.
     */
    public FabricPlatformHelper() {
    }

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isAdmin(CommandSourceStack source) {
        // Falls back to the OP level without a permissions mod
        return Permissions.check(source, "minecraftdiscordlink.admin", 4);
    }
}
