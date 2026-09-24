package com.mystipixel.royalwardrobe.wardrobe;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The wardrobe's promise is that every piece is always in exactly one place: worn, stored or in the
 * inventory. These drive the moves against a fake player and count where each piece ended up.
 */
class WardrobeActionsTest {

    private static final long NOW = 1_700_000_000_000L;

    /** A player's armor slots and inventory, with a fixed number of free inventory slots. */
    private static final class FakeBody implements WardrobeActions.Body {
        ItemStack[] worn = new ItemStack[ArmorSet.SIZE];
        final List<ItemStack> inventory = new ArrayList<>();
        int freeSlots = 36;

        @Override
        public ArmorSet worn() {
            return new ArmorSet(worn.clone());
        }

        @Override
        public void wear(ItemStack[] pieces) {
            worn = pieces.clone();
        }

        @Override
        public boolean stowWorn() {
            List<ItemStack> pieces = Arrays.stream(worn).filter(Objects::nonNull).toList();
            if (pieces.size() > freeSlots) {
                return false;
            }
            inventory.addAll(pieces);
            freeSlots -= pieces.size();
            worn = new ItemStack[ArmorSet.SIZE];
            return true;
        }
    }

    private static ItemStack piece(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        return item;
    }

    private static ItemStack[] fullSet(String tier) {
        return new ItemStack[]{
                piece(Material.valueOf(tier + "_HELMET")),
                piece(Material.valueOf(tier + "_CHESTPLATE")),
                piece(Material.valueOf(tier + "_LEGGINGS")),
                piece(Material.valueOf(tier + "_BOOTS"))};
    }

    private static WardrobeData wardrobe(int capacity) {
        ArmorSet[] sets = new ArmorSet[capacity];
        for (int i = 0; i < capacity; i++) {
            sets[i] = ArmorSet.empty();
        }
        return new WardrobeData(sets, new long[capacity], new String[capacity], new boolean[capacity], -1);
    }

    /** How many times each piece appears across the body, the inventory and every stored set. */
    private static Map<ItemStack, Integer> places(WardrobeData data, FakeBody body) {
        Map<ItemStack, Integer> seen = new IdentityHashMap<>();
        List<ItemStack> all = new ArrayList<>(Arrays.asList(body.worn));
        all.addAll(body.inventory);
        for (int i = 0; i < data.capacity(); i++) {
            all.addAll(Arrays.asList(data.set(i).pieces()));
        }
        for (ItemStack item : all) {
            if (item != null) {
                seen.merge(item, 1, Integer::sum);
            }
        }
        return seen;
    }

    private static void assertEachExactlyOnce(WardrobeData data, FakeBody body, ItemStack[]... sets) {
        Map<ItemStack, Integer> seen = places(data, body);
        for (ItemStack[] set : sets) {
            for (ItemStack item : set) {
                assertEquals(1, seen.getOrDefault(item, 0), "a piece is in " + seen.getOrDefault(item, 0) + " places");
            }
        }
    }

    @Test
    void equipWithNothingWornPutsTheSetOnAndEmptiesItsSlot() {
        WardrobeData data = wardrobe(4);
        ItemStack[] diamond = fullSet("DIAMOND");
        data.setSet(2, new ArmorSet(diamond));
        FakeBody body = new FakeBody();

        WardrobeActions.Result result = WardrobeActions.equip(data, body, 2, NOW);

        assertEquals(WardrobeActions.Outcome.EQUIPPED, result.outcome());
        assertArrayEquals(new int[]{2}, result.changed());
        assertArrayEquals(diamond, body.worn);
        assertTrue(data.set(2).isEmpty());
        assertEquals(2, data.activeIndex());
        assertEquals(NOW, data.firstWorn(2));
        assertEachExactlyOnce(data, body, diamond);
    }

    @Test
    void equipOverAnActiveSetSendsItBackToItsOwnSlotInOneWrite() {
        WardrobeData data = wardrobe(4);
        ItemStack[] iron = fullSet("IRON");
        ItemStack[] diamond = fullSet("DIAMOND");
        data.setSet(1, new ArmorSet(diamond));
        FakeBody body = new FakeBody();
        body.worn = iron.clone();
        data.setActiveIndex(0);                  // iron is set 0, being worn

        WardrobeActions.Result result = WardrobeActions.equip(data, body, 1, NOW);

        assertEquals(WardrobeActions.Outcome.EQUIPPED, result.outcome());
        assertArrayEquals(new int[]{0, 1}, result.changed(), "both slots must be persisted together");
        assertArrayEquals(iron, data.set(0).pieces());
        assertArrayEquals(diamond, body.worn);
        assertEquals(1, data.activeIndex());
        assertEachExactlyOnce(data, body, iron, diamond);
    }

    @Test
    void equipOverLooseArmorStowsItInTheInventory() {
        WardrobeData data = wardrobe(4);
        ItemStack[] leather = fullSet("LEATHER");
        ItemStack[] diamond = fullSet("DIAMOND");
        data.setSet(3, new ArmorSet(diamond));
        FakeBody body = new FakeBody();
        body.worn = leather.clone();

        WardrobeActions.equip(data, body, 3, NOW);

        assertTrue(body.inventory.containsAll(List.of(leather)));
        assertArrayEquals(diamond, body.worn);
        assertEachExactlyOnce(data, body, leather, diamond);
    }

    @Test
    void equipWithAFullInventoryChangesNothing() {
        WardrobeData data = wardrobe(4);
        ItemStack[] leather = fullSet("LEATHER");
        ItemStack[] diamond = fullSet("DIAMOND");
        data.setSet(3, new ArmorSet(diamond));
        FakeBody body = new FakeBody();
        body.worn = leather.clone();
        body.freeSlots = 3;

        WardrobeActions.Result result = WardrobeActions.equip(data, body, 3, NOW);

        assertEquals(WardrobeActions.Outcome.INVENTORY_FULL, result.outcome());
        assertEquals(0, result.changed().length);
        assertArrayEquals(leather, body.worn);
        assertArrayEquals(diamond, data.set(3).pieces());
        assertEquals(-1, data.activeIndex());
        assertEquals(0, data.firstWorn(3));
    }

    @Test
    void storeCurrentClaimsTheWornArmorWithoutMovingIt() {
        WardrobeData data = wardrobe(4);
        ItemStack[] iron = fullSet("IRON");
        FakeBody body = new FakeBody();
        body.worn = iron.clone();

        WardrobeActions.Result result = WardrobeActions.storeCurrent(data, body, 1, NOW);

        assertEquals(WardrobeActions.Outcome.STORED, result.outcome());
        assertArrayEquals(iron, body.worn);
        assertTrue(data.set(1).isEmpty(), "the active set's items live on the player, not in its slot");
        assertEquals(1, data.activeIndex());
        assertEachExactlyOnce(data, body, iron);
    }

    @Test
    void storeCurrentRefusesWhileASetIsWornOrNothingIs() {
        WardrobeData data = wardrobe(4);
        FakeBody body = new FakeBody();
        assertEquals(WardrobeActions.Outcome.NO_ARMOR, WardrobeActions.storeCurrent(data, body, 0, NOW).outcome());

        body.worn = fullSet("IRON");
        data.setActiveIndex(2);
        assertEquals(WardrobeActions.Outcome.ALREADY_ACTIVE,
                WardrobeActions.storeCurrent(data, body, 0, NOW).outcome());
        assertEquals(2, data.activeIndex());
    }

    @Test
    void unequipPutsTheWornSetBackAndBareTheArmorSlots() {
        WardrobeData data = wardrobe(4);
        ItemStack[] iron = fullSet("IRON");
        FakeBody body = new FakeBody();
        body.worn = iron.clone();
        data.setActiveIndex(2);

        WardrobeActions.Result result = WardrobeActions.unequip(data, body);

        assertEquals(WardrobeActions.Outcome.UNEQUIPPED, result.outcome());
        assertArrayEquals(new int[]{2}, result.changed());
        assertArrayEquals(iron, data.set(2).pieces());
        assertTrue(Arrays.stream(body.worn).allMatch(Objects::isNull));
        assertEquals(-1, data.activeIndex());
        assertEachExactlyOnce(data, body, iron);
    }

    @Test
    void unequipWithNothingActiveDoesNothing() {
        WardrobeData data = wardrobe(2);
        FakeBody body = new FakeBody();
        ItemStack[] iron = fullSet("IRON");
        body.worn = iron.clone();

        assertEquals(WardrobeActions.Outcome.NONE, WardrobeActions.unequip(data, body).outcome());
        assertArrayEquals(iron, body.worn);
    }

    @Test
    void aLongRunOfSwapsNeverCopiesOrLosesAPiece() {
        WardrobeData data = wardrobe(3);
        ItemStack[] iron = fullSet("IRON");
        ItemStack[] gold = fullSet("GOLDEN");
        ItemStack[] diamond = fullSet("DIAMOND");
        FakeBody body = new FakeBody();
        body.worn = iron.clone();
        data.setSet(1, new ArmorSet(gold));
        data.setSet(2, new ArmorSet(diamond));

        WardrobeActions.storeCurrent(data, body, 0, NOW);
        WardrobeActions.equip(data, body, 1, NOW);
        WardrobeActions.equip(data, body, 2, NOW);
        WardrobeActions.equip(data, body, 0, NOW);
        WardrobeActions.unequip(data, body);
        WardrobeActions.equip(data, body, 1, NOW);

        assertEachExactlyOnce(data, body, iron, gold, diamond);
        assertSame(gold[0], body.worn[0]);
        assertNull(data.set(1).piece(0));
    }
}
