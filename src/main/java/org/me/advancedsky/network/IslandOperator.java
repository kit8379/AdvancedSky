package org.me.advancedsky.network;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.world.WorldHandler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Executes island work on this server, the island's host. Every operation on an island runs in
 * that island's serial chain, so a delete can never interleave with the create that is still
 * pasting the same island.
 */
public class IslandOperator {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final WorldHandler worldHandler;
    private final String serverID;

    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

    public IslandOperator(AdvancedSky plugin, DatabaseHandler database, WorldHandler worldHandler, String serverID) {
        this.plugin = plugin;
        this.database = database;
        this.worldHandler = worldHandler;
        this.serverID = serverID;
    }

    public CompletableFuture<Void> createIsland(UUID islandUuid, UUID ownerUuid) {
        return serialized(islandUuid, () -> {
            int slot = database.addIslandData(islandUuid, ownerUuid, serverID);

            return worldHandler.createIsland(slot).thenRunAsync(() -> {
                plugin.debug("IslandOperator", "Created island " + islandUuid + " at slot " + slot + " on server: " + serverID);
            }, plugin.getBukkitAsyncExecutor()).exceptionallyComposeAsync(e -> {
                return cleanupFailedCreate(islandUuid, slot).thenCompose(v -> {
                    return CompletableFuture.failedFuture(e);
                });
            }, plugin.getBukkitAsyncExecutor());
        });
    }

    /**
     * Deletes the island's rows, then clears its area.
     */
    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid) {
        return serialized(islandUuid, () -> {
            int slot = database.getIslandSlot(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            database.deleteIsland(actor, islandUuid);

            return worldHandler.deleteIsland(slot).exceptionally(e -> {
                plugin.severe("Island rows deleted but the area could not be cleared, leaving orphaned blocks at slot " + slot + ": " + islandUuid, e);
                return null;
            }).thenRunAsync(() -> {
                plugin.debug("IslandOperator", "Deleted island " + islandUuid + " at slot " + slot);
            }, plugin.getBukkitAsyncExecutor());
        });
    }

    private CompletableFuture<Void> cleanupFailedCreate(UUID islandUuid, int slot) {
        return worldHandler.deleteIsland(slot).exceptionally(e -> {
            plugin.severe("Failed to cleanup area after island create failure: " + islandUuid, e);
            return null;
        }).thenRunAsync(() -> {
            try {
                database.deleteIsland(new Actor.Bypass("island create cleanup"), islandUuid);
            } catch (Exception e) {
                plugin.severe("Failed to cleanup database after island create failure: " + islandUuid, e);
            }
        }, plugin.getBukkitAsyncExecutor());
    }

    /**
     * Appends the operation to the island's chain. An idle chain runs it inline on the
     * caller's async thread; only a busy chain defers it, and then onto the async executor,
     * because the previous operation may have completed on a region thread. Results are
     * delivered on the operation's own completing thread. The chain entry is dropped once idle.
     */
    private <T> CompletableFuture<T> serialized(UUID islandUuid, Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<Void> previous = chains.put(islandUuid, done);

        Runnable run = () -> supply(operation).whenComplete((value, error) -> {
            done.complete(null);
            chains.remove(islandUuid, done);
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(value);
            }
        });

        if (previous == null || previous.isDone()) {
            run.run();
        } else {
            previous.thenRunAsync(run, plugin.getBukkitAsyncExecutor());
        }

        return result;
    }

    private static <T> CompletableFuture<T> supply(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }
}
