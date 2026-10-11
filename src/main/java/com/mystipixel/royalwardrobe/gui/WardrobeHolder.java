package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.wardrobe.WardrobeData;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * A wardrobe session's state carried on the open inventory, so the listener can identify a wardrobe
 * inventory and its page without matching titles.
 */
public final class WardrobeHolder implements InventoryHolder {

    private final UUID owner;
    private final String scope;
    private final WardrobeData data;
    private final int allowedSlots;
    private int page;
    private Inventory inventory;

    public WardrobeHolder(UUID owner, String scope, WardrobeData data, int page, int allowedSlots) {
        this.owner = owner;
        this.scope = scope;
        this.data = data;
        this.page = page;
        this.allowedSlots = allowedSlots;
    }

    /**
     * Slots this player may use, resolved from permissions when the menu opened. Gear in locked slots
     * stays retrievable; it just can't be added to.
     */
    public int allowedSlots() {
        return allowedSlots;
    }

    public boolean isLocked(int setIndex) {
        return setIndex >= allowedSlots;
    }

    public UUID owner() {
        return owner;
    }

    public String scope() {
        return scope;
    }

    public WardrobeData data() {
        return data;
    }

    public int page() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
