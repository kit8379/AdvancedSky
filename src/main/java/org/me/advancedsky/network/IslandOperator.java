package org.me.advancedsky.network;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.economy.EconomyHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.island.LimitHandler;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.teleport.TeleportHandler;
import org.me.advancedsky.util.LocationUtils;
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
 * Executes island work on this server, the island's host. Every operation on an island runs in
 * that island's serial chain, so a delete can never interleave with a mutation or with the create
 * that is still pasting the same island. Mutations refresh the island cache afterwards.
 */
public class IslandOperator {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final WorldHandler worldHandler;
    private final TeleportHandler teleportHandler;
    private final IslandCache islandCache;
    private final LimitHandler limitHandler;
    private final EconomyHandler economy;
    private final String serverID;

    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
    // Creates past their insert and not yet done, by island: the slot each is building.
    private final Map<UUID, Integer> creating = new ConcurrentHashMap<>();

    public IslandOperator(AdvancedSky plugin, DatabaseHandler database, WorldHandler worldHandler, TeleportHandler teleportHandler, IslandCache islandCache, LimitHandler limitHandler, EconomyHandler economy, String serverID) {
        this.plugin = plugin;
        this.database = database;
        this.worldHandler = worldHandler;
        this.teleportHandler = teleportHandler;
        this.islandCache = islandCache;
        this.limitHandler = limitHandler;
        this.economy = economy;
        this.serverID = serverID;
    }

    public CompletableFuture<Void> createIsland(UUID islandUuid, UUID ownerUuid) {
        return serialized(islandUuid, () -> {
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

            return islandCache.load(islandUuid).thenCompose(v -> {
                return worldHandler.createIsland(createdSlot);
            }).thenRunAsync(() -> {
                plugin.debug("IslandOperator", "Created island " + islandUuid + " at slot " + createdSlot + " on server: " + serverID);
            }, plugin.getBukkitAsyncExecutor()).exceptionallyComposeAsync(e -> {
                return cleanupFailedCreate(islandUuid, createdSlot).thenCompose(v -> {
                    return CompletableFuture.failedFuture(e);
                });
            }, plugin.getBukkitAsyncExecutor()).whenComplete((v, e) -> creating.remove(islandUuid));
        });
    }

    /**
     * Rolls back the creates still building when the plugin disables: no scheduler is left to
     * finish or clean them up, and a half-pasted island would stay. Their rows go now and their
     * areas at the next startup.
     */
    public void abortCreates() {
        creating.forEach((islandUuid, slot) -> {
            try {
                database.deleteIsland(new Actor.Bypass("island create interrupted by shutdown"), islandUuid);
            } catch (RuntimeException e) {
                plugin.severe("Could not roll back the island create interrupted by shutdown: " + islandUuid, e);
            }
            worldHandler.purgeArea(slot);
            plugin.warning("Rolled back the create of island " + islandUuid + " at slot " + slot + ", interrupted by shutdown");
        });
        creating.clear();
    }

    /**
     * Deletes the island's rows, then sends everyone off its area, whose region files go at the
     * next startup. Completes once the rows are gone: nothing can reach the island or reuse its
     * slot any more.
     */
    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid) {
        return serialized(islandUuid, () -> {
            int slot = getIslandSlot(islandUuid);
            try {
                database.deleteIsland(actor, islandUuid);
            } catch (RuntimeException e) {
                // A commit whose answer was lost may still have landed: then the island is gone.
                if (isRuleRejection(e) || database.getIslandSlot(islandUuid).isPresent()) {
                    throw e;
                }
            }
            islandCache.unload(islandUuid);
            limitHandler.unload(islandUuid);

            worldHandler.deleteIsland(slot).whenComplete((v, e) -> {
                if (e != null) {
                    plugin.severe("Island rows deleted but not everyone could be sent off its area at slot " + slot + ": " + islandUuid, e);
                } else {
                    plugin.debug("IslandOperator", "Deleted island " + islandUuid + " at slot " + slot);
                }
            });
            return CompletableFuture.completedFuture(null);
        });
    }

    public CompletableFuture<Boolean> prepareTeleport(UUID playerUuid, String teleportWorld, String teleportLocation) {
        return teleportOrStore(playerUuid, teleportWorld, teleportLocation);
    }

    /**
     * Teleports the player at once when they are on this server, otherwise stores the teleport
     * for their next join here. True when teleported in place; fails with
     * TeleportCancelledException when a listener cancelled the teleport in place.
     */
    private CompletableFuture<Boolean> teleportOrStore(UUID playerUuid, String teleportWorld, String teleportLocation) {
        return CompletableFuture.supplyAsync(() -> {
            Location location = LocationUtils.stringToLocation(teleportWorld, teleportLocation);
            Player player = Bukkit.getPlayer(playerUuid);

            // teleportAsync runs on the thread that owns the player; a player who left meanwhile
            // gets the teleport stored for their return, like one who was never here.
            CompletableFuture<Boolean> teleported = new CompletableFuture<>();
            if (player != null && player.getScheduler().run(plugin, task -> {
                try {
                    // Folia refuses to teleport a passenger; Paper dismounts one itself.
                    player.leaveVehicle();
                    player.teleportAsync(location).whenComplete((moved, error) -> {
                        if (error != null) {
                            teleported.completeExceptionally(error);
                        } else if (!moved) {
                            teleported.completeExceptionally(new TeleportCancelledException());
                        } else {
                            plugin.debug("IslandOperator", "Teleported player " + playerUuid + " to location: " + teleportLocation + " in world: " + teleportWorld);
                            teleported.complete(true);
                        }
                    });
                } catch (Throwable error) {
                    teleported.completeExceptionally(error);
                }
            }, () -> teleported.complete(store(playerUuid, location))) != null) {
                return teleported;
            }

            return CompletableFuture.completedFuture(store(playerUuid, location));
        }, plugin.getGlobalRegionExecutor()).thenCompose(teleported -> teleported);
    }

    /**
     * Keeps the teleport for the player's next join here. False: not teleported in place.
     */
    private boolean store(UUID playerUuid, Location location) {
        teleportHandler.addPendingTeleport(playerUuid, location);
        plugin.debug("IslandOperator", "Stored pending teleport for player " + playerUuid + " to " + location);
        return false;
    }

    public CompletableFuture<Void> expelPlayer(Actor actor, UUID islandUuid, UUID playerUuid) {
        return serialized(islandUuid, () -> {
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

    public CompletableFuture<Void> addMember(Actor actor, UUID islandUuid, UUID playerUuid, String role) {
        return updateCache(islandUuid, () -> {
            database.addIslandPlayer(actor, islandUuid, playerUuid, role);
            return null;
        });
    }

    public CompletableFuture<Void> removeMember(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateCache(islandUuid, () -> {
            database.deleteIslandPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null);
    }

    public CompletableFuture<Void> setOwner(Actor actor, UUID islandUuid, UUID newOwnerUuid) {
        return updateCache(islandUuid, () -> {
            database.updateIslandOwner(actor, islandUuid, newOwnerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> addBan(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateCache(islandUuid, () -> {
            database.updateBanPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null);
    }

    public CompletableFuture<Void> removeBan(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateCache(islandUuid, () -> {
            database.deleteBanPlayer(actor, islandUuid, playerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> addCoop(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateCache(islandUuid, () -> {
            database.updateCoopPlayer(actor, islandUuid, playerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> removeCoop(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateCache(islandUuid, () -> {
            database.deleteCoopPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null);
    }

    public CompletableFuture<Boolean> toggleIslandLock(Actor actor, UUID islandUuid) {
        return updateCache(islandUuid, () -> {
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
        });
    }

    public CompletableFuture<Boolean> toggleIslandPvp(Actor actor, UUID islandUuid) {
        return updateCache(islandUuid, () -> {
            return database.toggleIslandPvp(actor, islandUuid);
        });
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

    /**
     * The island size a new level may bring reaches players through their border on their next
     * move; nothing else has to be pushed.
     */
    public CompletableFuture<Void> setUpgradeLevel(Actor actor, UUID islandUuid, String upgradeId, int expectedLevel, int newLevel) {
        return updateCache(islandUuid, () -> {
            database.updateIslandUpgradeLevel(actor, islandUuid, upgradeId, expectedLevel, newLevel);
            return null;
        });
    }

    /**
     * Withdraws the price and compare-and-sets the level here on the host, refunding a failed
     * write itself, so the payment and the level agree however long a requester waits.
     */
    public CompletableFuture<Void> buyUpgrade(Actor actor, UUID islandUuid, UUID payerUuid, String upgradeId, int expectedLevel, int newLevel, double price) {
        return economy.withdraw(payerUuid, price).thenComposeAsync(v -> setUpgradeLevel(actor, islandUuid, upgradeId, expectedLevel, newLevel).exceptionallyComposeAsync(error -> {
            // A write whose commit answer was lost may still have landed: then it is paid for.
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (!isRuleRejection(cause) && hasLevel(islandUuid, upgradeId, newLevel)) {
                return CompletableFuture.completedFuture(null);
            }
            return refund(payerUuid, price, upgradeId).thenCompose(refunded -> CompletableFuture.failedFuture(error));
        }, plugin.getBukkitAsyncExecutor()), plugin.getBukkitAsyncExecutor());
    }

    /**
     * Whether the upgrade stands at the level; false when the database cannot tell.
     */
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

    /**
     * Runs the mutation in the island's chain and refreshes the island cache afterwards. A rule
     * the database refused rolled its transaction back, so the entry stays as it is; any other
     * failure may have committed, so the entry is refreshed then too.
     */
    private <T> CompletableFuture<T> updateCache(UUID islandUuid, Supplier<T> mutation) {
        return serialized(islandUuid, () -> {
            try {
                T result = mutation.get();
                return islandCache.reload(islandUuid).handle((v, reloadError) -> {
                    if (reloadError != null) {
                        plugin.severe("Island data saved but could not refresh the island cache: " + islandUuid, reloadError);
                    }
                    return result;
                });
            } catch (Throwable error) {
                if (isRuleRejection(error)) {
                    return CompletableFuture.failedFuture(error);
                }

                return islandCache.reload(islandUuid).handle((v, reloadError) -> null).thenCompose(v -> CompletableFuture.failedFuture(error));
            }
        });
    }

    /**
     * The typed exceptions the database raises for a broken rule, always before its
     * transaction commits.
     */
    private static boolean isRuleRejection(Throwable error) {
        return error.getClass().getPackageName().equals(IslandDoesNotExistException.class.getPackageName());
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

            islandCache.unload(islandUuid);
        }, plugin.getBukkitAsyncExecutor());
    }

    private int getIslandSlot(UUID islandUuid) {
        return database.getIslandSlot(islandUuid).orElseThrow(IslandDoesNotExistException::new);
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
