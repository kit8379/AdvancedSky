package org.me.advancedsky.island;

import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.config.ConfigHandler;
import org.me.advancedsky.database.DatabaseHandler;
import org.me.advancedsky.economy.EconomyHandler;
import org.me.advancedsky.exceptions.UpgradeDoesNotExistException;
import org.me.advancedsky.exceptions.UpgradeIslandLevelTooLowException;
import org.me.advancedsky.exceptions.UpgradeLevelDoesNotExistException;
import org.me.advancedsky.exceptions.UpgradeMaxedException;
import org.me.advancedsky.model.Actor;
import org.me.advancedsky.model.Upgrade;
import org.me.advancedsky.network.IslandOperator;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Island upgrades: one level per island for each upgrade id in the config, bought with Vault
 * money and gated by island level. Limits are enforced inside database write transactions;
 * island size is applied by the host to its island cache, updating protection and movement limits.
 */
public class UpgradeHandler {

    private final AdvancedSky plugin;
    private final ConfigHandler config;
    private final DatabaseHandler database;
    private final IslandOperator islandOperator;
    private final EconomyHandler economy;

    public UpgradeHandler(AdvancedSky plugin, ConfigHandler config, DatabaseHandler database, IslandOperator islandOperator, EconomyHandler economy) {
        this.plugin = plugin;
        this.config = config;
        this.database = database;
        this.islandOperator = islandOperator;
        this.economy = economy;
    }

    /**
     * Checks and charges the purchase on the island host, refunding a failed write.
     * Completes with the level bought.
     */
    public CompletableFuture<Integer> buyUpgrade(Actor actor, UUID islandUuid, UUID payerUuid, String upgradeId) {
        return CompletableFuture.supplyAsync(() -> {
            if (!config.isUpgrade(upgradeId)) {
                throw new UpgradeDoesNotExistException();
            }

            int current = database.getIslandUpgradeLevel(islandUuid, upgradeId);
            if (current >= config.getUpgradeLevels(upgradeId).getLast()) {
                throw new UpgradeMaxedException();
            }

            int next = current + 1;
            if (database.getIslandLevel(islandUuid) < config.getUpgradeRequireLevel(upgradeId, next)) {
                throw new UpgradeIslandLevelTooLowException();
            }

            return new Purchase(current, next, config.getUpgradePrice(upgradeId, next));
        }, plugin.getBukkitAsyncExecutor()).thenComposeAsync(purchase -> islandOperator.buyUpgrade(actor, islandUuid, payerUuid, upgradeId, purchase.current(), purchase.next(), purchase.price()).thenApply(v -> purchase.next()), plugin.getBukkitAsyncExecutor());
    }

    /**
     * Operator set: no island level or price check. Still compare-and-set against the level
     * read here, so a purchase landing in between is reported instead of overwritten.
     */
    public CompletableFuture<Void> setUpgradeLevel(Actor actor, UUID islandUuid, String upgradeId, int level) {
        return CompletableFuture.supplyAsync(() -> {
            if (!config.isUpgrade(upgradeId)) {
                throw new UpgradeDoesNotExistException();
            }

            if (!config.getUpgradeLevels(upgradeId).contains(level)) {
                throw new UpgradeLevelDoesNotExistException();
            }

            return database.getIslandUpgradeLevel(islandUuid, upgradeId);
        }, plugin.getBukkitAsyncExecutor()).thenComposeAsync(current -> islandOperator.setUpgradeLevel(actor, islandUuid, upgradeId, current, level), plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Integer> getUpgradeLevel(UUID islandUuid, String upgradeId) {
        return CompletableFuture.supplyAsync(() -> {
            if (!config.isUpgrade(upgradeId)) {
                throw new UpgradeDoesNotExistException();
            }
            return database.getIslandUpgradeLevel(islandUuid, upgradeId);
        }, plugin.getBukkitAsyncExecutor());
    }

    public CompletableFuture<Upgrade> getUpgradeDetails(UUID islandUuid, UUID playerUuid, String upgradeId) {
        return CompletableFuture.supplyAsync(() -> {
            if (!config.isUpgrade(upgradeId)) {
                throw new UpgradeDoesNotExistException();
            }
            return new Progress(database.getIslandUpgradeLevel(islandUuid, upgradeId), database.getIslandLevel(islandUuid));
        }, plugin.getBukkitAsyncExecutor()).thenCompose(progress -> economy.getBalance(playerUuid).thenCompose(balance -> {
            int current = progress.currentLevel();
            String currentValue = config.getUpgradeValue(upgradeId, current);
            if (current >= config.getUpgradeLevels(upgradeId).getLast()) {
                return economy.format(balance).thenApply(formattedBalance -> new Upgrade(current, currentValue, true, 0, null, 0, null, false, progress.islandLevel(), formattedBalance));
            }

            int next = current + 1;
            int requireLevel = config.getUpgradeRequireLevel(upgradeId, next);
            double price = config.getUpgradePrice(upgradeId, next);
            boolean available = progress.islandLevel() >= requireLevel && balance >= price;
            return economy.format(price).thenCombine(economy.format(balance), (formattedPrice, formattedBalance) -> new Upgrade(current, currentValue, false, next, config.getUpgradeValue(upgradeId, next), requireLevel, formattedPrice, available, progress.islandLevel(), formattedBalance));
        }));
    }

    private record Purchase(int current, int next, double price) {
    }

    private record Progress(int currentLevel, int islandLevel) {
    }

}
