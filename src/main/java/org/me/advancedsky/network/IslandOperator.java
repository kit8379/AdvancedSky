package org.me.advancedsky.network;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.cluster.OnlinePlayerRegistry;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.economy.EconomyHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.island.LimitHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.world.WorldHandler;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Executes island work on this server. Each island shares one serial chain for lifecycle,
 * mutations, cache refresh and world effects; existing-island work requires its local host.
 */
public class IslandOperator {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final WorldHandler worldHandler;
    private final IslandCache islandCache;
    private final LimitHandler limitHandler;
    private final EconomyHandler economy;
    private final OnlinePlayerRegistry onlinePlayerRegistry;
    private final String serverID;
    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> creating = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public IslandOperator(AdvancedSky plugin, DatabaseHandler database, WorldHandler worldHandler, IslandCache islandCache, LimitHandler limitHandler, EconomyHandler economy, OnlinePlayerRegistry onlinePlayerRegistry, String serverID) {
        this.plugin = plugin;
        this.database = database;
        this.worldHandler = worldHandler;
        this.islandCache = islandCache;
        this.limitHandler = limitHandler;
        this.economy = economy;
        this.onlinePlayerRegistry = onlinePlayerRegistry;
        this.serverID = serverID;
    }

    /**
     * Builds the reserved area for a local creation or a received creation request.
     */
    public CompletableFuture<Void> createIsland(UUID islandUuid, UUID ownerUuid) {
        return serialized(islandUuid, () -> {
            if (stopping) throw new IllegalStateException("Island operator stopped");
            int slot;
            try {
                slot = database.addIslandData(islandUuid, ownerUuid, serverID);
            } catch (RuntimeException e) {
                // A commit whose answer was lost may still have landed: then the island exists and
                // is built like any other.
                if (isRuleRejection(e)) {
                    throw e;
                }
                slot = database.getIslandSlot(islandUuid).orElseThrow(() -> e);
            }
            int createdSlot = slot;
            creating.put(islandUuid, createdSlot);
            islandCache.beginDeletion(islandUuid, createdSlot);
            // Clear the reserved area before pasting while the row blocks concurrent reuse.
            return worldHandler.deleteIsland(createdSlot).thenCompose(v -> worldHandler.createIsland(createdSlot)).thenComposeAsync(v -> {
                if (stopping) throw new IllegalStateException("Island operator stopped");
                database.finishIslandCreation(islandUuid);
                islandCache.unload(islandUuid);
                return islandCache.load(islandUuid);
            }, plugin.getBukkitAsyncExecutor()).thenRunAsync(() -> {
                plugin.debug("IslandOperator", "Created island " + islandUuid + " at slot " + createdSlot + " on server: " + serverID);
            }, plugin.getBukkitAsyncExecutor()).exceptionallyComposeAsync(e -> {
                return cleanupFailedCreate(islandUuid, createdSlot).thenCompose(v -> {
                    return CompletableFuture.failedFuture(e);
                });
            }, plugin.getBukkitAsyncExecutor()).whenComplete((v, e) -> creating.remove(islandUuid));
        });
    }

    /**
     * Interrupted builds keep their slots until recovery clears the physical areas.
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

    /**
     * Acknowledges removal of player relationships while cleanup still holds the slot and chain.
     */
    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid) {
        CompletableFuture<Void> accepted = new CompletableFuture<>();
        execute(islandUuid, () -> {
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

    private CompletableFuture<Void> deleteNow(Actor actor, UUID islandUuid, int slot) {
        beginDeletion(actor, islandUuid, slot);
        return clearIsland(islandUuid, slot);
    }

    private void beginDeletion(Actor actor, UUID islandUuid, int slot) {
        if (stopping) throw new IllegalStateException("Island operator stopped");
        try {
            database.beginIslandDeletion(actor, islandUuid);
        } catch (RuntimeException e) {
            // A lost commit reply is success only if the player relationships are gone too.
            // An unfinished create also has deleting=true, but still holds its owner.
            if (isRuleRejection(e) || !database.isIslandDeleting(islandUuid) || !database.getIslandPlayers(islandUuid).isEmpty())
                throw e;
        }
        islandCache.beginDeletion(islandUuid, slot);
    }

    private CompletableFuture<Void> clearIsland(UUID islandUuid, int slot) {
        return worldHandler.deleteIsland(slot).thenRunAsync(() -> {
            if (stopping) throw new IllegalStateException("Island operator stopped");
            try {
                database.finishIslandDeletion(islandUuid);
            } catch (RuntimeException e) {
                // A successful commit can lose its answer. The UUID, not the position, identifies it.
                if (isRuleRejection(e) || database.getIslandSlot(islandUuid).isPresent()) throw e;
            }
            islandCache.unload(islandUuid);
            if (limitHandler != null) limitHandler.unload(islandUuid);
            plugin.debug("IslandOperator", "Cleared and deleted island " + islandUuid + " at slot " + slot);
        }, plugin.getBukkitAsyncExecutor());
    }

    /**
     * Startup recovery and manual purge share the chain used by ordinary island edits.
     */
    public CompletableFuture<DeletionResult> retryDeletions() {
        return CompletableFuture.supplyAsync(() -> database.getDeletingIslands(serverID), plugin.getBukkitAsyncExecutor()).thenCompose(pending -> {
            var results = pending.keySet().stream().map(islandUuid -> serialized(islandUuid, () -> {
                if (!database.isIslandDeleting(islandUuid)) return CompletableFuture.completedFuture(0);
                return deleteNow(new Actor.Bypass("incomplete island deletion retry"), islandUuid, getIslandSlot(islandUuid)).thenApply(v -> 1);
            }).handle((count, error) -> {
                if (error != null) plugin.severe("Island cleanup failed; row and slot retained: " + islandUuid, error);
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

    public CompletableFuture<Boolean> toggleIslandLock(Actor actor, UUID islandUuid) {
        return execute(islandUuid, () -> refresh(islandUuid, () -> {
            return database.toggleIslandLock(actor, islandUuid);
        }).thenCompose(locked -> {
            if (!locked) {
                return CompletableFuture.completedFuture(false);
            }

            Set<UUID> allowed = new HashSet<>();
            Island island = islandCache.get(islandUuid);
            if (island == null) {
                throw new IllegalStateException("Island cache entry is missing after locking: " + islandUuid);
            }
            allowed.add(island.getOwner());
            allowed.addAll(island.getMembers());
            allowed.addAll(island.getCoops());

            return worldHandler.removePlayersFromIsland(getIslandSlot(islandUuid), player -> !allowed.contains(player.getUniqueId())).thenApply(v -> true);
        }));
    }

    public CompletableFuture<Boolean> toggleIslandPvp(Actor actor, UUID islandUuid) {
        return execute(islandUuid, () -> refresh(islandUuid, () -> {
            return database.toggleIslandPvp(actor, islandUuid);
        }));
    }

    public CompletableFuture<Void> addMember(Actor actor, UUID islandUuid, UUID playerUuid, String role) {
        return updateCache(islandUuid, () -> {
            database.addIslandPlayer(actor, islandUuid, playerUuid, role);
            return null;
        });
    }

    public CompletableFuture<Void> removeMember(Actor actor, UUID islandUuid, UUID playerUuid) {
        return execute(islandUuid, () -> refresh(islandUuid, () -> {
            database.deleteIslandPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null));
    }

    public CompletableFuture<Void> setOwner(Actor actor, UUID islandUuid, UUID newOwnerUuid) {
        return updateCache(islandUuid, () -> {
            database.updateIslandOwner(actor, islandUuid, newOwnerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> expelPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return execute(islandUuid, () -> {
            if (!onlinePlayerRegistry.isOnline(playerUuid)) {
                throw new PlayerNotOnlineException();
            }
            Set<UUID> islandPlayers = database.getIslandPlayers(islandUuid).keySet();

            if (actor instanceof Actor.Player player && !islandPlayers.contains(player.uuid())) {
                return CompletableFuture.failedFuture(new IslandDoesNotExistException());
            }

            if (islandPlayers.contains(playerUuid)) {
                return CompletableFuture.failedFuture(new CannotExpelIslandPlayerException());
            }

            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid).thenAccept(removed -> {
                if (!removed) {
                    throw new PlayerNotInIslandException();
                }
            });
        });
    }

    public CompletableFuture<Void> addBan(Actor actor, UUID islandUuid, UUID playerUuid) {
        return execute(islandUuid, () -> refresh(islandUuid, () -> {
            database.updateBanPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null));
    }

    public CompletableFuture<Void> removeBan(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateCache(islandUuid, () -> {
            database.deleteBanPlayer(actor, islandUuid, playerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> addCoop(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateCache(islandUuid, () -> {
            if (!onlinePlayerRegistry.isOnline(playerUuid)) throw new PlayerNotOnlineException();
            database.updateCoopPlayer(actor, islandUuid, playerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> removeCoop(Actor actor, UUID islandUuid, UUID playerUuid) {
        return execute(islandUuid, () -> refresh(islandUuid, () -> {
            database.deleteCoopPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null));
    }

    public CompletableFuture<Void> setHome(Actor actor, UUID islandUuid, UUID playerUuid, String homeName, String homeLocation) {
        return updateCache(islandUuid, () -> {
            database.updateHomePoint(actor, islandUuid, playerUuid, homeName, homeLocation);
            return null;
        });
    }

    public CompletableFuture<Void> deleteHome(UUID islandUuid, UUID playerUuid, String homeName) {
        return updateCache(islandUuid, () -> {
            database.deleteHomePoint(islandUuid, playerUuid, homeName);
            return null;
        });
    }

    public CompletableFuture<Void> setWarp(Actor actor, UUID islandUuid, String warpName, String warpLocation) {
        return updateCache(islandUuid, () -> {
            database.updateWarpPoint(actor, islandUuid, warpName, warpLocation);
            return null;
        });
    }

    public CompletableFuture<Void> deleteWarp(Actor actor, UUID islandUuid, String warpName) {
        return updateCache(islandUuid, () -> {
            database.deleteWarpPoint(actor, islandUuid, warpName);
            return null;
        });
    }

    public CompletableFuture<Void> setUpgradeLevel(Actor actor, UUID islandUuid, String upgradeId, int expectedLevel, int newLevel) {
        return updateCache(islandUuid, () -> {
            database.updateIslandUpgradeLevel(actor, islandUuid, upgradeId, expectedLevel, newLevel);
            return null;
        });
    }

    public CompletableFuture<Void> buyUpgrade(Actor actor, UUID islandUuid, UUID payerUuid, String upgradeId, int expectedLevel, int newLevel, double price) {
        return execute(islandUuid, () -> economy.withdraw(payerUuid, price).thenComposeAsync(v -> this.<Void>refresh(islandUuid, () -> {
            database.updateIslandUpgradeLevel(actor, islandUuid, upgradeId, expectedLevel, newLevel);
            return null;
        }).exceptionallyComposeAsync(error -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (!isRuleRejection(cause) && hasLevel(islandUuid, upgradeId, newLevel)) {
                return CompletableFuture.completedFuture(null);
            }
            return refund(payerUuid, price, upgradeId).thenCompose(refunded -> CompletableFuture.failedFuture(error));
        }, plugin.getBukkitAsyncExecutor()), plugin.getBukkitAsyncExecutor()));
    }

    private boolean hasLevel(UUID islandUuid, String upgradeId, int level) {
        try {
            return database.getIslandUpgradeLevel(islandUuid, upgradeId) == level;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private CompletableFuture<Void> refund(UUID payerUuid, double amount, String upgradeId) {
        return economy.deposit(payerUuid, amount).handle((success, error) -> {
            String message = "Could not refund " + amount + " to " + payerUuid + " after a failed " + upgradeId + " upgrade purchase.";
            if (error != null) {
                plugin.severe(message, error);
            } else if (!success) {
                plugin.severe(message);
            }
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

    /**
     * Existing-island work runs only on its host, including administrative and remote requests.
     */
    private <T> CompletableFuture<T> execute(UUID islandUuid, Supplier<CompletableFuture<T>> operation) {
        return serialized(islandUuid, () -> {
            String host = database.getIslandServer(islandUuid).orElseThrow(IslandDoesNotExistException::new);
            if (!serverID.equals(host)) throw new IslandNotHostedHereException(host);
            return operation.get();
        });
    }

    private <T> CompletableFuture<T> updateCache(UUID islandUuid, Supplier<T> mutation) {
        return execute(islandUuid, () -> refresh(islandUuid, mutation));
    }

    /**
     * Keeps world effects and cache refresh inside the same island operation.
     */
    private <T> CompletableFuture<T> refresh(UUID islandUuid, Supplier<T> mutation) {
        try {
            T result = mutation.get();
            return islandCache.reload(islandUuid).handle((v, error) -> {
                if (error != null)
                    plugin.severe("Island data saved but could not refresh the island cache: " + islandUuid, error);
                return result;
            });
        } catch (Throwable error) {
            if (isRuleRejection(error)) return CompletableFuture.failedFuture(error);
            return islandCache.reload(islandUuid).handle((v, reloadError) -> null).thenCompose(v -> CompletableFuture.failedFuture(error));
        }
    }

    private static boolean isRuleRejection(Throwable error) {
        return error.getClass().getPackageName().equals(IslandDoesNotExistException.class.getPackageName());
    }

    /**
     * Registers in call order before scheduling, and holds the chain until world effects finish.
     */
    private <T> CompletableFuture<T> serialized(UUID islandUuid, Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<Void> previous = chains.put(islandUuid, done);
        Runnable run = () -> supply(operation).whenComplete((value, error) -> {
            done.complete(null);
            chains.remove(islandUuid, done);
            if (error != null) result.completeExceptionally(error);
            else result.complete(value);
        });
        CompletableFuture<Void> ready = previous == null ? CompletableFuture.completedFuture(null) : previous;
        ready.thenRunAsync(run, plugin.getBukkitAsyncExecutor());
        return result;
    }

    public record DeletionResult(int purged, int deferred) {
    }
}
