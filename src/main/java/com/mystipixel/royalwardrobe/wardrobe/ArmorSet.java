package com.mystipixel.royalwardrobe.wardrobe;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/**
 * One stored armor loadout: helmet, chestplate, leggings, boots, any of which may be {@code null}.
 * The wardrobe moves these items, it never clones them, so a stat item can't be duplicated.
 */
public final class ArmorSet {

    public static final int SIZE = 4;
    public static final int HELMET = 0;
    public static final int CHEST = 1;
    public static final int LEGS = 2;
    public static final int BOOTS = 3;

    private final ItemStack[] pieces;

    public ArmorSet(ItemStack[] pieces) {
        this.pieces = new ItemStack[SIZE];
        if (pieces != null) {
            System.arraycopy(pieces, 0, this.pieces, 0, Math.min(SIZE, pieces.length));
        }
    }

    public static ArmorSet empty() {
        return new ArmorSet(new ItemStack[SIZE]);
    }

    /**
     * A detached deep copy. Take it on the main thread before handing a set to the writer; serializing
     * the live array while the player keeps clicking can capture a half-applied state.
     */
    public ArmorSet snapshot() {
        ItemStack[] copy = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) {
            copy[i] = pieces[i] == null ? null : pieces[i].clone();
        }
        return new ArmorSet(copy);
    }

    /** The backing array (length 4, nulls allowed). Mutating it mutates the set, which swaps rely on. */
    public ItemStack[] pieces() {
        return pieces;
    }

    public ItemStack piece(int index) {
        return pieces[index];
    }

    public boolean isEmpty() {
        return Arrays.stream(pieces).allMatch(p -> p == null || p.isEmpty());
    }

    /** A representative icon item for the GUI: the chestplate if present, else the first non-empty piece. */
    public ItemStack icon() {
        if (pieces[CHEST] != null && !pieces[CHEST].isEmpty()) {
            return pieces[CHEST];
        }
        for (ItemStack p : pieces) {
            if (p != null && !p.isEmpty()) {
                return p;
            }
        }
        return null;
    }
}
