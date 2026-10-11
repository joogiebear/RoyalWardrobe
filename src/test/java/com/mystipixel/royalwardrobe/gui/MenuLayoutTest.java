package com.mystipixel.royalwardrobe.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuLayoutTest {

    @Test
    void navigationButtonsSitOnSeparateSlotsInTheBottomRow() throws IOException {
        YamlConfiguration gui = load();
        Map<Integer, String> taken = new HashMap<>();
        for (String button : List.of("previous", "next", "close")) {
            int row = gui.getInt("nav." + button + ".row");
            int column = gui.getInt("nav." + button + ".column");
            assertEquals(6, row, button + " row");
            assertTrue(column >= 1 && column <= 9, button + " column");
            assertNull(taken.put((row - 1) * 9 + column - 1, button), button + " shares a slot");
        }
        assertEquals(List.of(), gui.getMapList("buttons"));
    }

    // the text that used to be built in WardrobeMenu, now read from the menu file
    @Test
    void shippedFormatsMatchWhatTheCodeShowedBefore() throws IOException {
        YamlConfiguration gui = load();
        assertEquals("Setup #" + 7, gui.getString("default-name").replace("%number%", "7"));
        assertEquals("empty", gui.getString("no-pieces"));
        assertEquals("MMM d, yyyy", gui.getString("date-format"));
    }

    @Test
    void everyItemHasAName() throws IOException {
        YamlConfiguration gui = load();
        for (String path : List.of("title", "nav.filler", "empty-slot.item", "locked-slot.item", "corrupt-slot.item",
                "dye.store.item", "dye.equip.item", "dye.locked.item", "dye.corrupt.item", "dye.unequip.item",
                "nav.previous.item", "nav.next.item", "nav.close.item")) {
            String value = gui.getString(path);
            assertNotNull(value, path);
            assertTrue(path.equals("title") || value.contains("name:\""), path + " has no name");
        }
    }

    private static YamlConfiguration load() throws IOException {
        try (InputStream in = MenuLayoutTest.class.getClassLoader().getResourceAsStream("gui/wardrobe.yml")) {
            assertNotNull(in, "gui/wardrobe.yml is not bundled");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }
}
