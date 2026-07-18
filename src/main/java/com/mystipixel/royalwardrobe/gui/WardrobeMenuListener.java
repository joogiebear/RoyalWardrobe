package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Routes wardrobe interaction. The wardrobe (top) grid is fully controlled — every click there is
 * cancelled and the menu moves items itself via the cursor. The player's own (bottom) inventory stays
 * normally usable so they can pick pieces up to place them, but any action that would move items
 * <em>into</em> or <em>out of</em> the wardrobe uncontrolled (shift-transfer, double-click collect,
 * drag across) is blocked — shift-clicking an armor piece instead deposits it into the first free slot.
 * That's what keeps the wardrobe dupe-safe while still being easy to fill.
 */
public final class WardrobeMenuListener implements Listener {

    private final RoyalWardrobePlugin plugin;

    public WardrobeMenuListener(RoyalWardrobePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof WardrobeHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        Inventory clicked = event.getClickedInventory();
        ClickType click = event.getClick();

        // A click in the wardrobe grid: fully controlled.
        if (clicked != null && clicked.getHolder() instanceof WardrobeHolder) {
            event.setCancelled(true);
            if (click == ClickType.LEFT || click == ClickType.RIGHT) {
                plugin.menu().handleClick(player, holder, event.getSlot());
            }
            return;
        }

        // A click in the player's own inventory. Shift / collect / hotbar-swap could move items across
        // the boundary uncontrolled, so block those; shift-click deposits one armor piece instead.
        boolean shift = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        if (shift) {
            event.setCancelled(true);
            ItemStack item = event.getCurrentItem();
            if (item != null && plugin.menu().tryDeposit(player, holder, item)) {
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else if (clicked != null) {
                    clicked.setItem(event.getSlot(), null);
                }
            }
            return;
        }
        if (click == ClickType.DOUBLE_CLICK || click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
        }
        // else: plain left/right/drop in the player's own inventory — allowed (needed to pick pieces up).
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof WardrobeHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {                 // drag touches the wardrobe grid — block it
                event.setCancelled(true);
                return;
            }
        }
        // drag entirely within the player's own inventory — allowed
    }
}
