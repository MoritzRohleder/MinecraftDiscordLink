package de.moritzrohleder.minecraftdiscordlink.mixin;

import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * Applies the whitelist via Discord roles on login.
 * <p>
 * Banned players keep their ban message. Everyone else who is not whitelisted by the Discord whitelist is refused
 * with the mod's kick message (with a link code for unlinked players). The vanilla whitelist check is replaced, so
 * players whitelisted only by a Discord role may join although the vanilla whitelist is on.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {

    /**
     * Refuses players the Discord whitelist does not let in, after the ban checks.
     *
     * @param address the player's address
     * @param profile the player's profile
     * @param cir     the callback info; its return value is the kick message
     */
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void minecraftdiscordlink$checkDiscordWhitelist(SocketAddress address, GameProfile profile,
                                                            CallbackInfoReturnable<Component> cir) {
        PlayerList playerList = (PlayerList) (Object) this;
        if (playerList.getBans().isBanned(profile) || playerList.getIpBans().isBanned(address)) {
            return;
        }
        Component kickMessage = MinecraftDiscordLink.checkLogin(profile);
        if (kickMessage != null) {
            cir.setReturnValue(kickMessage);
        }
    }

    /**
     * Replaces the vanilla whitelist check while the Discord whitelist is active.
     *
     * @param playerList the player list
     * @param profile    the player's profile
     * @return whether the player is whitelisted
     */
    @Redirect(method = "canPlayerLogin", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;isWhiteListed(Lcom/mojang/authlib/GameProfile;)Z"))
    private boolean minecraftdiscordlink$isWhitelisted(PlayerList playerList, GameProfile profile) {
        return MinecraftDiscordLink.isWhitelisted(profile, playerList.isWhiteListed(profile));
    }
}
