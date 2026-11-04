package de.moritzrohleder.minecraftdiscordlink.mixin;

import net.minecraft.server.players.StoredUserEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Gives access to the player or IP address of a ban or whitelist entry, which Minecraft keeps package-private.
 */
@Mixin(StoredUserEntry.class)
public interface StoredUserEntryAccessor {

    /**
     * Returns the user of the entry.
     *
     * @return the player's profile or the IP address, {@code null} for an invalid entry
     */
    @Invoker("getUser")
    Object minecraftdiscordlink$getUser();
}
