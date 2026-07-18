package com.mystipixel.royalwardrobe.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Turns {@code &}-code strings from config into Adventure {@link Component}s (the non-deprecated path),
 * with italics off by default so item names/lore render clean.
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component of(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy).decoration(TextDecoration.ITALIC, false);
    }
}
