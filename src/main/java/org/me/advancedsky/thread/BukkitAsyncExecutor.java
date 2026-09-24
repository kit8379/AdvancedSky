package org.me.advancedsky.thread;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * Runs blocking work (MySQL, Redis) on Paper's async pool. Available on both Paper and Folia.
 */
public class BukkitAsyncExecutor implements Executor {

    private final Plugin plugin;

    public BukkitAsyncExecutor(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull Runnable command) {
        // runNow hands off immediately; the legacy runTaskAsynchronously waits for the next tick.
        Bukkit.getAsyncScheduler().runNow(plugin, task -> command.run());
    }
}
