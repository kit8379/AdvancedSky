package org.me.advancedsky.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.island.CobblestoneGeneratorHandler;
import org.me.advancedsky.island.LimitHandler;
import org.me.advancedsky.model.Island;

/**
 * Applies generator upgrades after protection. Keeps vanilla cobblestone when the island
 * is unavailable or the rolled block has reached its limit.
 */
public class CobblestoneGeneratorListener implements Listener {

    private final ConfigHandler config;
    private final IslandCache islandCache;
    private final CobblestoneGeneratorHandler generator;
    private final LimitHandler limitHandler;

    public CobblestoneGeneratorListener(ConfigHandler config, IslandCache islandCache, CobblestoneGeneratorHandler generator, LimitHandler limitHandler) {
        this.config = config;
        this.islandCache = islandCache;
        this.generator = generator;
        this.limitHandler = limitHandler;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        BlockState formed = event.getNewState();
        if (formed.getType() != Material.COBBLESTONE) {
            return;
        }

        Block block = event.getBlock();
        if (!block.getWorld().getName().equals(config.getIslandWorldName())) {
            return;
        }

        Island island = islandCache.getAt(block.getLocation());
        if (island == null) {
            return;
        }

        Material generatedType = generator.roll(island.getGeneratorLevel());
        if (generatedType != Material.COBBLESTONE && limitHandler.hasRoom(island.getIslandUuid(), generatedType)) {
            formed.setType(generatedType);
        }
    }
}
