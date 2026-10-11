package com.mystipixel.royalwardrobe.wardrobe;

/**
 * A player's wardrobe for one scope. {@code firstWorn} is epoch millis (0 = never); the active index is
 * -1 for none.
 *
 * <p>Dupe-safety invariant: the active set's items live on the player and {@code sets[activeIndex]} is
 * empty in storage, so every item exists in exactly one place. A corrupt slot (row present but not
 * decodable) is never written to, so the row survives for an admin to recover.
 */
public final class WardrobeData {

    private final ArmorSet[] sets;
    private final long[] firstWorn;
    private final String[] names;      // player-given set names; null = the default "Setup #N"
    private final boolean[] corrupt;
    private int activeIndex;

    public WardrobeData(ArmorSet[] sets, long[] firstWorn, String[] names, boolean[] corrupt, int activeIndex) {
        this.sets = sets;
        this.firstWorn = firstWorn;
        this.names = names;
        this.corrupt = corrupt;
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

    /** The player-given name for a set, or null when it still has the default. */
    public String name(int index) {
        return names[index];
    }

    public void setName(int index, String name) {
        names[index] = name == null || name.isBlank() ? null : name;
    }

    public int activeIndex() {
        return activeIndex;
    }

    public void setActiveIndex(int index) {
        this.activeIndex = index;
    }

    /** Whether this slot's stored row failed to decode. It must be neither shown as empty nor saved over. */
    public boolean isCorrupt(int index) {
        return corrupt[index];
    }

    public boolean isActive(int index) {
        return index == activeIndex;
    }
}
