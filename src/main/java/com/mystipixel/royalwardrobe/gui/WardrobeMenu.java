package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import com.mystipixel.royalwardrobe.gui.menu.ItemSpec;
import com.mystipixel.royalwardrobe.storage.WardrobeStorage;
import com.mystipixel.royalwardrobe.util.Text;
import com.mystipixel.royalwardrobe.wardrobe.ArmorSet;
import com.mystipixel.royalwardrobe.wardrobe.WardrobeActions;
import com.mystipixel.royalwardrobe.wardrobe.WardrobeData;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
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
 * The wardrobe GUI: each column is one armor set (four piece slots, helmet to boots, with a control dye
 * below) and the bottom row is navigation. Layout and items come from {@code gui/wardrobe.yml}.
 *
 * <p>The active set lives on the player, so its column mirrors their armor and is locked. Every action
 * moves items, never copies them.
 */
public final class WardrobeMenu {

    private static final int SIZE = 54;
    private static final int DYE_ROW = 4;
    private static final int NAV_ROW = 5;

    private final RoyalWardrobePlugin plugin;
    private final TextInput textInput;
    private FileConfiguration gui;
    private int columns;
    private int pages;
    // parsed once per reload, so a misplaced entry is reported once rather than on every click
    private List<Button> buttons = List.of();
    private int previousSlot;
    private int nextSlot;
    private int closeSlot;

    public WardrobeMenu(RoyalWardrobePlugin plugin, TextInput textInput) {
        this.plugin = plugin;
        this.textInput = textInput;
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
        int base = NAV_ROW * 9;
        this.previousSlot = navSlot("previous", base);
        this.nextSlot = navSlot("next", base + 8);
        this.closeSlot = navSlot("close", base + 4);
        this.buttons = parseButtons();
    }

    public int capacity() {
        return columns * pages;
    }

    // more than the configured pages when sets are stored past a shrunk menu; those render locked
    private int pagesFor(WardrobeData data) {
        return Math.max(pages, (data.capacity() + columns - 1) / columns);
    }

    /**
     * {@code slots.default}, raised by the highest {@code <slots.permission>.<n>} the player holds
     * ({@code .*} grants the maximum), clamped to the menu's capacity.
     */
    public int allowedSlots(Player player) {
        int max = Math.min(capacity(), plugin.getConfig().getInt("slots.max", capacity()));
        int allowed = Math.max(0, plugin.getConfig().getInt("slots.default", 1));
        String prefix = plugin.getConfig().getString("slots.permission", "royalwardrobe.slots")
                .toLowerCase(Locale.ROOT) + ".";
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
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
                // e.g. royalwardrobe.slots.something
            }
        }
        return Math.max(0, Math.min(allowed, max));
    }

    public void open(Player player) {
        withSession(player, session -> {
            // resolved once per open, so the layout can't shift mid-session if perms change
            int allowed = allowedSlots(player);
            openPage(player, new WardrobeHolder(player.getUniqueId(), session.scope(), session.data(), 0, allowed), 0);
            playSound(player, "open");
        });
    }

    // an unreadable wardrobe is refused, not shown empty: storing into a "free" slot would overwrite the real set
    private void withSession(Player player, java.util.function.Consumer<WardrobeSessions.Session> action) {
        String scope = plugin.scopes().scopeFor(player);
        if (scope == null) {
            // between profiles: falling back to the shared wardrobe would carry gear across profiles
            playSound(player, "fail");
            message(player, "no-profile");
            return;
        }
        if (plugin.sessions().hasUnsavedWrites(player.getUniqueId(), scope)) {
            playSound(player, "fail");
            message(player, "save-pending");
            return;
        }
        plugin.sessions().with(player, scope, capacity(), action, () -> {
            playSound(player, "fail");
            message(player, "load-failed");
        });
    }

    private void openPage(Player player, WardrobeHolder holder, int page) {
        holder.setPage(page);
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                Text.of(title(page, pagesFor(holder.data()))));
        holder.setInventory(inv);
        render(player, holder);
        player.openInventory(inv);
    }

    private String title(int page, int totalPages) {
        return gui.getString("title", "&8&lWardrobe (%page%/%pages%)")
                .replace("%page%", Integer.toString(page + 1))
                .replace("%pages%", Integer.toString(totalPages));
    }

    private record Button(int index, String spec, List<String> lore, List<Map<?, ?>> effects) {
    }

    private List<Button> parseButtons() {
        List<Button> out = new ArrayList<>();
        List<Map<?, ?>> raw = gui.getMapList("buttons");
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> entry = raw.get(i);
            Object item = entry.get("item");
            if (item == null) {
                plugin.getLogger().warning("Button " + i + " in the wardrobe menu has no 'item:', skipping.");
                continue;
            }
            int index = slotIndexOf(entry, -1);
            if (index < 0 || index >= SIZE) {
                plugin.getLogger().warning("Button " + i + " in the wardrobe menu has no valid row/column"
                        + " or slot, skipping.");
                continue;
            }
            if (index < NAV_ROW * 9) {
                plugin.getLogger().warning("Button " + i + " sits at slot " + index + ", inside the wardrobe"
                        + " grid: it will cover a gear slot. Move it to the bottom row if that wasn't intended.");
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

    private List<Map<?, ?>> clickEffects(String path) {
        List<Map<?, ?>> out = new ArrayList<>();
        for (Map<?, ?> effect : gui.getMapList(path + ".left-click")) {
            out.add(effect);
        }
        return out;
    }

    // row/column (1-indexed) or a raw slot
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

    // same effect ids as the other Royal menus; false when none are configured, so the caller falls back
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
                // next tick: opening another plugin's menu from inside this click event is unreliable
                case "player_command" -> {
                    String command = arg(a, "command");
                    plugin.getServer().getScheduler().runTask(plugin, () -> player.performCommand(command));
                }
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

    private void playRawSound(Player player, String name, float volume, float pitch) {
        if (name == null || name.isBlank()) {
            return;
        }
        playNamedSound(player, name, volume, pitch);
    }

    // BLOCK_CHEST_OPEN -> block.chest.open
    private static Map<String, String> soundKeys;
    private final java.util.Set<String> unknownSounds = new java.util.HashSet<>();

    // accepts BLOCK_CHEST_OPEN or a namespaced key (minecraft:block.chest.open, or a resource pack's own)
    private void playNamedSound(Player player, String name, float volume, float pitch) {
        String key;
        if (name.indexOf('.') >= 0 || name.indexOf(':') >= 0) {
            key = name.toLowerCase(Locale.ROOT);
        } else {
            key = soundKeys().get(name.toUpperCase(Locale.ROOT));
            if (key == null) {
                if (unknownSounds.add(name)) {
                    plugin.getLogger().warning("Unknown sound in the wardrobe menu: " + name);
                }
                return;
            }
        }
        player.playSound(player.getLocation(), key, volume, pitch);
    }

    private static Map<String, String> soundKeys() {
        if (soundKeys == null) {
            Map<String, String> keys = new HashMap<>();
            io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.SOUND_EVENT)
                    .keyStream()
                    .forEach(key -> keys.put(key.getKey().toUpperCase(Locale.ROOT).replace('.', '_'), key.asString()));
            soundKeys = keys;
        }
        return soundKeys;
    }

    private static String arg(Map<?, ?> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static float toFloat(Object value, float def) {
        return value instanceof Number n ? n.floatValue() : def;
    }

    public void render(Player player, WardrobeHolder holder) {
        Inventory inv = holder.getInventory();
        if (inv == null) {
            return;                              // command-driven session with no open menu
        }
        ItemStack navFiller = item("nav.filler", "gray_stained_glass_pane name:\" \"", null, Map.of());
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, navFiller.clone());
        }

        WardrobeData data = holder.data();
        int page = holder.page();
        for (int c = 0; c < columns; c++) {
            int setIndex = page * columns + c;
            if (setIndex >= data.capacity()) {
                break;
            }
            boolean active = data.activeIndex() == setIndex;
            boolean locked = holder.isLocked(setIndex);
            if (data.isCorrupt(setIndex)) {
                // never show an unreadable row as an empty slot to fill
                for (int row = 0; row < ArmorSet.SIZE; row++) {
                    inv.setItem(c + row * 9, item("corrupt-slot.item",
                            "black_stained_glass_pane name:\"&4Damaged\"", null, Map.of()));
                }
                inv.setItem(c + DYE_ROW * 9, item("dye.corrupt.item", "structure_void name:\"&4&lDamaged Slot\"",
                        "dye.corrupt.lore", Map.of("number", Integer.toString(setIndex + 1))));
                continue;
            }
            ArmorSet set = active ? wornSet(player) : data.set(setIndex);
            for (int row = 0; row < ArmorSet.SIZE; row++) {
                ItemStack piece = set.piece(row);
                if (piece != null && !piece.getType().isAir()) {
                    // shown even in a locked slot, so gear stays retrievable if the allowance shrank
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

        if (page > 0) {
            inv.setItem(previousSlot, item("nav.previous.item", "arrow name:\"&ePrevious Page\"", "nav.previous.lore", Map.of()));
        }
        if (page < pagesFor(data) - 1) {
            inv.setItem(nextSlot, item("nav.next.item", "arrow name:\"&eNext Page\"", "nav.next.lore", Map.of()));
        }
        inv.setItem(closeSlot, item("nav.close.item", "barrier name:\"&cClose\"", "nav.close.lore", Map.of()));
        for (Button button : buttons) {
            inv.setItem(button.index(), ItemSpec.parse(button.spec()).build(Map.of(), button.lore()));
        }
        player.updateInventory();
    }

    /** The default or player-given name for a set, used in dyes, lists and messages alike. */
    public String setName(WardrobeData data, int setIndex) {
        String custom = data.name(setIndex);
        // cleaned on display too, for names stored before codes were stripped on entry
        String clean = custom == null ? "" : cleanName(custom);
        return !clean.isEmpty() ? clean : "Setup #" + (setIndex + 1);
    }

    private ItemStack dyeFor(ArmorSet set, boolean active, int setIndex, WardrobeData data) {
        Map<String, String> ph = new HashMap<>();
        ph.put("number", Integer.toString(setIndex + 1));
        ph.put("set_name", setName(data, setIndex));
        ph.put("pieces", pieces(set));
        ph.put("first_worn", date(data.firstWorn(setIndex)));
        if (active) {
            return item("dye.unequip.item", "lime_dye name:\"&aActive: %set_name%\"", "dye.unequip.lore", ph);
        }
        if (set != null && !set.isEmpty()) {
            return item("dye.equip.item", "pink_dye name:\"&d%set_name%\"", "dye.equip.lore", ph);
        }
        return item("dye.store.item", "gray_dye name:\"&7Store Current Setup\"", "dye.store.lore", ph);
    }

    public void handleClick(Player player, WardrobeHolder holder, int slot, boolean rightClick) {
        int row = slot / 9;
        int col = slot % 9;
        WardrobeData data = holder.data();

        if (row <= DYE_ROW && (col >= columns || holder.page() * columns + col >= data.capacity())) {
            return;
        }
        if (row <= DYE_ROW && data.isCorrupt(holder.page() * columns + col)) {
            playSound(player, "fail");
            message(player, "slot-corrupt");
            return;
        }
        if (row < ArmorSet.SIZE) {
            if (col >= columns) {
                return;
            }
            int setIndex = holder.page() * columns + col;
            if (data.activeIndex() == setIndex) {
                return;                          // the active set is locked
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
            if (rightClick) {
                rename(player, holder, setIndex);
            } else {
                handleDye(player, holder, setIndex);
            }
            return;
        }
        // admin buttons first, so one can sit on the nav row without being swallowed by paging
        for (Button button : buttons) {
            if (button.index() == slot) {
                runEffects(player, button.effects());
                return;
            }
        }
        if (row == NAV_ROW) {
            if (slot == previousSlot && holder.page() > 0) {
                // a configured left-click replaces the built-in behaviour
                if (!runEffects(player, clickEffects("nav.previous"))) {
                    openPage(player, holder, holder.page() - 1);
                }
            } else if (slot == nextSlot && holder.page() < pagesFor(data) - 1) {
                if (!runEffects(player, clickEffects("nav.next"))) {
                    openPage(player, holder, holder.page() + 1);
                }
            } else if (slot == closeSlot) {
                if (!runEffects(player, clickEffects("nav.close"))) {
                    player.closeInventory();
                }
            }
        }
    }

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
        if (cursorEmpty) {
            newCursor = current;
            set.pieces()[row] = null;
        } else {
            // a locked slot can still be emptied, but nothing new goes in
            if (holder.isLocked(setIndex)) {
                playSound(player, "fail");
                message(player, "slot-locked");
                return;
            }
            if (!isArmorForRow(cursor, row)) {
                playSound(player, "fail");
                return;
            }
            // some wearables stack: place one and keep the rest; a stack can't be swapped for a stored piece
            if (cursor.getAmount() > 1) {
                if (!currentEmpty) {
                    playSound(player, "fail");
                    return;
                }
                ItemStack one = cursor.clone();
                one.setAmount(1);
                ItemStack rest = cursor.clone();
                rest.setAmount(cursor.getAmount() - 1);
                set.pieces()[row] = one;
                newCursor = rest;
            } else {
                newCursor = currentEmpty ? null : current.clone();
                set.pieces()[row] = cursor.clone();
            }
        }
        player.setItemOnCursor(newCursor);
        persist(holder, setIndex);
        render(player, holder);
        // next tick, after the cancelled click is answered. Only resend state, never set the cursor again:
        // a click landing before this runs would get back a copy of a piece already put down.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.updateInventory();
            }
        });
    }

    /**
     * Shift-click deposit: place one armor piece into the first free matching slot on the current page.
     * Returns true if placed; the caller then removes one from the source stack.
     */
    public boolean tryDeposit(Player player, WardrobeHolder holder, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        int row = armorRow(item);
        if (row < 0) {
            return false;
        }
        WardrobeData data = holder.data();
        int page = holder.page();
        for (int c = 0; c < columns; c++) {
            int setIndex = page * columns + c;
            if (setIndex >= data.capacity()) {
                break;
            }
            if (data.activeIndex() == setIndex || holder.isLocked(setIndex) || data.isCorrupt(setIndex)) {
                continue;
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
        return false;
    }

    // from the equippable component, as the game does, so heads, elytra and custom wearables count
    private static int armorRow(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return -1;
        }
        Equippable equippable = item.getData(DataComponentTypes.EQUIPPABLE);
        if (equippable == null) {
            return -1;
        }
        return switch (equippable.slot()) {
            case HEAD -> ArmorSet.HELMET;
            case CHEST -> ArmorSet.CHEST;
            case LEGS -> ArmorSet.LEGS;
            case FEET -> ArmorSet.BOOTS;
            default -> -1;                       // hands, a horse's body, a saddle
        };
    }

    // the active set can be renamed too: a name belongs to the column, not to where its items are
    private void rename(Player player, WardrobeHolder holder, int setIndex) {
        WardrobeData data = holder.data();
        if (data.activeIndex() != setIndex && data.set(setIndex).isEmpty()) {
            playSound(player, "fail");
            message(player, "nothing-to-rename");
            return;
        }
        List<String> title = plugin.messages().lines("input.rename",
                List.of("&fName this setup", "&7Leave it blank to cancel."));
        textInput.request(player, title,
                typed -> {
                    if (!player.isOnline() || (typed == null && !canReopen(player))) {
                        return;
                    }
                    // the wardrobe may have changed while the dialog was open: only name the live copy,
                    // and reopen on whatever is live now
                    boolean named = false;
                    String name = typed == null ? "" : cleanName(typed);
                    if (!name.isEmpty() && plugin.sessions().isLive(holder.owner(), data)) {
                        data.setName(setIndex, name);
                        persist(holder, setIndex);
                        named = true;
                    }
                    int page = holder.page();
                    boolean announce = named;
                    withSession(player, session -> {
                        openPage(player, new WardrobeHolder(player.getUniqueId(), session.scope(), session.data(),
                                0, allowedSlots(player)), Math.min(page, pagesFor(session.data()) - 1));
                        if (announce) {
                            message(player, "renamed");
                            playSound(player, "store");
                        }
                    });
                });
    }

    // after a null answer the player may have died, opened another menu or started a newer prompt
    private boolean canReopen(Player player) {
        if (player.isDead() || textInput.isWaiting(player)) {
            return false;
        }
        InventoryType top = player.getOpenInventory().getTopInventory().getType();
        return top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
    }

    // legacy (&a, &k, §l) and hex (&#rrggbb)
    private static final java.util.regex.Pattern FORMAT_CODES =
            java.util.regex.Pattern.compile("(?i)[&§](#[0-9a-f]{6}|[0-9a-fk-orx])");

    // codes stripped so a name can't use &k or mimic the menu's state colours
    static String cleanName(String typed) {
        // strip until nothing changes: one pass turns "&&aa" into "&a", which is a code again
        String name = typed;
        String stripped;
        while (!(stripped = FORMAT_CODES.matcher(name).replaceAll("")).equals(name)) {
            name = stripped;
        }
        name = name.trim();
        return name.length() > 32 ? name.substring(0, 32).trim() : name;
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

    private void equip(Player player, WardrobeHolder holder, int target) {
        apply(player, holder, WardrobeActions.equip(holder.data(), body(player), target, now()));
    }

    private void storeCurrent(Player player, WardrobeHolder holder, int target) {
        apply(player, holder, WardrobeActions.storeCurrent(holder.data(), body(player), target, now()));
    }

    private void unequip(Player player, WardrobeHolder holder) {
        apply(player, holder, WardrobeActions.unequip(holder.data(), body(player)));
    }

    private void apply(Player player, WardrobeHolder holder, WardrobeActions.Result result) {
        String sound;
        String key;
        switch (result.outcome()) {
            case EQUIPPED -> { sound = "equip"; key = "equipped"; }
            case STORED -> { sound = "store"; key = "stored"; }
            case UNEQUIPPED -> { sound = "unequip"; key = "unequipped"; }
            case INVENTORY_FULL -> { sound = "fail"; key = "inventory-full"; }
            case NO_ARMOR -> { sound = "fail"; key = "no-armor"; }
            case ALREADY_ACTIVE -> { sound = "fail"; key = "already-active"; }
            default -> { return; }
        }
        if (result.outcome().succeeded()) {
            // equip and unequip move gear between player and table, so the player file is saved too
            boolean movedGear = result.outcome() != WardrobeActions.Outcome.STORED;
            persist(holder, movedGear, result.changed());
            render(player, holder);
        }
        playSound(player, sound);
        message(player, key);
    }

    private WardrobeActions.Body body(Player player) {
        return new WardrobeActions.Body() {
            @Override
            public ArmorSet worn() {
                return wornSet(player);
            }

            @Override
            public void wear(ItemStack[] pieces) {
                setWorn(player, pieces);
            }

            @Override
            public boolean stowWorn() {
                return moveToInventory(player, wornSet(player));
            }
        };
    }

    /** Equip a set by index without opening the menu ({@code /wardrobe equip <n>}). */
    public void equipDirect(Player player, int setIndex) {
        withSession(player, session -> {
            WardrobeHolder holder;
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof WardrobeHolder open
                    && open.data() == session.data()) {
                holder = open;
            } else {
                holder = new WardrobeHolder(player.getUniqueId(), session.scope(), session.data(), 0,
                        allowedSlots(player));
            }
            WardrobeData data = holder.data();
            if (setIndex < 0 || setIndex >= data.capacity()) {
                playSound(player, "fail");
                message(player, "no-such-slot");
                return;
            }
            if (data.isCorrupt(setIndex)) {
                playSound(player, "fail");
                message(player, "slot-corrupt");
                return;
            }
            if (holder.isLocked(setIndex)) {
                playSound(player, "fail");
                message(player, "slot-locked");
                return;
            }
            if (data.activeIndex() == setIndex) {
                playSound(player, "fail");
                message(player, "already-wearing");
                return;
            }
            if (data.set(setIndex).isEmpty()) {
                playSound(player, "fail");
                message(player, "setup-empty");
                return;
            }
            equip(player, holder, setIndex);
        });
    }

    /** Print the player's sets for {@code /wardrobe list}. */
    public void sendList(Player player) {
        withSession(player, session -> {
            WardrobeData data = session.data();
            int allowed = allowedSlots(player);
            player.sendMessage(Text.of("&6&lWardrobe &7— /wardrobe equip <number>"));
            for (int i = 0; i < data.capacity(); i++) {
                boolean active = data.activeIndex() == i;
                boolean locked = i >= allowed;
                ArmorSet set = data.set(i);
                if (!active && set.isEmpty() && data.name(i) == null && locked && !data.isCorrupt(i)) {
                    continue;
                }
                String state = active ? "&a(wearing)"
                        : data.isCorrupt(i) ? "&4(damaged — ask an admin)"
                        : locked ? "&c(locked)"
                        : set.isEmpty() ? "&8(empty)"
                        : "&7" + pieces(set);
                player.sendMessage(Text.of("&e" + (i + 1) + ". &f" + setName(data, i) + " " + state));
            }
        });
    }

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
        return armorRow(item) == row;
    }

    // Snapshots on the main thread, then writes all slots in one transaction on the writer thread.
    // savePlayer saves the player file about a second after the commit (coalesced per player), since
    // the table and the player file can't commit atomically together.
    private void persist(WardrobeHolder holder, boolean savePlayer, int... indexes) {
        WardrobeData data = holder.data();
        UUID owner = holder.owner();
        String scope = holder.scope();
        List<WardrobeStorage.SlotWrite> writes = new ArrayList<>(indexes.length);
        for (int index : indexes) {
            writes.add(WardrobeStorage.SlotWrite.of(data, index));
        }
        plugin.storage().submit(() -> {
            boolean saved = plugin.storage().saveAll(owner, scope, writes);
            if (!plugin.isEnabled()) {
                return;                          // drained on disable: nobody left to tell
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!saved) {
                    plugin.sessions().writeFailed(owner, scope, data, indexes);
                }
                Player online = plugin.getServer().getPlayer(owner);
                if (online == null) {
                    return;
                }
                if (!saved) {
                    // never silent: the gear is unaccounted for
                    message(online, "save-failed");
                } else if (savePlayer) {
                    savePlayerSoon(owner);
                }
            });
        });
    }

    private final java.util.Set<UUID> pendingPlayerSaves = new java.util.HashSet<>();
    private static final long PLAYER_SAVE_DELAY_TICKS = 20L;

    private void savePlayerSoon(UUID owner) {
        if (!pendingPlayerSaves.add(owner)) {
            return;   // the scheduled save will include this change
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingPlayerSaves.remove(owner);
            Player online = plugin.getServer().getPlayer(owner);
            if (online != null) {
                online.saveData();   // gone offline: the server saved them on quit
            }
        }, PLAYER_SAVE_DELAY_TICKS);
    }

    private void persist(WardrobeHolder holder, int index) {
        persist(holder, false, index);
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

    // row/column (1-indexed) or a raw slot, so configs in the old format keep working
    private int navSlot(String key, int def) {
        return slotFrom("nav." + key, def);
    }

    private int slotFrom(String path, int def) {
        int row = gui.getInt(path + ".row", -1);
        int column = gui.getInt(path + ".column", -1);
        if (row >= 1 && column >= 1 && column <= 9) {
            int index = (row - 1) * 9 + (column - 1);
            if (index >= 0 && index < SIZE) {
                return index;
            }
            plugin.getLogger().warning("Menu entry '" + path + "' is at row " + row + ", column " + column
                    + ", which is outside this menu; using the default slot instead.");
        }
        return gui.getInt(path + ".slot", def);
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private void playSound(Player player, String key) {
        if (!gui.getBoolean("sounds." + key + ".enabled", false)) {
            return;
        }
        String name = gui.getString("sounds." + key + ".name", "");
        if (name == null || name.isBlank()) {
            return;
        }
        playNamedSound(player, name, (float) gui.getDouble("sounds." + key + ".volume", 1.0),
                (float) gui.getDouble("sounds." + key + ".pitch", 1.0));
    }

    // fallbacks for keys missing from messages.yml
    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("equipped", "&aEquipped that setup."),
            Map.entry("stored", "&aStored your current setup."),
            Map.entry("unequipped", "&aUnequipped your setup."),
            Map.entry("no-armor", "&cYou aren't wearing any armor to store."),
            Map.entry("already-active", "&cYou're already wearing a saved setup — unequip it first."),
            Map.entry("slot-locked", "&cThat wardrobe slot is locked. Unlock more with a rank or perk."),
            Map.entry("inventory-full", "&cMake room in your inventory first."),
            Map.entry("save-failed", "&cYour wardrobe could not be saved — tell an admin before changing more sets."),
            Map.entry("save-pending", "&cYour last wardrobe change isn't saved yet. The wardrobe stays locked until it is."),
            Map.entry("save-recovered", "&aYour wardrobe has been saved and can be used again."),
            Map.entry("renamed", "&aSetup renamed."),
            Map.entry("nothing-to-rename", "&cStore a setup there first, then name it."),
            Map.entry("no-such-slot", "&cNo wardrobe slot with that number."),
            Map.entry("already-wearing", "&eYou're already wearing that setup."),
            Map.entry("setup-empty", "&cThat setup is empty."),
            Map.entry("no-profile", "&cJoin or create a profile before using your wardrobe."),
            Map.entry("load-failed", "&cYour wardrobe couldn't be loaded right now. Try again shortly."),
            Map.entry("slot-corrupt", "&cThat wardrobe slot is damaged and can't be used — tell an admin."));

    void notifySaved(Player player) {
        message(player, "save-recovered");
    }

    private void message(Player player, String key) {
        plugin.messages().send(player, key, DEFAULT_MESSAGES.getOrDefault(key, ""));
    }
}
