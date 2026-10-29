package de.moritzrohleder.minecraftdiscordlink.mixin;

import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Forwards completed advancements.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    /** The player owning these advancements. */
    @Shadow
    private ServerPlayer player;

    /**
     * Called when an advancement has just been completed: vanilla grants the rewards exactly then.
     *
     * @param advancement the advancement
     * @param criterion   the criterion that completed it
     * @param cir         the callback info
     */
    @Inject(method = "award", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/advancements/AdvancementRewards;grant(Lnet/minecraft/server/level/ServerPlayer;)V",
            shift = At.Shift.AFTER))
    private void minecraftdiscordlink$onAdvancementCompleted(AdvancementHolder advancement, String criterion,
                                                             CallbackInfoReturnable<Boolean> cir) {
        MinecraftDiscordLink.onAdvancement(player, advancement);
    }
}
