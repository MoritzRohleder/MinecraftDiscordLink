package de.moritzrohleder.minecraftdiscordlink.leaderboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import de.moritzrohleder.minecraftdiscordlink.Constants;
import de.moritzrohleder.minecraftdiscordlink.config.ModConfig;
import de.moritzrohleder.minecraftdiscordlink.mixin.StatAccessor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Leaderboards from the vanilla statistics, for {@code /mc top} and {@code /mc stats}.
 * <p>
 * Every player who has played on the world is ranked: the statistics files of the world are read on a background
 * thread, then the live values of the online players replace their files, which are only written every few minutes.
 * A statistic missing from a file counts as 0.
 */
public final class Leaderboards {

    /** Names of the vanilla statistic types, for categories without a configured name. */
    private static final Map<String, String> TYPE_NAMES = Map.of(
            "minecraft:mined", "Mined",
            "minecraft:crafted", "Crafted",
            "minecraft:used", "Used",
            "minecraft:broken", "Broken",
            "minecraft:picked_up", "Picked up",
            "minecraft:dropped", "Dropped",
            "minecraft:killed", "Killed",
            "minecraft:killed_by", "Killed by");

    private Leaderboards() {
    }

    /**
     * Looks up the statistics of the configured categories. Unknown statistics are logged as errors and left out.
     * Must be called once the game registries are loaded, i.e. while the server is running.
     *
     * @param config the leaderboard settings
     * @return the categories with a known statistic, in the configured order
     */
    public static List<Category> resolve(ModConfig.Leaderboard config) {
        List<Category> categories = new ArrayList<>();
        for (ModConfig.Category category : config.categories()) {
            Optional<ObjectiveCriteria> criteria = ObjectiveCriteria.byName(category.stat());
            if (criteria.isPresent() && criteria.get() instanceof Stat<?> stat) {
                categories.add(new Category(category.stat(), category.name().orElseGet(() -> vanillaName(stat)),
                        stat, category.lowestFirst(), format(stat), typeKey(stat), valueKey(stat)));
            } else {
                Constants.LOG.error("Unknown statistic '{}' in 'leaderboard.categories' in the config. "
                        + "The category is ignored.", category.stat());
            }
        }
        return List.copyOf(categories);
    }

    /**
     * Ranks all players who have played on the world in the given categories.
     *
     * @param server     the running server
     * @param categories the categories, see {@link #resolve(ModConfig.Leaderboard)}
     * @return the ranking of every category, completed on the server thread
     */
    public static CompletableFuture<Map<Category, Ranking>> rank(MinecraftServer server, List<Category> categories) {
        Path directory = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        return CompletableFuture.supplyAsync(() -> readFiles(directory, categories))
                .thenApplyAsync(values -> {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        long[] live = new long[categories.size()];
                        for (int i = 0; i < live.length; i++) {
                            live[i] = player.getStats().getValue(categories.get(i).stat());
                        }
                        values.put(player.getUUID(), live);
                    }
                    Map<Category, Ranking> rankings = new LinkedHashMap<>();
                    for (int i = 0; i < categories.size(); i++) {
                        Map<UUID, Long> category = new HashMap<>();
                        int index = i;
                        values.forEach((player, playerValues) -> category.put(player, playerValues[index]));
                        rankings.put(categories.get(i), Ranking.of(category, categories.get(i).lowestFirst()));
                    }
                    return rankings;
                }, server);
    }

    /**
     * Looks up the name of a player. Must be called on the server thread.
     *
     * @param server the running server
     * @param player the player's UUID
     * @return the player's name, or the UUID if the name is unknown
     */
    public static String playerName(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache() == null ? player.toString()
                : server.getProfileCache().get(player).map(GameProfile::getName).orElse(player.toString());
    }

    /**
     * Reads the values of all categories from the statistics files of the world. Unreadable files are logged and
     * skipped.
     *
     * @param directory  the statistics directory of the world
     * @param categories the categories
     * @return the values of every player, in the order of {@code categories}
     */
    private static Map<UUID, long[]> readFiles(Path directory, List<Category> categories) {
        Map<UUID, long[]> values = new HashMap<>();
        if (!Files.isDirectory(directory)) {
            return values;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                UUID player;
                try {
                    player = UUID.fromString(name.substring(0, name.length() - ".json".length()));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonElement root = JsonParser.parseReader(reader);
                    JsonObject stats = root.isJsonObject() && root.getAsJsonObject().get("stats") instanceof JsonObject
                            object ? object : new JsonObject();
                    long[] playerValues = new long[categories.size()];
                    for (int i = 0; i < playerValues.length; i++) {
                        playerValues[i] = value(stats, categories.get(i));
                    }
                    values.put(player, playerValues);
                } catch (IOException | JsonParseException | IllegalStateException e) {
                    Constants.LOG.warn("Could not read the statistics file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            Constants.LOG.error("Could not read the statistics directory {}: {}", directory, e.getMessage());
        }
        return values;
    }

    /**
     * Reads the value of one category from the {@code stats} object of a statistics file.
     *
     * @param stats    the {@code stats} object, by statistic type and value
     * @param category the category
     * @return the value, 0 if missing
     */
    private static long value(JsonObject stats, Category category) {
        if (stats.get(category.typeKey()) instanceof JsonObject type
                && type.get(category.valueKey()) instanceof JsonElement value && value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isNumber()) {
            return value.getAsLong();
        }
        return 0;
    }

    /**
     * Determines how values of a statistic are shown.
     *
     * @param stat the statistic
     * @return the format matching the statistic's unit
     */
    private static StatFormat format(Stat<?> stat) {
        StatFormatter formatter = ((StatAccessor) stat).minecraftdiscordlink$getFormatter();
        if (formatter == StatFormatter.TIME) {
            return StatFormat.TIME;
        }
        if (formatter == StatFormatter.DISTANCE) {
            return StatFormat.DISTANCE;
        }
        if (formatter == StatFormatter.DIVIDE_BY_TEN) {
            return StatFormat.TENTHS;
        }
        return StatFormat.NUMBER;
    }

    /**
     * Builds the English name of a statistic, e.g. {@code Time Played} or {@code Mined: Diamond Ore}.
     *
     * @param stat the statistic
     * @return the name
     */
    private static String vanillaName(Stat<?> stat) {
        Language language = Language.getInstance();
        if (stat.getType() == Stats.CUSTOM) {
            return language.getOrDefault("stat." + valueKey(stat).replace(':', '.'), valueKey(stat));
        }
        Object value = stat.getValue();
        String descriptionId = value instanceof Block block ? block.getDescriptionId()
                : value instanceof Item item ? item.getDescriptionId()
                : value instanceof EntityType<?> entity ? entity.getDescriptionId()
                : null;
        String valueName = descriptionId == null ? valueKey(stat) : language.getOrDefault(descriptionId);
        return TYPE_NAMES.getOrDefault(typeKey(stat), typeKey(stat)) + ": " + valueName;
    }

    /**
     * Returns the key of a statistic's type in the statistics files.
     *
     * @param stat the statistic
     * @return e.g. {@code minecraft:custom}
     */
    private static String typeKey(Stat<?> stat) {
        return String.valueOf(BuiltInRegistries.STAT_TYPE.getKey(stat.getType()));
    }

    /**
     * Returns the key of a statistic's value in the statistics files.
     *
     * @param stat the statistic
     * @param <T>  the type of the statistic's value
     * @return e.g. {@code minecraft:play_time}
     */
    private static <T> String valueKey(Stat<T> stat) {
        StatType<T> type = stat.getType();
        ResourceLocation key = type.getRegistry().getKey(stat.getValue());
        return String.valueOf(key);
    }

    /**
     * A leaderboard category with its statistic.
     *
     * @param id          the statistic as scoreboard criterion, as configured
     * @param name        the name shown in Discord
     * @param stat        the statistic
     * @param lowestFirst whether the lowest value is ranked first
     * @param format      how values are shown
     * @param typeKey     the key of the statistic's type in the statistics files
     * @param valueKey    the key of the statistic's value in the statistics files
     */
    public record Category(String id, String name, Stat<?> stat, boolean lowestFirst, StatFormat format,
                           String typeKey, String valueKey) {
    }
}
