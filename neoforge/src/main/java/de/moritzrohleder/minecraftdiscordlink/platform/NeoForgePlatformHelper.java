package de.moritzrohleder.minecraftdiscordlink.platform;

import de.moritzrohleder.minecraftdiscordlink.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * {@link IPlatformHelper} implementation for NeoForge.
 */
public class NeoForgePlatformHelper implements IPlatformHelper {

    /**
     * Created by the mod loader.
     */
    public NeoForgePlatformHelper() {
    }

    @Override
    public String getPlatformName() {
        return "NeoForge";
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
}
