package com.mystipixel.royalwardrobe.wardrobe;

/**
 * A player's whole wardrobe for one scope: the stored sets (by column index), the date each set was
 * first worn (epoch millis, 0 = never), and which column is currently <em>active</em> (the set the
 * player is wearing) or -1 for none.
 *
 * <p><b>Dupe-safety invariant:</b> the active set's real items live on the player, not in
 * {@code sets[activeIndex]} — that entry is empty in storage. Every item therefore exists in exactly
 * one place (on the player if active, in storage otherwise), so a set can never be duplicated.
 */
public final class WardrobeData {

    private final ArmorSet[] sets;
    private final long[] firstWorn;
    private int activeIndex;

    public WardrobeData(ArmorSet[] sets, long[] firstWorn, int activeIndex) {
        this.sets = sets;
        this.firstWorn = firstWorn;
        this.activeIndex = activeIndex;
    }

    public int capacity() {
        return sets.length;
    }

    public ArmorSet set(int index) {
        return sets[index];
    }

    public void setSet(int index, ArmorSet set) {
        sets[index] = set;
    }

    public long firstWorn(int index) {
        return firstWorn[index];
    }

    public void setFirstWorn(int index, long millis) {
        firstWorn[index] = millis;
    }

    public int activeIndex() {
        return activeIndex;
    }

    public void setActiveIndex(int index) {
        this.activeIndex = index;
    }

    public boolean isActive(int index) {
        return index == activeIndex;
    }
}
