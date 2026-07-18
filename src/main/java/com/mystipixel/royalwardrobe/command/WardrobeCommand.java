package com.mystipixel.royalwardrobe.command;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import com.mystipixel.royalwardrobe.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /wardrobe} opens the wardrobe; {@code /wardrobe reload} re-reads config (permission
 * {@code royalwardrobe.admin}). Opening needs {@code royalwardrobe.use}.
 */
public final class WardrobeCommand implements CommandExecutor {

    private final RoyalWardrobePlugin plugin;

    public WardrobeCommand(RoyalWardrobePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("royalwardrobe.admin")) {
                sender.sendMessage(Text.of("&cYou don't have permission to do that."));
                return true;
            }
            plugin.reloadConfig();
            plugin.menu().reload();
            sender.sendMessage(Text.of("&aRoyalWardrobe config + menu reloaded."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.of("&cOnly players can open the wardrobe."));
            return true;
        }
        if (!player.hasPermission("royalwardrobe.use")) {
            player.sendMessage(Text.of("&cYou don't have permission to use the wardrobe."));
            return true;
        }
        plugin.menu().open(player);
        return true;
    }
}
