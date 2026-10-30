package de.moritzrohleder.minecraftdiscordlink.mixin;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserWhiteList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets players whitelisted by a Discord role stay online when the vanilla whitelist is switched on or a player is
 * removed from it.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    /**
     * Replaces the vanilla whitelist check when kicking players who are not whitelisted.
     *
     * @param whitelist the vanilla whitelist
     * @param profile   the player
     * @return whether the player may stay
     */
    @Redirect(method = "kickUnlistedPlayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/UserWhiteList;isWhiteListed(Lcom/mojang/authlib/GameProfile;)Z"))
    private boolean minecraftdiscordlink$isWhitelisted(UserWhiteList whitelist, GameProfile profile) {
        return MinecraftDiscordLink.isWhitelisted(profile, whitelist.isWhiteListed(profile));
    }
}
