package com.mystipixel.royalwardrobe;

import com.mystipixel.royalwardrobe.gui.WardrobeMenu;
import com.mystipixel.royalwardrobe.message.MessageManager;
import com.mystipixel.royalwardrobe.gui.WardrobeMenuListener;
import com.mystipixel.royalwardrobe.gui.WardrobeSessions;
import com.mystipixel.royalwardrobe.scope.ScopeResolver;
import com.mystipixel.royalwardrobe.storage.WardrobeStorage;
import java.util.Locale;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RoyalWardrobe: store armor sets and swap between them from a GUI. Scoped per RoyalSkyblock profile
 * when PlaceholderAPI resolves one (see {@link ScopeResolver}), otherwise one wardrobe per player.
 */
public final class RoyalWardrobePlugin extends JavaPlugin {

    // identifies the plugin, not the server, so it isn't configurable
    private static final int BSTATS_PLUGIN_ID = 32731;

    private WardrobeStorage storage;
    private ScopeResolver scopes;
    private WardrobeMenu menu;
    private WardrobeSessions sessions;
    private com.mystipixel.royalwardrobe.gui.TextInput textInput;
    private MessageManager messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.storage = new WardrobeStorage(this);
        if (!storage.connect()) {
            getLogger().severe("Storage failed to initialise, disabling RoyalWardrobe.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        reloadScopes();
        this.messages = new MessageManager(this);
        this.sessions = new WardrobeSessions(this);
        getServer().getPluginManager().registerEvents(sessions, this);
        this.textInput = new com.mystipixel.royalwardrobe.gui.TextInput(this, messages);
        getServer().getPluginManager().registerEvents(textInput, this);
        this.menu = new WardrobeMenu(this, textInput);

        getServer().getPluginManager().registerEvents(new WardrobeMenuListener(this), this);
        if (getCommand("wardrobe") != null) {
            com.mystipixel.royalwardrobe.command.WardrobeCommand command =
                    new com.mystipixel.royalwardrobe.command.WardrobeCommand(this);
            getCommand("wardrobe").setExecutor(command);
            getCommand("wardrobe").setTabCompleter(command);
        }

        setupMetrics();
        // expansions register after plugins enable, so per-profile mode is decided on each open instead
        getLogger().info("RoyalWardrobe enabled: capacity " + capacity() + " sets, per-profile when "
                + getConfig().getString("wardrobe.scope-placeholder", "") + " is available.");
    }

    @Override
    public void onDisable() {
        if (textInput != null) {
            textInput.shutdown();
        }
        // the click listener is unregistered after this, so a menu left open would let its pieces be taken
        if (sessions != null) {
            sessions.invalidateAll();
            sessions.flushFailedWrites();
        }
        if (storage != null) {
            storage.shutdown();
        }
    }

    /** Number of set slots in the wardrobe menu (the mask's content region). */
    public int capacity() {
        return menu != null ? menu.capacity() : 0;
    }

    public WardrobeStorage storage() {
        return storage;
    }

    /** Re-read wardrobe.scope-placeholder. Open wardrobes must be closed first: their scope may change. */
    public void reloadScopes() {
        this.scopes = new ScopeResolver(getConfig().getString("wardrobe.scope-placeholder", "%royalskyblock_profile_id%"),
                scopes);   // keep per-profile mode latched across a reload with the same placeholder
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

    public WardrobeSessions sessions() {
        return sessions;
    }

    // opt out globally in plugins/bStats/config.yml
    private void setupMetrics() {
        Metrics metrics = new Metrics(this, BSTATS_PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("storage_backend",
                () -> getConfig().getString("storage.type", "SQLITE").toUpperCase(Locale.ROOT)));
        metrics.addCustomChart(new SimplePie("wardrobe_capacity", () -> String.valueOf(capacity())));
        metrics.addCustomChart(new SimplePie("scope",
                () -> scopes.perProfile() ? "per-profile" : "per-player"));
    }

}
