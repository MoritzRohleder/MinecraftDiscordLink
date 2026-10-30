package de.moritzrohleder.minecraftdiscordlink.moderation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Who is doing a moderation action on the current thread, and why.
 * <p>
 * Minecraft's ban and whitelist lists do not know who changed them. The mod therefore notes the moderator while a
 * command runs: vanilla commands get the name of their source (a player or {@code Server}), the mod's own commands
 * the Discord user. The hooks on the lists read it when they log a change.
 */
public final class ModerationContext {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    /** For every running command, whether it set the scope; commands can run other commands. */
    private static final ThreadLocal<Deque<Boolean>> COMMANDS = ThreadLocal.withInitial(ArrayDeque::new);

    private ModerationContext() {
    }

    /**
     * Runs an action with a moderator and reason. The scope replaces an active one for the action, e.g. the scope of
     * a running {@code /discord tempban} command, which does not know the reason yet.
     *
     * @param scope  the moderator and reason
     * @param action the action
     * @param <T>    the result type
     * @return the result of the action
     */
    public static <T> T run(Scope scope, Supplier<T> action) {
        Scope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * Runs an action with a moderator and reason, see {@link #run(Scope, Supplier)}.
     *
     * @param scope  the moderator and reason
     * @param action the action
     */
    public static void run(Scope scope, Runnable action) {
        run(scope, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Called before a Minecraft command runs: its source becomes the moderator, unless a scope is already active
     * (e.g. a command from the Discord console channel). Must be followed by {@link #exitCommand()}.
     *
     * @param source the name of the command source, e.g. a player name or {@code Server}
     */
    public static void enterCommand(String source) {
        boolean set = CURRENT.get() == null;
        if (set) {
            CURRENT.set(Scope.of(source, null));
        }
        COMMANDS.get().push(set);
    }

    /**
     * Called after a Minecraft command ran: removes the scope set by {@link #enterCommand(String)}.
     */
    public static void exitCommand() {
        Deque<Boolean> commands = COMMANDS.get();
        if (!commands.isEmpty() && commands.pop()) {
            CURRENT.remove();
        }
    }

    /**
     * Returns the active scope.
     *
     * @return the moderator and reason of the running action, empty outside of commands
     */
    public static Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /**
     * The moderator and reason of a running action.
     *
     * @param moderator who does it, e.g. {@code Discord @anedhel}
     * @param reason    the reason, empty if none was given
     * @param fromSync  whether the action is caused by the ban sync, which must not synchronize it back
     */
    public record Scope(String moderator, Optional<String> reason, boolean fromSync) {

        /**
         * Creates a scope for an action of a moderator.
         *
         * @param moderator who does it
         * @param reason    the reason, {@code null} or blank if none was given
         * @return the scope
         */
        public static Scope of(String moderator, String reason) {
            return new Scope(moderator, Optional.ofNullable(reason).map(String::strip).filter(text -> !text.isEmpty()),
                    false);
        }
    }
}
