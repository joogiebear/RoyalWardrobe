package com.mystipixel.royalwardrobe.wardrobe;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/**
 * One stored armor loadout: exactly four pieces in fixed order — helmet, chestplate, leggings, boots.
 * Any slot may be {@code null} (a partial set). Items are real {@link ItemStack}s with their full NBT
 * (so EcoArmor stats survive a round-trip), never copies — the wardrobe moves gear, it never clones it,
 * so there is no way to duplicate a stat item.
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
     * A detached deep copy. Persistence must snapshot on the main thread before handing a set to the
     * writer — serializing the live array while the player keeps clicking would capture a half-applied
     * state, or a state that never existed.
     */
    public ArmorSet snapshot() {
        ItemStack[] copy = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) {
            copy[i] = pieces[i] == null ? null : pieces[i].clone();
        }
        return new ArmorSet(copy);
    }

    /** The backing array (length 4, nulls allowed). Mutating it mutates the set — intended for swaps. */
    public ItemStack[] pieces() {
        return pieces;
    }

    public ItemStack piece(int index) {
        return pieces[index];
    }

    public boolean isEmpty() {
        return Arrays.stream(pieces).allMatch(p -> p == null || p.getType().isAir());
    }

    /** A representative icon item for the GUI: the chestplate if present, else the first non-empty piece. */
    public ItemStack icon() {
        if (pieces[CHEST] != null && !pieces[CHEST].getType().isAir()) {
            return pieces[CHEST];
        }
        for (ItemStack p : pieces) {
            if (p != null && !p.getType().isAir()) {
                return p;
            }
        }
        return null;
    }
}
