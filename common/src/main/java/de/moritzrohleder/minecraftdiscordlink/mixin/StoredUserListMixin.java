package de.moritzrohleder.minecraftdiscordlink.mixin;

import de.moritzrohleder.minecraftdiscordlink.MinecraftDiscordLink;
import net.minecraft.server.players.BanListEntry;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.StoredUserList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Reports every change of the vanilla ban, IP ban and whitelist lists to the moderation log, whoever makes it.
 *
 * @param <K> the type of the users in the list
 * @param <V> the type of the entries
 */
@Mixin(StoredUserList.class)
public abstract class StoredUserListMixin<K, V extends StoredUserEntry<K>> {

    @Shadow
    @Final
    private Map<String, V> map;

    /**
     * Returns the key of a user in the map.
     *
     * @param user the user
     * @return the key
     */
    @Shadow
    protected abstract String getKeyForUser(K user);

    /**
     * Called before an entry is added. A new ban of a player who is already banned replaces the old one.
     *
     * @param entry the new entry
     * @param ci    the callback info
     */
    @Inject(method = "add", at = @At("HEAD"))
    @SuppressWarnings("unchecked")
    private void minecraftdiscordlink$onAdd(V entry, CallbackInfo ci) {
        K user = (K) ((StoredUserEntryAccessor) entry).minecraftdiscordlink$getUser();
        boolean replaces = user != null && map.containsKey(getKeyForUser(user));
        MinecraftDiscordLink.onUserListAdded((StoredUserList<?, ?>) (Object) this, entry, replaces);
    }

    /**
     * Called before a user is removed.
     *
     * @param user the user
     * @param ci   the callback info
     */
    @Inject(method = "remove(Ljava/lang/Object;)V", at = @At("HEAD"))
    private void minecraftdiscordlink$onRemove(K user, CallbackInfo ci) {
        V entry = map.get(getKeyForUser(user));
        if (entry != null) {
            MinecraftDiscordLink.onUserListRemoved((StoredUserList<?, ?>) (Object) this, entry);
        }
    }

    /**
     * Called before Minecraft silently removes expired bans, so their end is logged and synchronized.
     *
     * @param ci the callback info
     */
    @Inject(method = "removeExpired", at = @At("HEAD"))
    private void minecraftdiscordlink$onRemoveExpired(CallbackInfo ci) {
        Date now = new Date();
        List<V> expired = new ArrayList<>();
        for (V entry : map.values()) {
            if (entry instanceof BanListEntry<?> ban && ban.getExpires() != null && ban.getExpires().before(now)) {
                expired.add(entry);
            }
        }
        expired.forEach(entry -> MinecraftDiscordLink.onUserListRemoved((StoredUserList<?, ?>) (Object) this, entry));
    }
}
