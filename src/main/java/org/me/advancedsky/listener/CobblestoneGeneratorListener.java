package org.me.advancedsky.listener;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.island.CobblestoneGeneratorHandler;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.snapshot.IslandSnapshot;


/**
 * Cobblestone formed by lava meeting water on an island becomes the block its generator-rates
 * upgrade level rolls. Runs after protection has had its say, so a form outside the island
 * boundary is already cancelled here. An unavailable snapshot leaves vanilla cobblestone.
 */
public class CobblestoneGeneratorListener implements Listener {

    private final ConfigHandler config;
    private final IslandSnapshot islandSnapshot;
    private final CobblestoneGeneratorHandler generator;

    public CobblestoneGeneratorListener(ConfigHandler config, IslandSnapshot islandSnapshot, CobblestoneGeneratorHandler generator) {
        this.config = config;
        this.islandSnapshot = islandSnapshot;
        this.generator = generator;
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

        Island island = islandSnapshot.getAt(event.getBlock().getLocation());
        if (island == null) {
            return;
        }

        Material rolled = generator.roll(island.getGeneratorLevel());
        if (rolled != Material.COBBLESTONE) {
            formed.setType(rolled);
        }
    }
}
