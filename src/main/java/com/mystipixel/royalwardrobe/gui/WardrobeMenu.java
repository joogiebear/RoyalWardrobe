package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import com.mystipixel.royalwardrobe.gui.menu.MenuEffect;
import com.mystipixel.royalwardrobe.gui.menu.MenuSlot;
import com.mystipixel.royalwardrobe.gui.menu.MenuTemplate;
import com.mystipixel.royalwardrobe.util.Text;
import com.mystipixel.royalwardrobe.wardrobe.ArmorSet;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds and drives the wardrobe GUI from a {@code gui/wardrobe.yml} menu in the suite's EcoMenus
 * dialect: {@code title}/{@code rows}, a {@code sounds:} block, a page {@code mask} whose {@code 0}
 * region is the set grid, and fixed {@code slots} (close, etc.) with {@code left-click} effects. Sets
 * load off the main thread, then the inventory opens on it.
 *
 * <p>Gear is only ever <em>moved</em> — equip swaps worn↔slot, save moves worn into an empty slot —
 * so a stat item can never be duplicated.
 */
public final class WardrobeMenu {

    private final RoyalWardrobePlugin plugin;
    private MenuTemplate template;

    public WardrobeMenu(RoyalWardrobePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "gui/wardrobe.yml");
        if (!file.exists()) {
            plugin.saveResource("gui/wardrobe.yml", false);
        }
        this.template = MenuTemplate.load(file, "&8Wardrobe", 6);
    }

    /** Capacity = the number of content slots ({@code 0}s) in the menu mask. */
    public int capacity() {
        return template.contentSlots().size();
    }

    // ── open / render ──────────────────────────────────────────────────────────────

    public void open(Player player) {
        String scope = plugin.scopes().scopeFor(player);
        int capacity = capacity();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ArmorSet[] sets = plugin.storage().load(player.getUniqueId(), scope, capacity);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                WardrobeHolder holder = new WardrobeHolder(player.getUniqueId(), scope, sets);
                Inventory inv = plugin.getServer().createInventory(holder, template.size(), Text.of(template.title()));
                holder.setInventory(inv);
                render(holder);
                player.openInventory(inv);
                playSound(player, "sounds.open");
            });
        });
    }

    public void render(WardrobeHolder holder) {
        Inventory inv = holder.getInventory();
        inv.clear();
        template.applyFiller(inv);
        for (MenuSlot slot : template.slots()) {
            inv.setItem(slot.index(), slot.item().build(Map.of(), slot.lore()));
        }
        List<Integer> content = template.contentSlots();
        ArmorSet[] sets = holder.sets();
        for (int k = 0; k < content.size() && k < sets.length; k++) {
            inv.setItem(content.get(k), renderSet(sets[k], k + 1));
        }
    }

    private ItemStack renderSet(ArmorSet set, int number) {
        FileConfiguration cfg = template.config();
        Map<String, String> ph = Map.of("number", Integer.toString(number), "pieces", pieces(set));
        if (set == null || set.isEmpty()) {
            String spec = cfg.getString("empty-icon.item", "item_frame name:\"&7Empty Slot #%number%\"");
            return com.mystipixel.royalwardrobe.gui.menu.ItemSpec.parse(spec)
                    .build(ph, cfg.getStringList("empty-icon.lore"));
        }
        ItemStack base = set.icon();
        ItemStack display = base != null ? base.clone() : new ItemStack(Material.ARMOR_STAND);
        return overlay(display, cfg.getString("set-icon.name", "&aArmor Set #%number%"),
                cfg.getStringList("set-icon.lore"), ph);
    }

    private ItemStack overlay(ItemStack item, String name, List<String> lore, Map<String, String> ph) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.displayName(Text.of(apply(name, ph)));
            }
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(Text.of(apply(line, ph)));
                }
                meta.lore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    // ── click handling ───────────────────────────────────────────────────────────

    public void handleClick(Player player, WardrobeHolder holder, int rawSlot, boolean shift, boolean right) {
        int index = template.contentSlots().indexOf(rawSlot);
        if (index >= 0 && index < holder.sets().length) {
            ArmorSet slot = holder.sets()[index];
            if (shift && slot != null && !slot.isEmpty()) {
                withdraw(player, holder, index);
            } else if (slot == null || slot.isEmpty()) {
                save(player, holder, index);
            } else {
                equip(player, holder, index);
            }
            return;
        }
        MenuSlot slot = template.slotAt(rawSlot);
        if (slot != null) {
            List<MenuEffect> effects = right && !slot.rightClick().isEmpty() ? slot.rightClick() : slot.leftClick();
            runEffects(player, effects);
        }
    }

    private void runEffects(Player player, List<MenuEffect> effects) {
        for (MenuEffect effect : effects) {
            switch (effect.id().toLowerCase(Locale.ROOT)) {
                case "close_inventory", "close" -> player.closeInventory();
                case "play_sound" -> playRaw(player, effect.argString("sound", ""),
                        (float) effect.argDouble("volume", 1.0), (float) effect.argDouble("pitch", 1.0));
                default -> { /* unknown effect id — ignore, never crash the menu */ }
            }
        }
    }

    // ── actions (dupe-safe: gear is moved, never copied) ───────────────────────────

    private void equip(Player player, WardrobeHolder holder, int index) {
        ItemStack[] worn = worn(player);
        setWorn(player, holder.sets()[index].pieces());
        holder.sets()[index] = new ArmorSet(worn);
        persist(holder, index);
        refresh(player, holder);
        playSound(player, "sounds.equip");
        message(player, "equipped", index + 1);
    }

    private void save(Player player, WardrobeHolder holder, int index) {
        ItemStack[] worn = worn(player);
        boolean anything = Arrays.stream(worn).anyMatch(p -> p != null && !p.getType().isAir());
        if (!anything) {
            playSound(player, "sounds.fail");
            message(player, "no-armor", index + 1);
            return;
        }
        holder.sets()[index] = new ArmorSet(worn);
        setWorn(player, new ItemStack[ArmorSet.SIZE]);
        persist(holder, index);
        refresh(player, holder);
        playSound(player, "sounds.save");
        message(player, "saved", index + 1);
    }

    private void withdraw(Player player, WardrobeHolder holder, int index) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack piece : holder.sets()[index].pieces()) {
            if (piece != null && !piece.getType().isAir()) {
                items.add(piece);
            }
        }
        long free = Arrays.stream(player.getInventory().getStorageContents())
                .filter(s -> s == null || s.getType().isAir()).count();
        if (free < items.size()) {
            playSound(player, "sounds.fail");
            message(player, "inventory-full", index + 1);
            return;
        }
        player.getInventory().addItem(items.toArray(new ItemStack[0]));
        holder.sets()[index] = ArmorSet.empty();
        persist(holder, index);
        refresh(player, holder);
        playSound(player, "sounds.save");
        message(player, "withdrew", index + 1);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private ItemStack[] worn(Player player) {
        var inv = player.getInventory();
        return new ItemStack[]{inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
    }

    private void setWorn(Player player, ItemStack[] pieces) {
        var inv = player.getInventory();
        inv.setHelmet(pieces[ArmorSet.HELMET]);
        inv.setChestplate(pieces[ArmorSet.CHEST]);
        inv.setLeggings(pieces[ArmorSet.LEGS]);
        inv.setBoots(pieces[ArmorSet.BOOTS]);
    }

    private void persist(WardrobeHolder holder, int index) {
        UUID owner = holder.owner();
        String scope = holder.scope();
        ArmorSet set = holder.sets()[index];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.storage().saveSet(owner, scope, index, set));
    }

    private void refresh(Player player, WardrobeHolder holder) {
        render(holder);
        player.updateInventory();
    }

    private String pieces(ArmorSet set) {
        if (set == null || set.isEmpty()) {
            return "empty";
        }
        return Arrays.stream(set.pieces())
                .filter(p -> p != null && !p.getType().isAir())
                .map(this::prettyName)
                .collect(Collectors.joining(", "));
    }

    private String prettyName(ItemStack item) {
        String key = item.getType().getKey().getKey();
        StringBuilder b = new StringBuilder(key.length());
        boolean up = true;
        for (char c : key.toCharArray()) {
            if (c == '_') {
                b.append(' ');
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return b.toString();
    }

    private String apply(String s, Map<String, String> ph) {
        if (s == null) {
            return "";
        }
        String out = s;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            out = out.replace("%" + e.getKey() + "%", e.getValue());
        }
        return out;
    }

    private void playSound(Player player, String path) {
        FileConfiguration cfg = template.config();
        if (!cfg.getBoolean(path + ".enabled", false)) {
            return;
        }
        playRaw(player, cfg.getString(path + ".name", ""),
                (float) cfg.getDouble(path + ".volume", 1.0), (float) cfg.getDouble(path + ".pitch", 1.0));
    }

    private void playRaw(Player player, String rawSound, float volume, float pitch) {
        if (rawSound == null || rawSound.isBlank()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), Sound.valueOf(rawSound.toUpperCase(Locale.ROOT)), volume, pitch);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown GUI sound: " + rawSound);
        }
    }

    private void message(Player player, String key, int number) {
        String raw = plugin.getConfig().getString("messages." + key, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        player.sendMessage(Text.of(prefix + raw.replace("%number%", Integer.toString(number))));
    }
}
