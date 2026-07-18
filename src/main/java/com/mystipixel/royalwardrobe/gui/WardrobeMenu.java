package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import com.mystipixel.royalwardrobe.gui.menu.ItemSpec;
import com.mystipixel.royalwardrobe.util.Text;
import com.mystipixel.royalwardrobe.wardrobe.ArmorSet;
import com.mystipixel.royalwardrobe.wardrobe.WardrobeData;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The wardrobe GUI, modelled on Hypixel's: each of the 9 columns is one armor set — 4 stacked piece
 * slots (helmet→boots) with a control dye below, and a bottom nav row (page arrows + close). Multiple
 * pages. Everything visual comes from {@code gui/wardrobe.yml} in the suite's item-spec/sounds style.
 *
 * <p><b>Dupe-safe by construction.</b> The <em>active</em> set (the one you're wearing) lives on the
 * player, not in storage — its column mirrors your armor and is locked. Equipping another set writes
 * your current set back to its column (or loose armor to your inventory) and moves the new set onto
 * you; storing/unequipping/manual placement all move single items via the cursor. No item is ever
 * copied, so nothing can be duplicated.
 */
public final class WardrobeMenu {

    private static final int SIZE = 54;
    private static final int DYE_ROW = 4;      // row index of the control dyes
    private static final int NAV_ROW = 5;      // bottom navigation row

    private final RoyalWardrobePlugin plugin;
    private FileConfiguration gui;
    private int columns;
    private int pages;

    public WardrobeMenu(RoyalWardrobePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "gui/wardrobe.yml");
        if (!file.exists()) {
            plugin.saveResource("gui/wardrobe.yml", false);
        }
        this.gui = YamlConfiguration.loadConfiguration(file);
        this.columns = Math.max(1, Math.min(9, gui.getInt("columns-per-page", 9)));
        this.pages = Math.max(1, gui.getInt("pages", 2));
    }

    public int capacity() {
        return columns * pages;
    }

    /**
     * How many slots a player may use: {@code slots.default}, raised by the highest
     * {@code <slots.permission>.<n>} they hold ({@code .*} grants the maximum), clamped to the menu's
     * capacity. Any permission plugin can hand these out with ranks or perks.
     */
    public int allowedSlots(Player player) {
        int max = Math.min(capacity(), plugin.getConfig().getInt("slots.max", capacity()));
        int allowed = Math.max(0, plugin.getConfig().getInt("slots.default", 1));
        String prefix = plugin.getConfig().getString("slots.permission", "royalwardrobe.slots")
                .toLowerCase(Locale.ROOT) + ".";
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;                        // explicitly negated
            }
            String perm = info.getPermission().toLowerCase(Locale.ROOT);
            if (!perm.startsWith(prefix)) {
                continue;
            }
            String suffix = perm.substring(prefix.length());
            if (suffix.equals("*")) {
                return max;
            }
            try {
                allowed = Math.max(allowed, Integer.parseInt(suffix));
            } catch (NumberFormatException notANumber) {
                // e.g. royalwardrobe.slots.something — ignore
            }
        }
        return Math.max(0, Math.min(allowed, max));
    }

    // ── open / paging ──────────────────────────────────────────────────────────────

    public void open(Player player) {
        String scope = plugin.scopes().scopeFor(player);
        int capacity = capacity();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            WardrobeData data = plugin.storage().load(player.getUniqueId(), scope, capacity);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    // Resolved once per open, so the layout can't shift mid-session if perms change.
                    int allowed = allowedSlots(player);
                    openPage(player, new WardrobeHolder(player.getUniqueId(), scope, data, 0, allowed), 0);
                    playSound(player, "open");
                }
            });
        });
    }

    private void openPage(Player player, WardrobeHolder holder, int page) {
        holder.setPage(page);
        Inventory inv = plugin.getServer().createInventory(holder, SIZE, Text.of(title(page)));
        holder.setInventory(inv);
        render(player, holder);
        player.openInventory(inv);
    }

    private String title(int page) {
        return gui.getString("title", "&8&lWardrobe (%page%/%pages%)")
                .replace("%page%", Integer.toString(page + 1))
                .replace("%pages%", Integer.toString(pages));
    }

    /** An admin-defined button: an item at a slot, with the effects its click runs. */
    private record Button(int index, String spec, List<String> lore, List<Map<?, ?>> effects) {
    }

    /**
     * Extra buttons declared in {@code buttons:}. The armour grid is computed rather than authored, so
     * this is how a menu gains anything beyond the built-in navigation without touching code.
     */
    private List<Button> buttons() {
        List<Button> out = new ArrayList<>();
        List<Map<?, ?>> raw = gui.getMapList("buttons");
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            Object item = entry.get("item");
            if (item == null) {
                plugin.getLogger().warning("Button " + i + " in the wardrobe menu has no 'item:' — skipping.");
                continue;
            }
            int index = slotIndexOf(entry, -1);
            if (index < 0 || index >= SIZE) {
                plugin.getLogger().warning("Button " + i + " in the wardrobe menu has no valid row/column"
                        + " or slot — skipping.");
                continue;
            }
            if (index < NAV_ROW * 9) {
                plugin.getLogger().warning("Button " + i + " sits at slot " + index + ", inside the wardrobe"
                        + " grid — it will cover a gear slot. Move it to the bottom row if that wasn't intended.");
            }
            List<String> lore = new ArrayList<>();
            Object rawLore = entry.get("lore");
            if (rawLore instanceof List<?> list) {
                for (Object line : list) {
                    lore.add(String.valueOf(line));
                }
            }
            List<Map<?, ?>> effects = new ArrayList<>();
            Object rawClick = entry.get("left-click");
            if (rawClick instanceof List<?> list) {
                for (Object effect : list) {
                    if (effect instanceof Map<?, ?> map) {
                        effects.add(map);
                    }
                }
            }
            out.add(new Button(index, String.valueOf(item), lore, effects));
        }
        return out;
    }

    /** The {@code left-click:} effects configured under a nav entry, empty when it has none. */
    private List<Map<?, ?>> clickEffects(String path) {
        List<Map<?, ?>> out = new ArrayList<>();
        for (Map<?, ?> effect : gui.getMapList(path + ".left-click")) {
            out.add(effect);
        }
        return out;
    }

    /** row/column (1-indexed) or a raw slot, from an inline config map. */
    private static int slotIndexOf(Map<?, ?> entry, int def) {
        Object row = entry.get("row");
        Object column = entry.get("column");
        if (row instanceof Number r && column instanceof Number c) {
            int index = (r.intValue() - 1) * 9 + (c.intValue() - 1);
            if (c.intValue() >= 1 && c.intValue() <= 9 && index >= 0) {
                return index;
            }
        }
        Object slot = entry.get("slot");
        return slot instanceof Number n ? n.intValue() : def;
    }

    /**
     * Run a click's configured effects. Same vocabulary as the other Royal menus, so an admin who has
     * configured one of them already knows this one. Returns false when nothing was configured, letting
     * the caller fall back to the button's built-in behaviour.
     */
    private boolean runEffects(Player player, List<Map<?, ?>> effects) {
        if (effects == null || effects.isEmpty()) {
            return false;
        }
        for (Map<?, ?> effect : effects) {
            String id = String.valueOf(effect.get("id")).toLowerCase(Locale.ROOT);
            Object args = effect.get("args");
            Map<?, ?> a = args instanceof Map<?, ?> m ? m : Map.of();
            switch (id) {
                case "close", "close_inventory" -> player.closeInventory();
                case "message" -> player.sendMessage(Text.of(arg(a, "message")));
                case "player_command" -> player.performCommand(arg(a, "command"));
                case "console_command" -> plugin.getServer().dispatchCommand(
                        plugin.getServer().getConsoleSender(),
                        arg(a, "command").replace("%player%", player.getName()));
                case "play_sound" -> playRawSound(player, arg(a, "sound"),
                        toFloat(a.get("volume"), 1f), toFloat(a.get("pitch"), 1f));
                default -> plugin.getLogger().warning("Unknown click id '" + id + "' in the wardrobe menu.");
            }
        }
        return true;
    }

    /** Play a sound named directly by a click effect, rather than one of the configured sound keys. */
    private void playRawSound(Player player, String name, float volume, float pitch) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), Sound.valueOf(name.toUpperCase(Locale.ROOT)), volume, pitch);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown sound in a wardrobe button: " + name);
        }
    }

    /** A string arg from an effect's args map, or "" when absent. */
    private static String arg(Map<?, ?> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static float toFloat(Object value, float def) {
        return value instanceof Number n ? n.floatValue() : def;
    }

    // ── render ─────────────────────────────────────────────────────────────────────

    public void render(Player player, WardrobeHolder holder) {
        Inventory inv = holder.getInventory();
        ItemStack navFiller = item("nav.filler", "gray_stained_glass_pane name:\" \"", null, Map.of());
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, navFiller.clone());
        }

        WardrobeData data = holder.data();
        int page = holder.page();
        for (int c = 0; c < columns; c++) {
            int setIndex = page * columns + c;
            boolean active = data.activeIndex() == setIndex;
            boolean locked = holder.isLocked(setIndex);
            ArmorSet set = active ? wornSet(player) : data.set(setIndex);
            for (int row = 0; row < ArmorSet.SIZE; row++) {
                ItemStack piece = set.piece(row);
                if (piece != null && !piece.getType().isAir()) {
                    // Gear is always shown, even in a locked slot, so it stays visible and retrievable
                    // if the player's allowance shrank (rank expired) — never stranded.
                    inv.setItem(c + row * 9, piece.clone());
                } else {
                    inv.setItem(c + row * 9, locked
                            ? item("locked-slot.item", "red_stained_glass_pane name:\"&cLocked\"", null, Map.of())
                            : item("empty-slot.item", "light_gray_stained_glass_pane name:\" \"", null, Map.of()));
                }
            }
            inv.setItem(c + DYE_ROW * 9, locked
                    ? item("dye.locked.item", "barrier name:\"&c&lLocked Slot\"", "dye.locked.lore",
                            Map.of("number", Integer.toString(setIndex + 1)))
                    : dyeFor(set, active, setIndex, data));
        }

        int base = NAV_ROW * 9;
        if (page > 0) {
            inv.setItem(navSlot("previous", base), item("nav.previous.item", "arrow name:\"&ePrevious Page\"", "nav.previous.lore", Map.of()));
        }
        if (page < pages - 1) {
            inv.setItem(navSlot("next", base + 8), item("nav.next.item", "arrow name:\"&eNext Page\"", "nav.next.lore", Map.of()));
        }
        inv.setItem(navSlot("close", base + 4), item("nav.close.item", "barrier name:\"&cClose\"", "nav.close.lore", Map.of()));
        for (Button button : buttons()) {
            inv.setItem(button.index(), ItemSpec.parse(button.spec()).build(Map.of(), button.lore()));
        }
        player.updateInventory();
    }

    private ItemStack dyeFor(ArmorSet set, boolean active, int setIndex, WardrobeData data) {
        Map<String, String> ph = new HashMap<>();
        ph.put("number", Integer.toString(setIndex + 1));
        ph.put("pieces", pieces(set));
        ph.put("first_worn", date(data.firstWorn(setIndex)));
        if (active) {
            return item("dye.unequip.item", "lime_dye name:\"&aActive Setup\"", "dye.unequip.lore", ph);
        }
        if (set != null && !set.isEmpty()) {
            return item("dye.equip.item", "pink_dye name:\"&dEquip Setup\"", "dye.equip.lore", ph);
        }
        return item("dye.store.item", "gray_dye name:\"&7Store Current Setup\"", "dye.store.lore", ph);
    }

    // ── click handling (all clicks are cancelled by the listener; we move items ourselves) ──

    public void handleClick(Player player, WardrobeHolder holder, int slot) {
        int row = slot / 9;
        int col = slot % 9;
        WardrobeData data = holder.data();

        if (row < ArmorSet.SIZE) {
            if (col >= columns) {
                return;
            }
            int setIndex = holder.page() * columns + col;
            if (data.activeIndex() == setIndex) {
                return;                          // active setup is locked — can't be modified here
            }
            handleArmorSlot(player, holder, setIndex, row);
            return;
        }
        if (row == DYE_ROW) {
            if (col >= columns) {
                return;
            }
            int setIndex = holder.page() * columns + col;
            if (holder.isLocked(setIndex)) {
                playSound(player, "fail");
                message(player, "slot-locked");
                return;
            }
            handleDye(player, holder, setIndex);
            return;
        }
        // Admin-defined buttons are checked first so one can sit on the nav row without being
        // swallowed by the built-in paging behaviour.
        for (Button button : buttons()) {
            if (button.index() == slot) {
                runEffects(player, button.effects());
                return;
            }
        }
        if (row == NAV_ROW) {
            int base = NAV_ROW * 9;
            if (slot == navSlot("previous", base) && holder.page() > 0) {
                // A configured left-click replaces the built-in behaviour; without one, page as before.
                if (!runEffects(player, clickEffects("nav.previous"))) {
                    openPage(player, holder, holder.page() - 1);
                }
            } else if (slot == navSlot("next", base + 8) && holder.page() < pages - 1) {
                if (!runEffects(player, clickEffects("nav.next"))) {
                    openPage(player, holder, holder.page() + 1);
                }
            } else if (slot == navSlot("close", base + 4)) {
                if (!runEffects(player, clickEffects("nav.close"))) {
                    player.closeInventory();
                }
            }
        }
    }

    /** Cursor-based place/take of a single piece into an inactive column's slot. Armor-type validated. */
    private void handleArmorSlot(Player player, WardrobeHolder holder, int setIndex, int row) {
        ArmorSet set = holder.data().set(setIndex);
        ItemStack cursor = player.getItemOnCursor();
        ItemStack current = set.piece(row);
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();
        boolean currentEmpty = current == null || current.getType().isAir();

        if (cursorEmpty && currentEmpty) {
            return;
        }
        final ItemStack newCursor;
        if (cursorEmpty) {                       // take the stored piece onto the cursor
            newCursor = current;
            set.pieces()[row] = null;
        } else {                                 // place the cursor piece, swap the old one out
            // A locked slot can still be emptied (above), but nothing new goes in.
            if (holder.isLocked(setIndex)) {
                playSound(player, "fail");
                message(player, "slot-locked");
                return;
            }
            if (!isArmorForRow(cursor, row)) {
                playSound(player, "fail");
                return;
            }
            newCursor = currentEmpty ? null : current.clone();
            set.pieces()[row] = cursor.clone();
        }
        player.setItemOnCursor(newCursor);       // server-side now, so there's no dupe window
        persist(holder, setIndex);
        render(player, holder);
        // Re-assert the cursor next tick so the client display resyncs after the cancelled click.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.setItemOnCursor(newCursor);
            }
        });
    }

    /**
     * Shift-click deposit: drop one armor piece from the player's inventory into the first empty slot
     * of the matching row on the current page (skipping the locked active column). Returns true if it
     * was placed (the caller then removes one from the source stack). Keeps everything a single-item move.
     */
    public boolean tryDeposit(Player player, WardrobeHolder holder, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        int row = armorRow(item);
        if (row < 0) {
            return false;                        // not a wearable armor piece
        }
        WardrobeData data = holder.data();
        int page = holder.page();
        for (int c = 0; c < columns; c++) {
            int setIndex = page * columns + c;
            if (data.activeIndex() == setIndex || holder.isLocked(setIndex)) {
                continue;                        // active column, or one they haven't unlocked
            }
            ItemStack existing = data.set(setIndex).piece(row);
            if (existing == null || existing.getType().isAir()) {
                ItemStack one = item.clone();
                one.setAmount(1);
                data.set(setIndex).pieces()[row] = one;
                persist(holder, setIndex);
                render(player, holder);
                playSound(player, "store");
                return true;
            }
        }
        return false;                            // no empty matching slot on this page
    }

    private static int armorRow(ItemStack item) {
        for (int row = 0; row < ArmorSet.SIZE; row++) {
            if (isArmorForRow(item, row)) {
                return row;
            }
        }
        return -1;
    }

    private void handleDye(Player player, WardrobeHolder holder, int setIndex) {
        WardrobeData data = holder.data();
        if (data.activeIndex() == setIndex) {
            unequip(player, holder);
        } else if (!data.set(setIndex).isEmpty()) {
            equip(player, holder, setIndex);
        } else {
            storeCurrent(player, holder, setIndex);
        }
    }

    // ── actions ────────────────────────────────────────────────────────────────────

    private void equip(Player player, WardrobeHolder holder, int target) {
        WardrobeData data = holder.data();
        ArmorSet worn = wornSet(player);
        int active = data.activeIndex();

        if (active != -1) {
            data.setSet(active, worn);           // the set you were wearing goes back to its column
            data.setActiveIndex(-1);
            persist(holder, active);
        } else if (!worn.isEmpty()) {
            if (!moveToInventory(player, worn)) { // loose armor -> inventory
                playSound(player, "fail");
                message(player, "inventory-full");
                return;
            }
        }

        setWorn(player, data.set(target).pieces());
        data.setSet(target, ArmorSet.empty());   // items are on the player now
        data.setActiveIndex(target);
        if (data.firstWorn(target) <= 0) {
            data.setFirstWorn(target, now());
        }
        persist(holder, target);
        render(player, holder);
        playSound(player, "equip");
        message(player, "equipped");
    }

    private void storeCurrent(Player player, WardrobeHolder holder, int target) {
        WardrobeData data = holder.data();
        if (data.activeIndex() != -1) {
            playSound(player, "fail");
            message(player, "already-active");
            return;
        }
        ArmorSet worn = wornSet(player);
        if (worn.isEmpty()) {
            playSound(player, "fail");
            message(player, "no-armor");
            return;
        }
        data.setActiveIndex(target);             // your worn armor becomes this (active) set
        data.setFirstWorn(target, now());
        data.setSet(target, ArmorSet.empty());
        persist(holder, target);
        render(player, holder);
        playSound(player, "store");
        message(player, "stored");
    }

    private void unequip(Player player, WardrobeHolder holder) {
        WardrobeData data = holder.data();
        int active = data.activeIndex();
        if (active == -1) {
            return;
        }
        data.setSet(active, wornSet(player));    // worn armor goes back into its column
        data.setActiveIndex(-1);
        setWorn(player, new ItemStack[ArmorSet.SIZE]);
        persist(holder, active);
        render(player, holder);
        playSound(player, "unequip");
        message(player, "unequipped");
    }

    // ── item / player helpers ────────────────────────────────────────────────────────

    private ArmorSet wornSet(Player player) {
        var inv = player.getInventory();
        return new ArmorSet(new ItemStack[]{inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()});
    }

    private void setWorn(Player player, ItemStack[] pieces) {
        var inv = player.getInventory();
        inv.setHelmet(pieces[ArmorSet.HELMET]);
        inv.setChestplate(pieces[ArmorSet.CHEST]);
        inv.setLeggings(pieces[ArmorSet.LEGS]);
        inv.setBoots(pieces[ArmorSet.BOOTS]);
    }

    /** Move a worn set's pieces into the inventory (used when equipping over loose armor). */
    private boolean moveToInventory(Player player, ArmorSet worn) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack piece : worn.pieces()) {
            if (piece != null && !piece.getType().isAir()) {
                items.add(piece);
            }
        }
        long free = Arrays.stream(player.getInventory().getStorageContents())
                .filter(s -> s == null || s.getType().isAir()).count();
        if (free < items.size()) {
            return false;
        }
        player.getInventory().addItem(items.toArray(new ItemStack[0]));
        setWorn(player, new ItemStack[ArmorSet.SIZE]);
        return true;
    }

    private static boolean isArmorForRow(ItemStack item, int row) {
        String n = item.getType().name();
        return switch (row) {
            case ArmorSet.HELMET -> n.endsWith("_HELMET") || n.endsWith("_HEAD") || n.endsWith("_SKULL")
                    || n.equals("CARVED_PUMPKIN");
            case ArmorSet.CHEST -> n.endsWith("_CHESTPLATE") || n.equals("ELYTRA");
            case ArmorSet.LEGS -> n.endsWith("_LEGGINGS");
            case ArmorSet.BOOTS -> n.endsWith("_BOOTS");
            default -> false;
        };
    }

    /**
     * Persist one slot. Snapshots on the main thread (the live array keeps being mutated by clicks)
     * and queues the write on storage's single writer thread, so writes for the same slot can't
     * commit out of order and a shutdown drains them instead of dropping them.
     */
    private void persist(WardrobeHolder holder, int index) {
        WardrobeData data = holder.data();
        UUID owner = holder.owner();
        String scope = holder.scope();
        ArmorSet snapshot = data.set(index).snapshot();
        long firstWorn = data.firstWorn(index);
        boolean active = data.activeIndex() == index;
        java.util.UUID viewer = holder.owner();
        plugin.storage().submit(() -> {
            if (!plugin.storage().save(owner, scope, index, snapshot, firstWorn, active)) {
                // Never fail silently — the gear is unaccounted for and the player must know.
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player online = plugin.getServer().getPlayer(viewer);
                    if (online != null) {
                        message(online, "save-failed");
                    }
                });
            }
        });
    }

    private ItemStack item(String itemPath, String def, String lorePath, Map<String, String> ph) {
        String spec = gui.getString(itemPath, def);
        List<String> lore = lorePath == null ? List.of() : gui.getStringList(lorePath);
        return ItemSpec.parse(spec).build(ph, lore);
    }

    private String pieces(ArmorSet set) {
        if (set == null || set.isEmpty()) {
            return "empty";
        }
        return Arrays.stream(set.pieces())
                .filter(p -> p != null && !p.getType().isAir())
                .map(p -> prettyName(p.getType().getKey().getKey()))
                .collect(Collectors.joining(", "));
    }

    private String date(long millis) {
        if (millis <= 0) {
            return gui.getString("never-worn", "—");
        }
        try {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(gui.getString("date-format", "MMM d, yyyy"), Locale.ENGLISH);
            return fmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
        } catch (RuntimeException e) {
            return "—";
        }
    }

    private static String prettyName(String key) {
        StringBuilder b = new StringBuilder(key.length());
        boolean up = true;
        for (char ch : key.toCharArray()) {
            if (ch == '_') {
                b.append(' ');
                up = true;
            } else {
                b.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return b.toString();
    }

    /**
     * Where a nav button sits. Accepts a {@code row}/{@code column} pair (both 1-indexed, matching how
     * every other menu in the suite places buttons) or a raw {@code slot} index, so configs written
     * against the old format keep working. Falls back to {@code def} when neither is set.
     */
    private int navSlot(String key, int def) {
        return slotFrom("nav." + key, def);
    }

    /** Resolve a row/column pair or raw slot under {@code path}, or {@code def} if neither is present. */
    private int slotFrom(String path, int def) {
        int row = gui.getInt(path + ".row", -1);
        int column = gui.getInt(path + ".column", -1);
        if (row >= 1 && column >= 1 && column <= 9) {
            int index = (row - 1) * 9 + (column - 1);
            if (index >= 0 && index < SIZE) {
                return index;
            }
            plugin.getLogger().warning("Menu entry '" + path + "' is at row " + row + ", column " + column
                    + ", which is outside this menu — using the default slot instead.");
        }
        return gui.getInt(path + ".slot", def);
    }

    private long now() {
        return System.currentTimeMillis();
    }

    // ── sounds / messages ────────────────────────────────────────────────────────────

    private void playSound(Player player, String key) {
        if (!gui.getBoolean("sounds." + key + ".enabled", false)) {
            return;
        }
        String name = gui.getString("sounds." + key + ".name", "");
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), Sound.valueOf(name.toUpperCase(Locale.ROOT)),
                    (float) gui.getDouble("sounds." + key + ".volume", 1.0),
                    (float) gui.getDouble("sounds." + key + ".pitch", 1.0));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown GUI sound: " + name);
        }
    }

    /** Built-in wording for each message, used when messages.yml doesn't define the key. */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "equipped", "&aEquipped that setup.",
            "stored", "&aStored your current setup.",
            "unequipped", "&aUnequipped your setup.",
            "no-armor", "&cYou aren't wearing any armor to store.",
            "already-active", "&cYou're already wearing a saved setup — unequip it first.",
            "slot-locked", "&cThat wardrobe slot is locked. Unlock more with a rank or perk.",
            "inventory-full", "&cMake room in your inventory first.",
            "save-failed", "&cYour wardrobe could not be saved — tell an admin before changing more sets.");

    private void message(Player player, String key) {
        plugin.messages().send(player, key, DEFAULT_MESSAGES.getOrDefault(key, ""));
    }
}
