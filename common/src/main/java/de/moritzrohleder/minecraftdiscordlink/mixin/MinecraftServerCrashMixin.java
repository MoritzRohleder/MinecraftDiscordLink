package de.moritzrohleder.minecraftdiscordlink.mixin;

import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import net.minecraft.CrashReport;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports a crash of the server to the monitoring channel.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerCrashMixin {

    /**
     * Called after the crash report was saved, before the server stops.
     *
     * @param crash the crash report
     * @param ci    the callback info
     */
    @Inject(method = "onServerCrash", at = @At("HEAD"))
    private void minecraftdiscordlink$onServerCrash(CrashReport crash, CallbackInfo ci) {
        MinecraftDiscordLink.onServerCrash(crash);
    }
}
