package org.me.advancedsky.loader;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import org.jetbrains.annotations.NotNull;
import org.me.advancedsky.world.RegionPurge;

@SuppressWarnings({"UnstableApiUsage", "unused"})
public class AdvancedSkyBootstrap implements PluginBootstrap {

    /**
     * Runs before any world loads: the region files of deleted islands can go now, while no
     * world has them open or cached.
     */
    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        RegionPurge.purge(context.getDataDirectory(), context.getLogger());
    }
}
