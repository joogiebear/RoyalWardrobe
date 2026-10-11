package com.mystipixel.royalwardrobe.command;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
                plugin.messages().sendPlain(sender, "command.reload-no-permission", Map.of());
                return true;
            }
            // close open wardrobes first: a menu laid out for the old shape would index slots that no longer exist
            plugin.sessions().invalidateAll();
            plugin.reloadConfig();
            plugin.messages().reload();
            plugin.menu().reload();
            plugin.reloadScopes();
            plugin.messages().sendPlain(sender, "command.reloaded", Map.of());
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().sendPlain(sender, "command.players-only", Map.of());
            return true;
        }
        if (!player.hasPermission("royalwardrobe.use")) {
            plugin.messages().sendPlain(player, "command.no-permission", Map.of());
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
                plugin.messages().sendPlain(player, "command.usage-equip", Map.of("label", label));
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
