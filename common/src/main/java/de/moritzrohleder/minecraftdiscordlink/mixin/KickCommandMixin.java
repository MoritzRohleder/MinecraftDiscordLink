package de.moritzrohleder.minecraftdiscordlink.mixin;

import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.KickCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Logs kicks by the vanilla {@code /kick} command to the moderation log.
 */
@Mixin(KickCommand.class)
public abstract class KickCommandMixin {

    /**
     * Called before the players are kicked.
     *
     * @param source  the command source
     * @param players the players to kick
     * @param reason  the reason
     * @param cir     the callback info
     */
    @Inject(method = "kickPlayers", at = @At("HEAD"))
    private static void minecraftdiscordlink$onKick(CommandSourceStack source, Collection<ServerPlayer> players,
                                                    Component reason, CallbackInfoReturnable<Integer> cir) {
        for (ServerPlayer player : players) {
            MinecraftDiscordLink.onVanillaKick(source.getTextName(), player, reason.getString());
        }
    }
}
