package de.moritzrohleder.minecraftdiscordlink.platform;

import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/**
 * Access to loader specific implementations from the common code.
 * <p>
 * The implementations are found with Java's {@link ServiceLoader}: each loader project registers its implementation
 * in {@code META-INF/services/<fully qualified interface name>}.
 */
public final class Services {

    /** Information about and access to the mod loader. */
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {
    }

    /**
     * Loads the implementation of a service for the current loader.
     *
     * @param clazz the service interface
     * @param <T>   type of the service
     * @return the implementation
     * @throws NullPointerException if no implementation is registered
     */
    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
