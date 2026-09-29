package org.me.advancedsky.network;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.cache.IslandCache;
import org.me.advancedsky.database.DatabaseHandler;
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
    private final String serverID;

    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

    public IslandOperator(AdvancedSky plugin, DatabaseHandler database, WorldHandler worldHandler, TeleportHandler teleportHandler, IslandCache islandCache, LimitHandler limitHandler, String serverID) {
        this.plugin = plugin;
        this.database = database;
        this.worldHandler = worldHandler;
        this.teleportHandler = teleportHandler;
        this.islandCache = islandCache;
        this.limitHandler = limitHandler;
        this.serverID = serverID;
    }

    public CompletableFuture<Void> createIsland(UUID islandUuid, UUID ownerUuid) {
        return serialized(islandUuid, () -> {
            int slot = database.addIslandData(islandUuid, ownerUuid, serverID);

            return islandCache.load(islandUuid).thenCompose(v -> {
                return worldHandler.createIsland(slot);
            }).thenRunAsync(() -> {
                plugin.debug("IslandOperator", "Created island " + islandUuid + " at slot " + slot + " on server: " + serverID);
            }, plugin.getBukkitAsyncExecutor()).exceptionallyComposeAsync(e -> {
                return cleanupFailedCreate(islandUuid, slot).thenCompose(v -> {
                    return CompletableFuture.failedFuture(e);
                });
            }, plugin.getBukkitAsyncExecutor());
        });
    }

    /**
     * Deletes the island's rows, then clears its area. Completes once the rows are gone: clearing
     * takes longer than a remote requester waits, and nothing can reach the island or reuse its
     * slot any more.
     */
    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid) {
        return serialized(islandUuid, () -> {
            int slot = getIslandSlot(islandUuid);
            database.deleteIsland(actor, islandUuid);
            islandCache.unload(islandUuid);
            limitHandler.unload(islandUuid);

            worldHandler.deleteIsland(slot).whenComplete((v, e) -> {
                if (e != null) {
                    plugin.severe("Island rows deleted but the area could not be cleared, leaving orphaned blocks at slot " + slot + ": " + islandUuid, e);
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
