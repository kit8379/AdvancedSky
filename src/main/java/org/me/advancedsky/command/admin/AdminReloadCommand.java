package org.me.advancedsky.command.admin;

import org.bukkit.command.CommandSender;
import org.me.advancedsky.AdvancedSky;
import org.me.advancedsky.command.SubCommand;
import org.me.advancedsky.config.ConfigHandler;


/**
 * /isadmin reload
 */
public class AdminReloadCommand implements SubCommand {
    private final AdvancedSky plugin;
    private final ConfigHandler config;

    public AdminReloadCommand(AdvancedSky plugin, ConfigHandler config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String[] getAliases() {
        return config.getAdminReloadAliases();
    }

    @Override
    public String getPermission() {
        return config.getAdminReloadPermission();
    }

    @Override
    public String getSyntax() {
        return config.getAdminReloadSyntax();
    }

    @Override
    public String getDescription() {
        return config.getAdminReloadDescription();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        plugin.reload();
        sender.sendMessage(config.getPluginReloadedMessage());
        return true;
    }
}
