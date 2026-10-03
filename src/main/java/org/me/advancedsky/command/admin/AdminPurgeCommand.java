package org.me.advancedsky.command.admin;

import org.bukkit.command.CommandSender;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;

/** Retries incomplete deletions on this host, clearing the physical area before deleting its row. */
public final class AdminPurgeCommand implements SubCommand {
    private final AdvancedSky plugin;
    private final ConfigHandler config;

    public AdminPurgeCommand(AdvancedSky plugin, ConfigHandler config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override public String getName() { return "purge"; }
    @Override public String[] getAliases() { return config.getAdminPurgeAliases(); }
    @Override public String getPermission() { return config.getAdminPurgePermission(); }
    @Override public String getSyntax() { return config.getAdminPurgeSyntax(); }
    @Override public String getDescription() { return config.getAdminPurgeDescription(); }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length != 1) return false;
        if (config.isLobbyOnly()) {
            sender.sendMessage(config.getRegionPurgeIslandServerMessage());
            return true;
        }
        sender.sendMessage(config.getRegionPurgeStartedMessage());
        plugin.purgeDeletedRegions().thenAccept(result ->
                sender.sendMessage(config.getRegionPurgeFinishedMessage(result.purged(), result.deferred())))
                .exceptionally(error -> {
                    sender.sendMessage(config.getUnknownExceptionMessage());
                    plugin.severe("Manual region purge failed", error);
                    return null;
                });
        return true;
    }

}
