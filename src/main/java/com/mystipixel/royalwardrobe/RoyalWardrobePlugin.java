package com.mystipixel.royalwardrobe;

import com.mystipixel.royalwardrobe.gui.WardrobeMenu;
import com.mystipixel.royalwardrobe.gui.WardrobeMenuListener;
import com.mystipixel.royalwardrobe.scope.ScopeResolver;
import com.mystipixel.royalwardrobe.storage.WardrobeStorage;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RoyalWardrobe — a Hypixel-style armor wardrobe. Store gear sets and swap between them from a GUI.
 *
 * <p>Standalone, but scopes each player's wardrobe per RoyalSkyblock profile when that plugin +
 * PlaceholderAPI are present (see {@link ScopeResolver}), falling back to one wardrobe per player.
 * Real gear is moved, never copied, so there is no way to duplicate a stat item.
 */
public final class RoyalWardrobePlugin extends JavaPlugin {

    private WardrobeStorage storage;
    private ScopeResolver scopes;
    private WardrobeMenu menu;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.storage = new WardrobeStorage(this);
        if (!storage.connect()) {
            getLogger().severe("Storage failed to initialise — disabling RoyalWardrobe.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.scopes = new ScopeResolver(getConfig().getString("wardrobe.scope-placeholder", "%royalskyblock_profile_id%"));
        this.menu = new WardrobeMenu(this);

        getServer().getPluginManager().registerEvents(new WardrobeMenuListener(this), this);
        if (getCommand("wardrobe") != null) {
            getCommand("wardrobe").setExecutor(new com.mystipixel.royalwardrobe.command.WardrobeCommand(this));
        }

        getLogger().info("RoyalWardrobe enabled — capacity " + capacity() + " sets, scope: "
                + (scopes.perProfile() ? "per-profile (RoyalSkyblock)" : "per-player") + ".");
    }

    @Override
    public void onDisable() {
        if (storage != null) {
            storage.shutdown();
        }
    }

    /** Number of set slots in the wardrobe menu (the mask's content region) — the wardrobe's capacity. */
    public int capacity() {
        return menu != null ? menu.capacity() : 0;
    }

    public WardrobeStorage storage() {
        return storage;
    }

    public ScopeResolver scopes() {
        return scopes;
    }

    public WardrobeMenu menu() {
        return menu;
    }
}
