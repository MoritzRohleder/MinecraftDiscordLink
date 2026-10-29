package de.moritzrohleder.minecraftdiscordlink.mixin;

import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * Forwards chat messages broadcast to all players: player chat, {@code /say} and {@code /me}. Private messages
 * ({@code /msg}, {@code /teammsg}) are not broadcast and therefore not forwarded.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    /**
     * Called before a chat message is sent to all players.
     *
     * @param message the message
     * @param filter  decides per player whether the message is filtered
     * @param sender  the sending player, {@code null} for the console and command blocks
     * @param bound   the chat type and the sender's name
     * @param ci      the callback info
     */
    @Inject(
            method = "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Ljava/util/function/Predicate;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            at = @At("HEAD"))
    private void minecraftdiscordlink$onBroadcastChatMessage(PlayerChatMessage message,
                                                             Predicate<ServerPlayer> filter,
                                                             @Nullable ServerPlayer sender, ChatType.Bound bound,
                                                             CallbackInfo ci) {
        MinecraftDiscordLink.onChatMessage(message, sender, bound);
    }
}
