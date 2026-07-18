package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Routes clicks on a {@link WardrobeHolder} inventory into wardrobe actions. Every interaction is
 * cancelled first — the menu moves items itself (via the cursor), so there is no uncontrolled path for
 * an item to enter or leave, which is what keeps the wardrobe dupe-safe. Only plain left/right clicks
 * on the wardrobe (top) inventory are acted on; number-key swaps, drops and drags are just blocked.
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
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Only handle simple left/right clicks on the wardrobe itself; block everything else.
        ClickType click = event.getClick();
        boolean simple = click == ClickType.LEFT || click == ClickType.RIGHT
                || click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        if (simple && event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof WardrobeHolder) {
            plugin.menu().handleClick(player, holder, event.getSlot());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WardrobeHolder) {
            event.setCancelled(true);
        }
    }
}
