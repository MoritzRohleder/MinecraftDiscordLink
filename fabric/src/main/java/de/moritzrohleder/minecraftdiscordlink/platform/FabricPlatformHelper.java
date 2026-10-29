package de.moritzrohleder.minecraftdiscordlink.platform;

import de.moritzrohleder.minecraftdiscordlink.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

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
}
