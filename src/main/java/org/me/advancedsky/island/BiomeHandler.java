package org.me.advancedsky.island;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.BiomeNotUnlockedException;
import org.me.advancedsky.exceptions.InvalidBiomeException;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.LocationNotInIslandException;
import org.me.advancedsky.exceptions.WorldNotFoundException;
import org.me.advancedsky.grid.IslandGrid;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BiomeHandler {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final DatabaseHandler database;
    private final IslandGrid islandGrid;

    public BiomeHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, IslandGrid islandGrid) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.islandGrid = islandGrid;
    }

    /**
     * Re-biomes the chunk holding the location, which must lie in the player's own island area on
     * the server hosting it.
     */
    public CompletableFuture<Void> applyPlayerChunkBiome(UUID playerUuid, Location location, String biomeName) {
        return CompletableFuture.runAsync(() -> {
            // Players may only re-biome their own island; admins use applyChunkBiome directly.
            UUID islandUuid = database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new);

            String host = database.getIslandServer(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            int slot = database.getIslandSlot(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            if (!host.equals(config.getServerName()) || location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName()) || !islandGrid.isInArea(slot, location.getX(), location.getZ())) {
                throw new LocationNotInIslandException();
            }

            // The island's biomes upgrade level decides which biomes its members may set.
            List<String> allowed = config.getUpgradeAllowedBiomes(database.getIslandUpgradeLevel(islandUuid, "biomes"));
            String key = biomeName.toLowerCase(Locale.ROOT);
            if (key.startsWith("minecraft:")) {
                key = key.substring("minecraft:".length());
            }

            if (!allowed.contains(key)) {
                throw new BiomeNotUnlockedException(String.join(", ", allowed));
            }
        }, plugin.getBukkitAsyncExecutor()).thenCompose(v -> applyChunkBiome(location, biomeName));
    }

    /**
     * Re-biomes the chunk holding the location. Island areas are chunk aligned, so a chunk lies
     * wholly inside or outside one.
     */
    public CompletableFuture<Void> applyChunkBiome(Location location, String biomeName) {
        World world = location.getWorld();
        if (world == null) {
            return CompletableFuture.failedFuture(new WorldNotFoundException());
        }

        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;

        Biome biome = parseBiome(biomeName);
        if (biome == null) {
            return CompletableFuture.failedFuture(new InvalidBiomeException());
        }

        // Biomes are set on the thread that owns the chunk.
        CompletableFuture<Void> applied = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> {
            try {
                int startX = chunkX << 4;
                int startZ = chunkZ << 4;
                int minY = world.getMinHeight();
                int maxY = world.getMaxHeight();

                for (int x = startX; x < startX + 16; x += 4) {
                    for (int z = startZ; z < startZ + 16; z += 4) {
                        for (int y = minY; y < maxY; y += 4) {
                            world.setBiome(x, y, z, biome);
                        }
                    }
                }

                world.refreshChunk(chunkX, chunkZ);
                applied.complete(null);
            } catch (Throwable t) {
                applied.completeExceptionally(t);
            }
        });
        return applied;
    }

    /**
     * Biome keys are lowercase; {@code plains}, {@code PLAINS} and {@code minecraft:plains} all
     * name the same biome. Null for anything that is not a valid key or not a biome.
     */
    private Biome parseBiome(String biomeName) {
        Registry<@org.jetbrains.annotations.NotNull Biome> biomeRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);

        // fromString defaults the namespace to minecraft and returns null instead of throwing.
        NamespacedKey key = NamespacedKey.fromString(biomeName.toLowerCase(Locale.ROOT));
        return key == null ? null : biomeRegistry.get(key);
    }
}
