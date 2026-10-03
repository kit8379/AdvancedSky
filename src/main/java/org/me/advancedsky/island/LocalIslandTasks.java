package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.IslandNotHostedHereException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Shared async ordering and cache refresh for the local feature handlers. No network routing. */
public class LocalIslandTasks {
    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final IslandCache cache;
    private final String serverID;
    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

    public LocalIslandTasks(AdvancedSky plugin, DatabaseHandler database, IslandCache cache, String serverID) {
        this.plugin = plugin;
        this.database = database;
        this.cache = cache;
        this.serverID = serverID;
    }

    /** Existing-island work must run on its host, including admin and received remote requests. */
    <T> CompletableFuture<T> execute(UUID islandUuid, Supplier<CompletableFuture<T>> operation) {
        return queue(islandUuid, () -> {
            String host = database.getIslandServer(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            if (!serverID.equals(host)) throw new IslandNotHostedHereException(host);
            return operation.get();
        });
    }

    <T> CompletableFuture<T> mutate(UUID islandUuid, Supplier<T> mutation) {
        return execute(islandUuid, () -> refresh(islandUuid, mutation));
    }

    /** Called inside an execute task, so world effects can stay in the same serial chain. */
    <T> CompletableFuture<T> refresh(UUID islandUuid, Supplier<T> mutation) {
        try {
            T result = mutation.get();
            return cache.reload(islandUuid).handle((v, error) -> {
                if (error != null) plugin.severe("Island data saved but could not refresh the island cache: " + islandUuid, error);
                return result;
            });
        } catch (Throwable error) {
            if (isRuleRejection(error)) return CompletableFuture.failedFuture(error);
            // A lost commit reply may still have changed the database.
            return cache.reload(islandUuid).handle((v, reloadError) -> null)
                    .thenCompose(v -> CompletableFuture.failedFuture(error));
        }
    }

    static boolean isRuleRejection(Throwable error) {
        return error.getClass().getPackageName().equals(IslandDoesNotExistException.class.getPackageName());
    }

    /** Creation and recovery may have no row to check; their handlers validate the lifecycle. */
    <T> CompletableFuture<T> queue(UUID islandUuid, Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<Void> previous = chains.put(islandUuid, done);
        Runnable run = () -> {
            CompletableFuture<T> running;
            try {
                running = operation.get();
            } catch (Throwable error) {
                running = CompletableFuture.failedFuture(error);
            }
            running.whenComplete((value, error) -> {
                done.complete(null);
                chains.remove(islandUuid, done);
                if (error != null) result.completeExceptionally(error);
                else result.complete(value);
            });
        };
        // Register in call order before scheduling: async worker order must not reorder edits.
        CompletableFuture<Void> ready = previous == null ? CompletableFuture.completedFuture(null) : previous;
        ready.thenRunAsync(run, plugin.getBukkitAsyncExecutor());
        return result;
    }
}
