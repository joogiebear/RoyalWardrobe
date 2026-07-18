package com.mystipixel.royalwardrobe;

import com.mystipixel.royalwardrobe.gui.WardrobeMenu;
import com.mystipixel.royalwardrobe.message.MessageManager;
import com.mystipixel.royalwardrobe.gui.WardrobeMenuListener;
import com.mystipixel.royalwardrobe.scope.ScopeResolver;
import com.mystipixel.royalwardrobe.storage.WardrobeStorage;
import java.util.Locale;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RoyalWardrobe — a Hypixel-style armor wardrobe. Store gear sets and swap between them from a GUI.
 *
 * <p>Standalone, but scopes each player's wardrobe per RoyalSkyblock profile when that plugin +
 * PlaceholderAPI are present (see {@link ScopeResolver}), falling back to one wardrobe per player.
 * Real gear is moved, never copied, so there is no way to duplicate a stat item.
 */
public final class RoyalWardrobePlugin extends JavaPlugin {

    /** bStats project id. Identifies the plugin, not the server, so it is fixed rather than configurable. */
    private static final int BSTATS_PLUGIN_ID = 32731;

    private WardrobeStorage storage;
    private ScopeResolver scopes;
    private WardrobeMenu menu;
    private MessageManager messages;

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
        this.messages = new MessageManager(this);
        this.menu = new WardrobeMenu(this);

        getServer().getPluginManager().registerEvents(new WardrobeMenuListener(this), this);
        if (getCommand("wardrobe") != null) {
            getCommand("wardrobe").setExecutor(new com.mystipixel.royalwardrobe.command.WardrobeCommand(this));
        }

        setupMetrics();
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

    public MessageManager messages() {
        return messages;
    }

    public WardrobeMenu menu() {
        return menu;
    }
    /**
     * Anonymous usage reporting via bStats.
     *
     * <p>Server owners who want no reporting disable it globally in plugins/bStats/config.yml, which
     * is the mechanism bStats provides; the id itself is fixed because it names this plugin's project.
     */
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("storage_backend",
                () -> getConfig().getString("storage.type", "SQLITE").toUpperCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("wardrobe_capacity", () -> String.valueOf(capacity())));
        metrics.addCustomChart(new SimplePie("scope",
                () -> getConfig().getString("wardrobe.scope", "global")));
    }

}
