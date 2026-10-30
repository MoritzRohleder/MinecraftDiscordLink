package de.moritzrohleder.minecraftdiscordlink.mixin;

import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Logs switching the vanilla whitelist on or off to the moderation log.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListWhitelistMixin {

    /**
     * Checks whether the whitelist is on.
     *
     * @return whether the whitelist is on
     */
    @Shadow
    public abstract boolean isUsingWhitelist();

    /**
     * Called before the whitelist is switched.
     *
     * @param on whether it is switched on
     * @param ci the callback info
     */
    @Inject(method = "setUsingWhiteList", at = @At("HEAD"))
    private void minecraftdiscordlink$onSetUsingWhiteList(boolean on, CallbackInfo ci) {
        if (on != isUsingWhitelist()) {
            MinecraftDiscordLink.onWhitelistSwitched(on);
        }
    }
}
