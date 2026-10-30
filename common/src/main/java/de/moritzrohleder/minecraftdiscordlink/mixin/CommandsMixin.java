package de.moritzrohleder.minecraftdiscordlink.mixin;

import com.mojang.brigadier.ParseResults;
import de.moritzrohleder.minecraftdiscordlink.moderation.ModerationContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes who runs a command, so moderation actions of vanilla commands are logged with their moderator.
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
    @Inject(method = "performCommand", at = @At("HEAD"))
    private void minecraftdiscordlink$beforeCommand(ParseResults<CommandSourceStack> parse, String command,
                                                    CallbackInfo ci) {
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
