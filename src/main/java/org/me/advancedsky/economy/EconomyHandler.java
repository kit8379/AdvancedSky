package org.me.advancedsky.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.exceptions.InsufficientFundsException;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Vault provider access and economy operations. Provider calls run on the global region thread. */
public class EconomyHandler {
    private final AdvancedSky plugin;

    public EconomyHandler(AdvancedSky plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Double> getBalance(UUID playerUuid) {
        return CompletableFuture.supplyAsync(() -> economy().getBalance(Bukkit.getOfflinePlayer(playerUuid)), plugin.getGlobalRegionExecutor());
    }

    public CompletableFuture<Void> withdraw(UUID playerUuid, double amount) {
        return CompletableFuture.supplyAsync(() -> {
            if (!economy().withdrawPlayer(Bukkit.getOfflinePlayer(playerUuid), amount).transactionSuccess()) {
                throw new InsufficientFundsException();
            }
            return null;
        }, plugin.getGlobalRegionExecutor());
    }

    public CompletableFuture<Boolean> deposit(UUID playerUuid, double amount) {
        return CompletableFuture.supplyAsync(() -> economy().depositPlayer(Bukkit.getOfflinePlayer(playerUuid), amount).transactionSuccess(), plugin.getGlobalRegionExecutor());
    }

    public CompletableFuture<String> format(double amount) {
        return CompletableFuture.supplyAsync(() -> economy().format(amount), plugin.getGlobalRegionExecutor());
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            throw new IllegalStateException("No Vault economy provider is registered");
        }
        return registration.getProvider();
    }

}
