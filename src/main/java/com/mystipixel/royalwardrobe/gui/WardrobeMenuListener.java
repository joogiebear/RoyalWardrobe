package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Turns clicks on a {@link WardrobeHolder} inventory into wardrobe actions. Every interaction with the
 * menu is cancelled first — the menu never lets an item be picked up or dropped directly, so there is
 * no path to pull gear out except the controlled withdraw action. This is what keeps it dupe-safe.
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
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() == null) {
            return;
        }
        // Only clicks in the wardrobe (top) inventory do anything; the cancel above blocks the rest.
        if (event.getClickedInventory().getHolder() instanceof WardrobeHolder) {
            plugin.menu().handleClick(player, holder, event.getSlot(), event.isShiftClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WardrobeHolder) {
            event.setCancelled(true);
        }
    }
}
