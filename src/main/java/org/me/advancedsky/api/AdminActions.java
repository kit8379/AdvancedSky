package org.me.advancedsky.api;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.me.advancedsky.island.BanHandler;
import org.me.advancedsky.island.BiomeHandler;
import org.me.advancedsky.island.CoopHandler;
import org.me.advancedsky.island.CoreHandler;
import org.me.advancedsky.island.HomeHandler;
import org.me.advancedsky.island.PlayerHandler;
import org.me.advancedsky.island.UpgradeHandler;
import org.me.advancedsky.island.WarpHandler;
import org.me.advancedsky.model.Actor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Operator-level actions on arbitrary islands and players, exempt from every player-facing rule.
 * Obtained via {@code api.admin(sender)}. Management runs on the target island's host; creation
 * and travel can span servers. The sender's name identifies each bypass in logs.
 */
public final class AdminActions {

    private final Actor actor;
    private final CoreHandler coreHandler;
    private final PlayerHandler playerHandler;
    private final HomeHandler homeHandler;
    private final WarpHandler warpHandler;
    private final BanHandler banHandler;
    private final CoopHandler coopHandler;
    private final BiomeHandler biomeHandler;
    private final UpgradeHandler upgradeHandler;

    AdminActions(String source, CoreHandler coreHandler, PlayerHandler playerHandler, HomeHandler homeHandler, WarpHandler warpHandler, BanHandler banHandler, CoopHandler coopHandler, BiomeHandler biomeHandler, UpgradeHandler upgradeHandler) {
        this.actor = new Actor.Bypass(source);
        this.coreHandler = coreHandler;
        this.playerHandler = playerHandler;
        this.homeHandler = homeHandler;
        this.warpHandler = warpHandler;
        this.banHandler = banHandler;
        this.coopHandler = coopHandler;
        this.biomeHandler = biomeHandler;
        this.upgradeHandler = upgradeHandler;
    }

    // ---- island lifecycle -------------------------------------------------------------------

    public CompletableFuture<Void> createIsland(UUID ownerPlayerUuid) {
        return coreHandler.createIsland(ownerPlayerUuid);
    }

    /** Completes after logical deletion; the host continues clearing the reserved area. */
    public CompletableFuture<Void> deleteIsland(UUID islandUuid) {
        return coreHandler.deleteIsland(actor, islandUuid);
    }

    // ---- membership and trust ---------------------------------------------------------------

    /** Always grants the member role: ownership moves exclusively through {@link #setOwner}. */
    public CompletableFuture<Void> addMember(UUID islandUuid, UUID playerUuid) {
        return playerHandler.addMember(actor, islandUuid, playerUuid, "member");
    }

    public CompletableFuture<Void> removeMember(UUID islandUuid, UUID playerUuid) {
        return playerHandler.removeMember(actor, islandUuid, playerUuid);
    }

    public CompletableFuture<Void> setOwner(UUID islandUuid, UUID newOwnerUuid) {
        return playerHandler.setOwner(actor, islandUuid, newOwnerUuid);
    }

    public CompletableFuture<Void> banPlayer(UUID islandUuid, UUID targetUuid) {
        return banHandler.banPlayer(actor, islandUuid, targetUuid);
    }

    public CompletableFuture<Void> unbanPlayer(UUID islandUuid, UUID targetUuid) {
        return banHandler.unbanPlayer(actor, islandUuid, targetUuid);
    }

    public CompletableFuture<Void> addCoop(UUID islandUuid, UUID targetUuid) {
        return coopHandler.coopPlayer(actor, islandUuid, targetUuid);
    }

    public CompletableFuture<Void> removeCoop(UUID islandUuid, UUID targetUuid) {
        return coopHandler.unCoopPlayer(actor, islandUuid, targetUuid);
    }

    public CompletableFuture<Void> expelPlayer(UUID islandUuid, UUID targetUuid) {
        return playerHandler.expelPlayer(actor, islandUuid, targetUuid);
    }

    public CompletableFuture<Boolean> toggleLock(UUID islandUuid) {
        return coreHandler.toggleIslandLock(actor, islandUuid);
    }

    public CompletableFuture<Boolean> togglePvp(UUID islandUuid) {
        return coreHandler.toggleIslandPvp(actor, islandUuid);
    }

    // ---- homes and warps (on anyone's behalf) -------------------------------------------------

    public CompletableFuture<Void> setHome(UUID homeOwnerUuid, String homeName, Location location) {
        return homeHandler.setHome(actor, homeOwnerUuid, homeName, location);
    }

    public CompletableFuture<Void> deleteHome(UUID homeOwnerUuid, String homeName) {
        return homeHandler.delHome(homeOwnerUuid, homeName);
    }

    public CompletableFuture<Void> home(UUID homeOwnerUuid, String homeName, UUID teleportPlayerUuid) {
        return home(homeOwnerUuid, homeName, teleportPlayerUuid, null);
    }

    /** Sends the optional message to the traveler only after arrival succeeds. */
    public CompletableFuture<Void> home(UUID homeOwnerUuid, String homeName, UUID teleportPlayerUuid, Component successMessage) {
        return successMessage == null
                ? homeHandler.home(homeOwnerUuid, homeName, teleportPlayerUuid)
                : homeHandler.home(homeOwnerUuid, homeName, teleportPlayerUuid, successMessage);
    }

    public CompletableFuture<Void> setWarp(UUID islandUuid, String warpName, Location location) {
        return warpHandler.setWarp(actor, islandUuid, warpName, location);
    }

    public CompletableFuture<Void> deleteWarp(UUID islandUuid, String warpName) {
        return warpHandler.delWarp(actor, islandUuid, warpName);
    }

    public CompletableFuture<Void> warp(UUID islandUuid, String warpName, UUID teleportPlayerUuid) {
        return warp(islandUuid, warpName, teleportPlayerUuid, null);
    }

    /** Sends the optional message to the traveler only after arrival succeeds. */
    public CompletableFuture<Void> warp(UUID islandUuid, String warpName, UUID teleportPlayerUuid, Component successMessage) {
        return successMessage == null
                ? warpHandler.warp(actor, islandUuid, warpName, teleportPlayerUuid)
                : warpHandler.warp(actor, islandUuid, warpName, teleportPlayerUuid, successMessage);
    }

    // ---- world ------------------------------------------------------------------------------

    /**
     * Re-biome the chunk holding the location, in any loaded world of this server.
     */
    public CompletableFuture<Void> applyBiome(Location location, String biomeName) {
        return biomeHandler.applyChunkBiome(location, biomeName);
    }

    // ---- upgrades ---------------------------------------------------------------------------

    /** Sets the level outright, no island level or price check; the level must exist in the config. */
    public CompletableFuture<Void> setUpgradeLevel(UUID islandUuid, String upgradeId, int level) {
        return upgradeHandler.setUpgradeLevel(actor, islandUuid, upgradeId, level);
    }
}
