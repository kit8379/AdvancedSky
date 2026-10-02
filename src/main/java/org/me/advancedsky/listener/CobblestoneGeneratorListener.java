package org.me.advancedsky.listener;

import org.bukkit.Material;
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
 * Cobblestone formed by lava meeting water on an island becomes the block its generator-rates
 * upgrade level rolls. Runs after protection has had its say, so a form outside the island
 * boundary is already cancelled here. An unavailable cache entry leaves vanilla cobblestone, and
 * so does a capped block the island has no room for.
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

        if (!event.getBlock().getWorld().getName().equals(config.getIslandWorldName())) {
            return;
        }

        Island island = islandCache.getAt(event.getBlock().getLocation());
        if (island == null) {
            return;
        }

        Material rolled = generator.roll(island.getGeneratorLevel());
        if (rolled != Material.COBBLESTONE && limitHandler.hasRoom(island.getIslandUuid(), rolled)) {
            formed.setType(rolled);
        }
    }
}
