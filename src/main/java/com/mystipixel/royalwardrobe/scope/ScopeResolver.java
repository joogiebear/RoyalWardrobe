package com.mystipixel.royalwardrobe.scope;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Decides which "scope" a player's wardrobe belongs to, so sets don't leak across separate profiles.
 *
 * <p>When the scope placeholder's expansion is registered with PlaceholderAPI (RoyalSkyblock's
 * {@code %royalskyblock_profile_id%} by default), the scope is the player's active-profile id — so an
 * Ironman profile keeps its own wardrobe, exactly like Hypixel. When it isn't (no PlaceholderAPI, no
 * such expansion, or a blank placeholder), every player has one {@link #GLOBAL} wardrobe.
 *
 * <p>In per-profile mode an unresolved placeholder (a player between profiles) yields {@code null},
 * not {@link #GLOBAL}. The global wardrobe is shared by every profile, so falling back to it would let
 * gear be stored from one profile and taken out in another. No compile-time coupling to either plugin:
 * PlaceholderAPI is only touched when it's installed.
 */
public final class ScopeResolver {

    public static final String GLOBAL = "global";

    private final boolean placeholderApi;
    private final String placeholder;
    private final String expansion;

    public ScopeResolver(String placeholder) {
        this.placeholder = placeholder == null ? "" : placeholder.trim();
        this.expansion = expansionOf(this.placeholder);
        this.placeholderApi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /** The scope for {@code player}'s wardrobe, or {@code null} if per-profile and no profile resolves. */
    public String scopeFor(Player player) {
        if (!perProfile()) {
            return GLOBAL;
        }
        String resolved = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
        if (resolved != null) {
            resolved = resolved.trim();
            // A resolved value still containing '%' means no expansion handled it — treat as absent.
            if (!resolved.isEmpty() && !resolved.contains("%")) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * Whether wardrobes are scoped per profile. Checked live rather than at enable, because expansions
     * register after plugins load.
     */
    public boolean perProfile() {
        return placeholderApi && expansion != null
                && me.clip.placeholderapi.PlaceholderAPI.isRegistered(expansion);
    }

    /** The expansion identifier a placeholder belongs to: {@code %royalskyblock_profile_id%} → "royalskyblock". */
    static String expansionOf(String placeholder) {
        if (placeholder == null || !placeholder.startsWith("%")) {
            return null;
        }
        int underscore = placeholder.indexOf('_');
        if (underscore <= 1) {
            return null;
        }
        return placeholder.substring(1, underscore).toLowerCase(Locale.ROOT);
    }
}
