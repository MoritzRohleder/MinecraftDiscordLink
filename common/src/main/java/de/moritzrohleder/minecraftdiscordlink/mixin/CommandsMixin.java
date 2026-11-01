package de.moritzrohleder.minecraftdiscordlink.mixin;

import com.mojang.brigadier.ParseResults;
import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes who runs a command, so moderation actions of vanilla commands are logged with their moderator, and keeps
 * muted players from sending messages with commands.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

    /**
     * Called before a command runs.
     *
     * @param parse   the parsed command
     * @param command the command text
     * @param ci      the callback info
     */
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void minecraftdiscordlink$beforeCommand(ParseResults<CommandSourceStack> parse, String command,
                                                    CallbackInfo ci) {
        ServerPlayer player = parse.getContext().getSource().getPlayer();
        if (player != null && MinecraftDiscordLink.blockMutedCommand(player, command)) {
            // Muted players cannot send messages with commands either; the command does not run
            ci.cancel();
            return;
        }
        ModerationContext.enterCommand(parse.getContext().getSource().getTextName());
    }

    /**
     * Called after a command ran.
     *
     * @param parse   the parsed command
     * @param command the command text
     * @param ci      the callback info
     */
    @Inject(method = "performCommand", at = @At("RETURN"))
    private void minecraftdiscordlink$afterCommand(ParseResults<CommandSourceStack> parse, String command,
                                                   CallbackInfo ci) {
        ModerationContext.exitCommand();
    }
}
