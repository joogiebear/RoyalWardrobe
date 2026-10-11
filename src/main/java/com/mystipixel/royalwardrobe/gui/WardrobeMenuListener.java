package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Every click in the wardrobe grid is cancelled and the menu moves items itself. The player's own
 * inventory stays usable, but anything that would move items across the boundary uncontrolled
 * (shift-transfer, double-click collect, drag across) is blocked to keep the wardrobe dupe-safe.
 */
public final class WardrobeMenuListener implements Listener {

    private final RoyalWardrobePlugin plugin;

    public WardrobeMenuListener(RoyalWardrobePlugin plugin) {
        this.plugin = plugin;
    }

    // HIGHEST: this decides the click's final cancelled state. At the default priority a later
    // listener could un-cancel it, letting vanilla move items into or out of the grid uncontrolled.
    @EventHandler(priority = EventPriority.HIGHEST)
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

        // The profile changed under an open menu: its sets belong to the old profile while the armor
        // on the player now belongs to the new one, so no click may act on it.
        if (!holder.scope().equals(plugin.scopes().scopeFor(player))) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }

        if (clicked != null && clicked.getHolder() instanceof WardrobeHolder) {
            event.setCancelled(true);
            if (click == ClickType.LEFT || click == ClickType.RIGHT) {
                plugin.menu().handleClick(player, holder, event.getSlot(), click == ClickType.RIGHT);
            }
            return;
        }

        // shift / collect / hotbar-swap could move items across the boundary; shift-click deposits one piece instead
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
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof WardrobeHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
