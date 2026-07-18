package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.wardrobe.ArmorSet;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Carries a wardrobe session's state on the open inventory itself: whose it is, which scope it was
 * loaded for, and the live sets. Using an {@link InventoryHolder} means the click listener can tell a
 * wardrobe inventory apart from any other by identity, with no title-string matching.
 */
public final class WardrobeHolder implements InventoryHolder {

    private final UUID owner;
    private final String scope;
    private final ArmorSet[] sets;
    private Inventory inventory;

    public WardrobeHolder(UUID owner, String scope, ArmorSet[] sets) {
        this.owner = owner;
        this.scope = scope;
        this.sets = sets;
    }

    public UUID owner() {
        return owner;
    }

    public String scope() {
        return scope;
    }

    public ArmorSet[] sets() {
        return sets;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
