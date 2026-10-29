package de.moritzrohleder.minecraftdiscordlink.mixin;

import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards player deaths.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    /**
     * Called when a player dies, before the death message is shown and the combat tracker is reset.
     *
     * @param source the cause of death
     * @param ci     the callback info
     */
    @Inject(method = "die", at = @At("HEAD"))
    private void minecraftdiscordlink$onDie(DamageSource source, CallbackInfo ci) {
        MinecraftDiscordLink.onPlayerDeath((ServerPlayer) (Object) this);
    }
}
