package org.me.advancedsky.thread;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * Runs work on the global region thread: the main thread on Paper, the global region on Folia.
 * Stands in for NewSky's {@code Bukkit.getScheduler().getMainThreadExecutor(plugin)}, which Folia
 * does not provide. Player lookups happen here; anything touching a player's entity state is then
 * handed to that player's own scheduler.
 */
public class GlobalRegionExecutor implements Executor {

    private final Plugin plugin;

    public GlobalRegionExecutor(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull Runnable command) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, command);
    }
}
