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
 *
 * <p>Once the expansion has been seen, per-profile mode sticks: an expansion that is unregistered at
 * runtime ({@code /papi reload}, the profile plugin reloading) must refuse wardrobes until it's back,
 * not quietly fall back to the global one. A restart re-decides, so removing the profile plugin still
 * returns the server to per-player wardrobes.
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
            // A resolved value still containing '%' means no expansion handled it — treat as absent.
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

    /** The mode decision: per-profile when the expansion is registered now or was seen before. */
    static boolean perProfile(boolean registeredNow, boolean seenBefore) {
        return registeredNow || seenBefore;
    }

    private boolean registered() {
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
