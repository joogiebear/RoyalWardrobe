package com.mystipixel.royalwardrobe.scope;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Decides which "scope" a player's wardrobe belongs to, so sets don't leak across separate profiles.
 *
 * <p>When RoyalSkyblock + PlaceholderAPI are present, the scope is the player's active-profile id
 * ({@code %royalskyblock_profile_id%}) — so an Ironman profile keeps its own wardrobe, exactly like
 * Hypixel. When they aren't (or the placeholder is empty, e.g. the player is between profiles), the
 * scope falls back to {@link #GLOBAL} — one wardrobe per player. No compile-time coupling to either
 * plugin: the placeholder is resolved reflectively via PlaceholderAPI only when it's installed.
 */
public final class ScopeResolver {

    public static final String GLOBAL = "global";

    private final boolean placeholderApi;
    private final String placeholder;

    public ScopeResolver(String placeholder) {
        this.placeholder = placeholder;
        this.placeholderApi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public String scopeFor(Player player) {
        if (placeholderApi && placeholder != null && !placeholder.isBlank()) {
            String resolved = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
            if (resolved != null) {
                resolved = resolved.trim();
                // A resolved value still containing '%' means no expansion handled it — treat as absent.
                if (!resolved.isEmpty() && !resolved.contains("%")) {
                    return resolved;
                }
            }
        }
        return GLOBAL;
    }

    public boolean perProfile() {
        return placeholderApi;
    }
}
