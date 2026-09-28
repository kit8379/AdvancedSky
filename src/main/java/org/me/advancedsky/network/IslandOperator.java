package org.me.advancedsky.network;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.exceptions.*;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Island;
import org.me.advancedsky.snapshot.IslandSnapshot;
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
 * that is still pasting the same island. Mutations refresh the island's snapshot afterwards.
 */
public class IslandOperator {

    private final AdvancedSky plugin;
    private final DatabaseHandler database;
    private final WorldHandler worldHandler;
    private final TeleportHandler teleportHandler;
    private final IslandSnapshot islandSnapshot;
    private final String serverID;

    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();

    public IslandOperator(AdvancedSky plugin, DatabaseHandler database, WorldHandler worldHandler, TeleportHandler teleportHandler, IslandSnapshot islandSnapshot, String serverID) {
        this.plugin = plugin;
        this.database = database;
        this.worldHandler = worldHandler;
        this.teleportHandler = teleportHandler;
        this.islandSnapshot = islandSnapshot;
        this.serverID = serverID;
    }

    public CompletableFuture<Void> createIsland(UUID islandUuid, UUID ownerUuid) {
        return serialized(islandUuid, () -> {
            int slot = database.addIslandData(islandUuid, ownerUuid, serverID);

            return islandSnapshot.load(islandUuid).thenCompose(v -> {
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
     * Deletes the island's rows, then clears its area.
     */
    public CompletableFuture<Void> deleteIsland(Actor actor, UUID islandUuid) {
        return serialized(islandUuid, () -> {
            int slot = getIslandSlot(islandUuid);
            database.deleteIsland(actor, islandUuid);
            islandSnapshot.unload(islandUuid);

            return worldHandler.deleteIsland(slot).exceptionally(e -> {
                plugin.severe("Island rows deleted but the area could not be cleared, leaving orphaned blocks at slot " + slot + ": " + islandUuid, e);
                return null;
            }).thenRunAsync(() -> {
                plugin.debug("IslandOperator", "Deleted island " + islandUuid + " at slot " + slot);
            }, plugin.getBukkitAsyncExecutor());
        });
    }

    public CompletableFuture<Boolean> prepareTeleport(UUID playerUuid, String teleportWorld, String teleportLocation) {
        return teleportOrStore(playerUuid, teleportWorld, teleportLocation);
    }

    /**
     * Teleports the player at once when they are on this server, otherwise stores the teleport
     * for their next join here. True when teleported in place.
     */
    private CompletableFuture<Boolean> teleportOrStore(UUID playerUuid, String teleportWorld, String teleportLocation) {
        return CompletableFuture.supplyAsync(() -> {
            Location location = LocationUtils.stringToLocation(teleportWorld, teleportLocation);
            Player player = Bukkit.getPlayer(playerUuid);

            // teleportAsync runs on the thread that owns the player; a player who left meanwhile gets no task.
            if (player != null && player.getScheduler().run(plugin, task -> player.teleportAsync(location), null) != null) {
                plugin.debug("IslandOperator", "Teleported player " + playerUuid + " to location: " + teleportLocation + " in world: " + teleportWorld);
                return true;
            }

            teleportHandler.addPendingTeleport(playerUuid, location);
            plugin.debug("IslandOperator", "Stored pending teleport for player " + playerUuid + " to location: " + teleportLocation + " in world: " + teleportWorld);
            return false;
        }, plugin.getGlobalRegionExecutor());
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
        return updateSnapshot(islandUuid, () -> {
            database.addIslandPlayer(actor, islandUuid, playerUuid, role);
            return null;
        });
    }

    public CompletableFuture<Void> removeMember(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateSnapshot(islandUuid, () -> {
            database.deleteIslandPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null);
    }

    public CompletableFuture<Void> setOwner(Actor actor, UUID islandUuid, UUID newOwnerUuid) {
        return updateSnapshot(islandUuid, () -> {
            database.updateIslandOwner(actor, islandUuid, newOwnerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> addBan(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateSnapshot(islandUuid, () -> {
            database.updateBanPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null);
    }

    public CompletableFuture<Void> removeBan(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateSnapshot(islandUuid, () -> {
            database.deleteBanPlayer(actor, islandUuid, playerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> addCoop(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateSnapshot(islandUuid, () -> {
            database.updateCoopPlayer(actor, islandUuid, playerUuid);
            return null;
        });
    }

    public CompletableFuture<Void> removeCoop(Actor actor, UUID islandUuid, UUID playerUuid) {
        return updateSnapshot(islandUuid, () -> {
            database.deleteCoopPlayer(actor, islandUuid, playerUuid);
            return null;
        }).thenCompose(v -> {
            return worldHandler.removePlayerFromIsland(getIslandSlot(islandUuid), playerUuid);
        }).thenApply(removed -> null);
    }

    public CompletableFuture<Boolean> toggleIslandLock(Actor actor, UUID islandUuid) {
        return updateSnapshot(islandUuid, () -> {
            return database.toggleIslandLock(actor, islandUuid);
        }).thenCompose(locked -> {
            if (!locked) {
                return CompletableFuture.completedFuture(false);
            }

            Set<UUID> allowed = new HashSet<>();
            Island island = islandSnapshot.get(islandUuid);
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
        return updateSnapshot(islandUuid, () -> {
            return database.toggleIslandPvp(actor, islandUuid);
        });
    }

    public CompletableFuture<Void> setHome(Actor actor, UUID islandUuid, UUID playerUuid, String homeName, String homeLocation) {
        return updateSnapshot(islandUuid, () -> {
            database.updateHomePoint(actor, islandUuid, playerUuid, homeName, homeLocation);
            return null;
        });
    }

    public CompletableFuture<Void> deleteHome(UUID islandUuid, UUID playerUuid, String homeName) {
        return updateSnapshot(islandUuid, () -> {
            database.deleteHomePoint(islandUuid, playerUuid, homeName);
            return null;
        });
    }

    public CompletableFuture<Void> setWarp(Actor actor, UUID islandUuid, String warpName, String warpLocation) {
        return updateSnapshot(islandUuid, () -> {
            database.updateWarpPoint(actor, islandUuid, warpName, warpLocation);
            return null;
        });
    }

    public CompletableFuture<Void> deleteWarp(Actor actor, UUID islandUuid, String warpName) {
        return updateSnapshot(islandUuid, () -> {
            database.deleteWarpPoint(actor, islandUuid, warpName);
            return null;
        });
    }

    /**
     * The island size a new level may bring reaches players through their border on their next
     * move; nothing else has to be pushed.
     */
    public CompletableFuture<Void> setUpgradeLevel(Actor actor, UUID islandUuid, String upgradeId, int expectedLevel, int newLevel) {
        return updateSnapshot(islandUuid, () -> {
            database.updateIslandUpgradeLevel(actor, islandUuid, upgradeId, expectedLevel, newLevel);
            return null;
        });
    }

    /**
     * Runs the mutation in the island's chain and refreshes the snapshot afterwards, whether the
     * mutation succeeded or not.
     */
    private <T> CompletableFuture<T> updateSnapshot(UUID islandUuid, Supplier<T> mutation) {
        return serialized(islandUuid, () -> {
            try {
                T result = mutation.get();
                return islandSnapshot.reload(islandUuid).handle((v, reloadError) -> {
                    if (reloadError != null) {
                        plugin.severe("Island data saved but could not refresh the snapshot: " + islandUuid, reloadError);
                    }
                    return result;
                });
            } catch (Throwable error) {
                return islandSnapshot.reload(islandUuid).handle((v, reloadError) -> null).thenCompose(v -> CompletableFuture.failedFuture(error));
            }
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

            islandSnapshot.unload(islandUuid);
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
