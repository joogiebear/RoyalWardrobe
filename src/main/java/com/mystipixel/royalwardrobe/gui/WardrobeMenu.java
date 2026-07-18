package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import com.mystipixel.royalwardrobe.util.Text;
import com.mystipixel.royalwardrobe.wardrobe.ArmorSet;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds and drives the wardrobe GUI. Sets load off the main thread, then the inventory opens on the
 * main thread. Every visual — rows, slot layout, icons, sounds, messages — comes from config.
 *
 * <p>The gear itself is only ever <em>moved</em>: equipping a stored set puts it on the player and
 * drops the previously-worn armor into that same slot (a swap), and saving moves worn armor into an
 * empty slot. Because nothing is copied, a stat item can never be duplicated.
 */
public final class WardrobeMenu {

    private final RoyalWardrobePlugin plugin;

    public WardrobeMenu(RoyalWardrobePlugin plugin) {
        this.plugin = plugin;
    }

    // ── open ─────────────────────────────────────────────────────────────────────

    public void open(Player player) {
        String scope = plugin.scopes().scopeFor(player);
        int capacity = plugin.capacity();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            ArmorSet[] sets = plugin.storage().load(player.getUniqueId(), scope, capacity);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                WardrobeHolder holder = build(player.getUniqueId(), scope, sets);
                player.openInventory(holder.getInventory());
                sound(player, "open");
            });
        });
    }

    private WardrobeHolder build(java.util.UUID owner, String scope, ArmorSet[] sets) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("gui.rows", 6)));
        WardrobeHolder holder = new WardrobeHolder(owner, scope, sets);
        Inventory inv = plugin.getServer().createInventory(
                holder, rows * 9, Text.of(plugin.getConfig().getString("gui.title", "&8Wardrobe")));
        holder.setInventory(inv);
        render(holder);
        return holder;
    }

    /** (Re)paint the whole inventory from the current sets. */
    public void render(WardrobeHolder holder) {
        Inventory inv = holder.getInventory();
        inv.clear();

        if (plugin.getConfig().getBoolean("gui.filler.enabled", true)) {
            Material fill = material(plugin.getConfig().getString("gui.filler.material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
            ItemStack filler = icon(fill, " ", List.of());
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, filler);
            }
        }

        List<Integer> slots = setSlots();
        ArmorSet[] sets = holder.sets();
        for (int k = 0; k < slots.size() && k < sets.length; k++) {
            inv.setItem(slots.get(k), renderSet(sets[k], k + 1));
        }

        int closeSlot = plugin.getConfig().getInt("gui.close.slot", -1);
        if (closeSlot >= 0 && closeSlot < inv.getSize()) {
            inv.setItem(closeSlot, icon(
                    material(plugin.getConfig().getString("gui.close.material", "BARRIER"), Material.BARRIER),
                    plugin.getConfig().getString("gui.close.name", "&cClose"), List.of()));
        }
    }

    private ItemStack renderSet(ArmorSet set, int number) {
        if (set == null || set.isEmpty()) {
            return icon(
                    material(plugin.getConfig().getString("gui.empty-icon.material", "ITEM_FRAME"), Material.ITEM_FRAME),
                    replaceNumber(plugin.getConfig().getString("gui.empty-icon.name", "&7Empty Slot #%number%"), number),
                    loreList("gui.empty-icon.lore", number, set));
        }
        ItemStack base = set.icon();
        ItemStack display = base != null ? base.clone() : new ItemStack(Material.ARMOR_STAND);
        return applyMeta(display,
                replaceNumber(plugin.getConfig().getString("gui.set-icon.name", "&aArmor Set #%number%"), number),
                loreList("gui.set-icon.lore", number, set));
    }

    // ── click handling ───────────────────────────────────────────────────────────

    /** Handle a click on a wardrobe slot. Returns silently for non-interactive slots. */
    public void handleClick(Player player, WardrobeHolder holder, int rawSlot, boolean shift) {
        if (rawSlot == plugin.getConfig().getInt("gui.close.slot", -1)) {
            player.closeInventory();
            return;
        }
        int index = setSlots().indexOf(rawSlot);
        if (index < 0 || index >= holder.sets().length) {
            return;                              // filler or a non-set slot
        }
        ArmorSet slot = holder.sets()[index];

        if (shift && slot != null && !slot.isEmpty()) {
            withdraw(player, holder, index);
            return;
        }
        if (slot == null || slot.isEmpty()) {
            save(player, holder, index);
        } else {
            equip(player, holder, index);
        }
    }

    /** Swap: wear the stored set, drop the previously-worn armor into this slot. */
    private void equip(Player player, WardrobeHolder holder, int index) {
        ItemStack[] worn = worn(player);
        ArmorSet stored = holder.sets()[index];
        setWorn(player, stored.pieces());
        holder.sets()[index] = new ArmorSet(worn);
        persist(holder, index);
        refresh(player, holder);
        sound(player, "equip");
        message(player, "equipped", index + 1);
    }

    /** Move the player's current armor into an empty slot (they're now unarmored — the set is stored). */
    private void save(Player player, WardrobeHolder holder, int index) {
        ItemStack[] worn = worn(player);
        boolean anything = false;
        for (ItemStack piece : worn) {
            if (piece != null && !piece.getType().isAir()) {
                anything = true;
                break;
            }
        }
        if (!anything) {
            sound(player, "empty");
            message(player, "no-armor", index + 1);
            return;
        }
        holder.sets()[index] = new ArmorSet(worn);
        setWorn(player, new ItemStack[ArmorSet.SIZE]);
        persist(holder, index);
        refresh(player, holder);
        sound(player, "save");
        message(player, "saved", index + 1);
    }

    /** Take a stored set out into the inventory (clears the slot), only if there's room for every piece. */
    private void withdraw(Player player, WardrobeHolder holder, int index) {
        ArmorSet stored = holder.sets()[index];
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack piece : stored.pieces()) {
            if (piece != null && !piece.getType().isAir()) {
                items.add(piece);
            }
        }
        long free = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                free++;
            }
        }
        if (free < items.size()) {
            message(player, "inventory-full", index + 1);
            return;
        }
        player.getInventory().addItem(items.toArray(new ItemStack[0]));
        holder.sets()[index] = ArmorSet.empty();
        persist(holder, index);
        refresh(player, holder);
        sound(player, "save");
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
        java.util.UUID owner = holder.owner();
        String scope = holder.scope();
        ArmorSet set = holder.sets()[index];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                () -> plugin.storage().saveSet(owner, scope, index, set));
    }

    private void refresh(Player player, WardrobeHolder holder) {
        render(holder);
        player.updateInventory();
    }

    private List<Integer> setSlots() {
        return plugin.getConfig().getIntegerList("gui.set-slots");
    }

    private List<Component> loreList(String path, int number, ArmorSet set) {
        List<String> raw = plugin.getConfig().getStringList(path);
        List<Component> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(Text.of(replacePieces(replaceNumber(line, number), set)));
        }
        return out;
    }

    private String replaceNumber(String s, int number) {
        return s == null ? "" : s.replace("%number%", Integer.toString(number));
    }

    private String replacePieces(String s, ArmorSet set) {
        if (s == null || !s.contains("%pieces%")) {
            return s;
        }
        String list;
        if (set == null || set.isEmpty()) {
            list = "empty";
        } else {
            list = java.util.Arrays.stream(set.pieces())
                    .filter(p -> p != null && !p.getType().isAir())
                    .map(p -> prettyName(p))
                    .collect(Collectors.joining(", "));
        }
        return s.replace("%pieces%", list);
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

    private ItemStack icon(Material material, String name, List<Component> lore) {
        return applyMeta(new ItemStack(material), name, lore);
    }

    private ItemStack applyMeta(ItemStack item, String name, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.displayName(Text.of(name));
            }
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m != null ? m : fallback;
    }

    private void sound(Player player, String key) {
        String sound = plugin.getConfig().getString("gui.sounds." + key, "");
        if (sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound,
                    (float) plugin.getConfig().getDouble("gui.sounds.volume", 1.0),
                    (float) plugin.getConfig().getDouble("gui.sounds.pitch", 1.0));
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
