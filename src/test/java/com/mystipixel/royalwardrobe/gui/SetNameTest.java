package com.mystipixel.royalwardrobe.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SetNameTest {

    @Test
    void colourAndFormatCodesAreStripped() {
        assertEquals("PvP", WardrobeMenu.cleanName("&a&lPvP"));
        assertEquals("Mining", WardrobeMenu.cleanName("&kMin§ling"));
        assertEquals("Boss", WardrobeMenu.cleanName("&#ff00aaBoss"));
    }

    @Test
    void ordinaryAmpersandsAndPercentsSurvive() {
        assertEquals("Salt & Pepper", WardrobeMenu.cleanName("Salt & Pepper"));
        assertEquals("100% crit", WardrobeMenu.cleanName("100% crit"));
    }

    @Test
    void namesAreTrimmedAndCappedAt32() {
        assertEquals("Tank", WardrobeMenu.cleanName("   Tank  "));
        assertEquals(32, WardrobeMenu.cleanName("x".repeat(40)).length());
        assertEquals("", WardrobeMenu.cleanName("&a&l"));
    }
}
