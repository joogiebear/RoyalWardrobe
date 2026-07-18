package com.mystipixel.royalwardrobe.gui.menu;

import java.util.List;

/**
 * A fixed, hand-placed slot from a menu's {@code slots:} list — its resolved 0-based index, the item
 * to render, its lore, and the left/right-click effect lists. {@code id} is an optional semantic tag
 * the renderer can key state off.
 */
public record MenuSlot(int index,
                       String id,
                       ItemSpec item,
                       List<String> lore,
                       List<MenuEffect> leftClick,
                       List<MenuEffect> rightClick) {
}
