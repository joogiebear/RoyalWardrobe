package com.mystipixel.royalwardrobe.scope;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Decides which scope a player's wardrobe belongs to: the active-profile id when the scope placeholder's
 * PlaceholderAPI expansion is registered, otherwise one {@link #GLOBAL} wardrobe per player.
 *
 * <p>In per-profile mode an unresolved placeholder yields {@code null}, never {@link #GLOBAL}: the global
 * wardrobe is shared by every profile, so falling back to it would move gear between profiles. Per-profile
 * mode stays latched once the expansion has been seen, until a restart.
 */
public final class ScopeResolver {

    public static final String GLOBAL = "global";

    private final boolean placeholderApi;
    private final String placeholder;
    private final String expansion;
    private boolean seenExpansion;

    public ScopeResolver(String placeholder) {
        this(placeholder, null);
    }

    /** As above, keeping {@code previous}'s per-profile latch when the placeholder is unchanged (a reload). */
    public ScopeResolver(String placeholder, ScopeResolver previous) {
        this.placeholder = placeholder == null ? "" : placeholder.trim();
        this.expansion = expansionOf(this.placeholder);
        this.placeholderApi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        this.seenExpansion = previous != null && previous.seenExpansion && previous.placeholder.equals(this.placeholder);
    }

    /** The scope for {@code player}'s wardrobe, or {@code null} if per-profile and no profile resolves. */
    public String scopeFor(Player player) {
        if (!perProfile()) {
            return GLOBAL;
        }
        if (!registered()) {
            return null;   // per-profile, but the expansion went away: refuse rather than share
        }
        String resolved = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
        if (resolved != null) {
            resolved = resolved.trim();
            // still containing '%' means no expansion handled it
            if (!resolved.isEmpty() && !resolved.contains("%")) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * Whether wardrobes are scoped per profile. Checked live rather than at enable, because expansions
     * register after plugins load, and latched once the expansion has been seen.
     */
    public boolean perProfile() {
        seenExpansion = perProfile(registered(), seenExpansion);
        return seenExpansion;
    }

    static boolean perProfile(boolean registeredNow, boolean seenBefore) {
        return registeredNow || seenBefore;
    }

    private boolean registered() {
        return placeholderApi && expansion != null
                && me.clip.placeholderapi.PlaceholderAPI.isRegistered(expansion);
    }

    // %royalskyblock_profile_id% -> "royalskyblock"
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
