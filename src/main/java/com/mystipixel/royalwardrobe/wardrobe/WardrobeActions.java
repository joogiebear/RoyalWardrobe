package com.mystipixel.royalwardrobe.wardrobe;

import org.bukkit.inventory.ItemStack;

/**
 * The three moves between the armor a player wears and their stored sets. Everything that decides
 * where a piece ends up lives here, apart from Bukkit's {@code Player}, so the dupe-safety invariant in
 * {@link WardrobeData} can be tested directly: after any of these, each piece is either worn or stored,
 * never both and never neither.
 *
 * <p>Each move mutates {@code data} and the {@link Body} together, and reports which slots changed so
 * the caller can persist them as one write.
 */
public final class WardrobeActions {

    private WardrobeActions() {
    }

    /** The player's armor slots and inventory, as the moves need them. */
    public interface Body {

        /** What the player is wearing now, helmet to boots. */
        ArmorSet worn();

        /** Put these pieces on (nulls clear a slot). */
        void wear(ItemStack[] pieces);

        /**
         * Move every worn piece into the inventory and leave the armor slots empty — or, if they
         * won't all fit, change nothing and return false.
         */
        boolean stowWorn();
    }

    public enum Outcome {
        EQUIPPED, STORED, UNEQUIPPED,
        /** Loose armor had to go to the inventory first, and there was no room. */
        INVENTORY_FULL,
        /** Nothing is worn, so there's nothing to store. */
        NO_ARMOR,
        /** A saved set is already worn; it must be unequipped before storing a new one. */
        ALREADY_ACTIVE,
        /** Nothing to do. */
        NONE;

        public boolean succeeded() {
            return this == EQUIPPED || this == STORED || this == UNEQUIPPED;
        }
    }

    /** What happened, and the slots whose stored state changed (to be persisted together). */
    public record Result(Outcome outcome, int... changed) {
    }

    /**
     * Wear the set in {@code target}. The set being worn goes back to its own slot; armor worn without
     * belonging to a set goes to the inventory, and if it can't, nothing moves.
     */
    public static Result equip(WardrobeData data, Body body, int target, long now) {
        ArmorSet worn = body.worn();
        int active = data.activeIndex();
        int[] changed;

        if (active != -1) {
            data.setSet(active, worn);           // the set you were wearing goes back to its column
            data.setActiveIndex(-1);
            changed = new int[]{active, target};
        } else {
            if (!worn.isEmpty() && !body.stowWorn()) {
                return new Result(Outcome.INVENTORY_FULL);
            }
            changed = new int[]{target};
        }

        body.wear(data.set(target).pieces());
        data.setSet(target, ArmorSet.empty());   // items are on the player now
        data.setActiveIndex(target);
        if (data.firstWorn(target) <= 0) {
            data.setFirstWorn(target, now);
        }
        return new Result(Outcome.EQUIPPED, changed);
    }

    /** Make the armor being worn the set for {@code target}. Nothing moves; the slot just claims it. */
    public static Result storeCurrent(WardrobeData data, Body body, int target, long now) {
        if (data.activeIndex() != -1) {
            return new Result(Outcome.ALREADY_ACTIVE);
        }
        if (body.worn().isEmpty()) {
            return new Result(Outcome.NO_ARMOR);
        }
        data.setActiveIndex(target);             // your worn armor becomes this (active) set
        data.setFirstWorn(target, now);
        data.setSet(target, ArmorSet.empty());
        return new Result(Outcome.STORED, target);
    }

    /** Take the worn set off and back into its slot. */
    public static Result unequip(WardrobeData data, Body body) {
        int active = data.activeIndex();
        if (active == -1) {
            return new Result(Outcome.NONE);
        }
        data.setSet(active, body.worn());        // worn armor goes back into its column
        data.setActiveIndex(-1);
        body.wear(new ItemStack[ArmorSet.SIZE]);
        return new Result(Outcome.UNEQUIPPED, active);
    }
}
