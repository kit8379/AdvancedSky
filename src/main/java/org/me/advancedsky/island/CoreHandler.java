package org.me.advancedsky.island;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.IslandDoesNotExistException;
import org.me.advancedsky.exceptions.PlayerNotOnOwnIslandException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.network.IslandDistributor;
import org.me.advancedsky.world.WorldHandler;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class CoreHandler {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final DatabaseHandler database;
    private final IslandDistributor islandDistributor;
    private final IslandCache islandCache;
    private final LocalIslandTasks tasks;
    private final WorldHandler worldHandler;
    private final LimitHandler limitHandler;
    private final String serverID;
    private final Map<UUID, Integer> creating = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public CoreHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, IslandDistributor islandDistributor, IslandCache islandCache, LocalIslandTasks tasks, WorldHandler worldHandler, LimitHandler limitHandler, String serverID) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.islandDistributor = islandDistributor;
        this.islandCache = islandCache;
        this.tasks = tasks;
        this.worldHandler = worldHandler;
        this.limitHandler = limitHandler;
        this.serverID = serverID;
    }

    public CompletableFuture<Void> createIsland(UUID ownerUuid) {
        UUID islandUuid = UUID.randomUUID();
        return CompletableFuture.completedFuture(null).thenComposeAsync(v -> islandDistributor.createIsland(islandUuid, ownerUuid, () -> createIsland(islandUuid, ownerUuid)), plugin.getBukkitAsyncExecutor());
    }

    /**
     * Releases players immediately; physical cleanup keeps the old slot and serial task reserved.
     */
    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid) {
        CompletableFuture<Void> accepted = new CompletableFuture<>();
        tasks.execute(islandUuid, () -> {
            int slot = getIslandSlot(islandUuid);
            beginDeletion(actor, islandUuid, slot);
            CompletableFuture<Void> cleanup = supply(() -> clearIsland(islandUuid, slot));
            accepted.complete(null);
            return cleanup;
        }).whenComplete((v, error) -> {
            if (error != null) {
                if (!accepted.completeExceptionally(error)) {
                    plugin.severe("Deleted island retained for physical cleanup: " + islandUuid, error);
                }
            }
        });
        return accepted;
    }

    public CompletableFuture<Boolean> toggleIslandLock(Actor actor, UUID islandUuid) {
        return tasks.execute(islandUuid, () -> tasks.refresh(islandUuid, () -> {
            return database.toggleIslandLock(actor, islandUuid);
        }).thenCompose(locked -> {
            if (!locked) {
                return CompletableFuture.completedFuture(false);
            }

            Set<UUID> allowed = new HashSet<>();
            Island island = islandCache.get(islandUuid);
            if (island != null) {
                allowed.add(island.getOwner());
                allowed.addAll(island.getMembers());
                allowed.addAll(island.getCoops());
            } else {
                allowed.addAll(database.getIslandPlayers(islandUuid).keySet());
                allowed.addAll(database.getIslandCoops(islandUuid));
            }

            return worldHandler.removePlayersFromIsland(getIslandSlot(islandUuid), player -> !allowed.contains(player.getUniqueId())).thenApply(v -> true);
        }));
    }

    public CompletableFuture<Boolean> toggleIslandPvp(Actor actor, UUID islandUuid) {
        return tasks.execute(islandUuid, () -> tasks.refresh(islandUuid, () -> {
            return database.toggleIslandPvp(actor, islandUuid);
        }));
    }

    public CompletableFuture<Boolean> isIslandLock(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.isIslandLock(islandUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Boolean> isIslandPvp(UUID islandUuid) {
        return CompletableFuture.supplyAsync(() -> database.isIslandPvp(islandUuid), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<UUID> getIslandUuid(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> database.getIslandUuid(playerUuid).orElseThrow(IslandDoesNotExistException::new), plugin.getBukkitAsyncExecutor());
    }

    /**
     * Own-island management uses the local cache and reads the player on their entity thread.
     */
    public CompletableFuture<UUID> getOwnIslandHere(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> Bukkit.getPlayer(playerUuid), plugin.getGlobalRegionExecutor()).thenCompose(player -> {
            CompletableFuture<UUID> result = new CompletableFuture<>();
            if (player == null) return CompletableFuture.failedFuture(new PlayerNotOnOwnIslandException());
            try {
                if (player.getScheduler().run(plugin, task -> {
                    try {
                        UUID islandUuid = getOwnIslandAt(playerUuid, player.getLocation());
                        if (islandUuid == null) throw new PlayerNotOnOwnIslandException();
                        result.complete(islandUuid);
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                }, () -> result.completeExceptionally(new PlayerNotOnOwnIslandException())) == null) {
                    result.completeExceptionally(new PlayerNotOnOwnIslandException());
                }
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
            return result;
        });
    }

    /**
     * Called with a location snapshot taken on the player's thread. Co-ops and visitors do not qualify.
     */
    public UUID getOwnIslandAt(UUID playerUuid, Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName()))
            return null;
        Island island = islandCache.getAt(location);
        if (island == null || !(playerUuid.equals(island.getOwner()) || island.getMembers().contains(playerUuid)))
            return null;
        return island.getIslandUuid();
    }

    /**
     * The island whose area holds the location, among the islands hosted on this server; null
     * anywhere else. NewSky reads this from the island world's name; here every island shares one
     * world, so the grid decides.
     */
    public UUID getIslandUuidAt(Location location) {
        if (location.getWorld() == null || !location.getWorld().getName().equals(config.getIslandWorldName())) {
            return null;
        }

        return islandCache.getIslandUuidAt(location);
    }

    /**
     * Local destination for both same-server creation and received creation requests.
     */
    public CompletableFuture<Void> createIsland(UUID islandUuid, UUID ownerUuid) {
        return tasks.queue(islandUuid, () -> {
            if (stopping) throw new IllegalStateException("Island lifecycle stopped");
            int slot;
            try {
                slot = database.addIslandData(islandUuid, ownerUuid, serverID);
            } catch (RuntimeException e) {
                // A commit whose answer was lost may still have landed: then the island exists and
                // is built like any other.
                if (LocalIslandTasks.isRuleRejection(e)) {
                    throw e;
                }
                slot = database.getIslandSlot(islandUuid).orElseThrow(() -> e);
            }
            int createdSlot = slot;
            creating.put(islandUuid, createdSlot);
            islandCache.beginDeletion(islandUuid, createdSlot);
            // Gaps from older versions may still hold files on this host. Clear before pasting,
            // while the newly inserted row reserves the position against other creates.
            return worldHandler.deleteIsland(createdSlot).thenCompose(v -> worldHandler.createIsland(createdSlot)).thenComposeAsync(v -> {
                if (stopping) throw new IllegalStateException("Island lifecycle stopped");
                database.finishIslandCreation(islandUuid);
                islandCache.unload(islandUuid);
                return islandCache.load(islandUuid);
            }, plugin.getBukkitAsyncExecutor()).thenRunAsync(() -> {
                plugin.debug("CoreHandler", "Created island " + islandUuid + " at slot " + createdSlot + " on server: " + serverID);
            }, plugin.getBukkitAsyncExecutor()).exceptionallyComposeAsync(e -> {
                return cleanupFailedCreate(islandUuid, createdSlot).thenCompose(v -> {
                    return CompletableFuture.failedFuture(e);
                });
            }, plugin.getBukkitAsyncExecutor()).whenComplete((v, e) -> creating.remove(islandUuid));
        });
    }

    /**
     * Interrupted builds keep their slot until startup recovery clears the physical area.
     */
    public void abortCreates() {
        stopping = true;
        if (worldHandler != null) worldHandler.stop();
        creating.forEach((islandUuid, slot) -> {
            try {
                database.beginIslandDeletion(new Actor.Bypass("island create interrupted by shutdown"), islandUuid);
                islandCache.beginDeletion(islandUuid, slot);
            } catch (RuntimeException e) {
                plugin.severe("Could not roll back the island create interrupted by shutdown: " + islandUuid, e);
            }
            plugin.warning("Retained interrupted create for cleanup: " + islandUuid + " at slot " + slot);
        });
        creating.clear();
    }

    private CompletableFuture<Void> deleteNow(Actor actor, UUID islandUuid, int slot) {
        beginDeletion(actor, islandUuid, slot);
        return clearIsland(islandUuid, slot);
    }

    private void beginDeletion(Actor actor, UUID islandUuid, int slot) {
        if (stopping) throw new IllegalStateException("Island lifecycle stopped");
        try {
            database.beginIslandDeletion(actor, islandUuid);
        } catch (RuntimeException e) {
            // A lost commit reply is success only if the player relationships are gone too.
            // An unfinished create also has deleting=true, but still holds its owner.
            if (LocalIslandTasks.isRuleRejection(e) || !database.isIslandDeleting(islandUuid) || !database.getIslandPlayers(islandUuid).isEmpty())
                throw e;
        }
        islandCache.beginDeletion(islandUuid, slot);
    }

    private CompletableFuture<Void> clearIsland(UUID islandUuid, int slot) {
        return worldHandler.deleteIsland(slot).thenRunAsync(() -> {
            if (stopping) throw new IllegalStateException("Island lifecycle stopped");
            try {
                database.finishIslandDeletion(islandUuid);
            } catch (RuntimeException e) {
                // A successful commit can lose its answer. The UUID, not the position, identifies it.
                if (LocalIslandTasks.isRuleRejection(e) || database.getIslandSlot(islandUuid).isPresent()) throw e;
            }
            islandCache.unload(islandUuid);
            if (limitHandler != null) limitHandler.unload(islandUuid);
            plugin.debug("CoreHandler", "Cleared and deleted island " + islandUuid + " at slot " + slot);
        }, plugin.getBukkitAsyncExecutor());
    }

    /**
     * Startup recovery and manual purge share the same chain as ordinary island edits.
     */
    public CompletableFuture<DeletionResult> retryDeletions() {
        return CompletableFuture.supplyAsync(() -> database.getDeletingIslands(serverID), plugin.getBukkitAsyncExecutor()).thenCompose(pending -> {
            var results = pending.keySet().stream().map(integer -> tasks.queue(integer, () -> {
                if (!database.isIslandDeleting(integer)) return CompletableFuture.completedFuture(0);
                return deleteNow(new Actor.Bypass("incomplete island deletion retry"), integer, getIslandSlot(integer)).thenApply(v -> 1);
            }).handle((count, error) -> {
                if (error != null) plugin.severe("Island cleanup failed; row and slot retained: " + integer, error);
                return error == null ? count : -1;
            })).toList();
            return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).thenApply(v -> {
                int cleaned = (int) results.stream().filter(result -> result.join() == 1).count();
                int failed = (int) results.stream().filter(result -> result.join() == -1).count();
                return new DeletionResult(cleaned, failed);
            });
        });
    }

    private CompletableFuture<Void> cleanupFailedCreate(UUID islandUuid, int slot) {
        if (stopping) return CompletableFuture.completedFuture(null);
        return deleteNow(new Actor.Bypass("island create cleanup"), islandUuid, slot).exceptionally(e -> {
            plugin.severe("Failed create retained for physical cleanup: " + islandUuid, e);
            return null;
        });
    }

    private int getIslandSlot(UUID islandUuid) {
        return database.getIslandSlot(islandUuid).orElseThrow(IslandDoesNotExistException::new);
    }

    private static <T> CompletableFuture<T> supply(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public record DeletionResult(int purged, int deferred) {
    }
}
