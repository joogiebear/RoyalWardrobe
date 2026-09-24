package com.mystipixel.royalwardrobe.command;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import com.mystipixel.royalwardrobe.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /wardrobe} opens the wardrobe; {@code /wardrobe equip <n>} swaps to a set without the GUI
 * (keybind/macro-friendly); {@code /wardrobe list} prints the sets; {@code /wardrobe reload}
 * re-reads config (permission {@code royalwardrobe.admin}). Everything else needs
 * {@code royalwardrobe.use}.
 */
public final class WardrobeCommand implements CommandExecutor, TabCompleter {

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
            // Close open wardrobes first: the menu's shape may change, and a menu laid out for the old
            // one would index slots that no longer exist.
            plugin.sessions().invalidateAll();
            plugin.reloadConfig();
            plugin.messages().reload();
            plugin.menu().reload();
            plugin.reloadScopes();
            sender.sendMessage(Text.of("&aRoyalWardrobe config, messages, menu and scope reloaded."
                    + " &7Storage settings apply on restart."));
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
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            plugin.menu().sendList(player);
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("equip")) {
            int number;
            try {
                number = Integer.parseInt(args[1]);
            } catch (NumberFormatException notANumber) {
                player.sendMessage(Text.of("&cUsage: /" + label + " equip <number> &7— see /"
                        + label + " list"));
                return true;
            }
            plugin.menu().equipDirect(player, number - 1);
            return true;
        }
        plugin.menu().open(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : List.of("equip", "list")) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            if (sender.hasPermission("royalwardrobe.admin") && "reload".startsWith(prefix)) {
                out.add("reload");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("equip")) {
            for (int i = 1; i <= plugin.capacity(); i++) {
                String n = Integer.toString(i);
                if (n.startsWith(args[1])) {
                    out.add(n);
                }
            }
        }
        return out;
    }
}
