package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import com.mystipixel.royalwardrobe.wardrobe.WardrobeData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The one live copy of each online player's wardrobe.
 *
 * <p>Every way in — the menu, {@code /wardrobe equip}, {@code /wardrobe list}, a rename returning from
 * its sign — works on the same {@link WardrobeData}. When each of them loaded a private copy instead,
 * a copy read before another path's change was queued would still show a set as stored after it had
 * been equipped, and its pieces could be taken a second time. With one copy there is nothing to go
 * stale: the database is read once per player and scope, and only written after that.
 *
 * <p>Main-thread only. Loads still go through the storage writer thread, so they queue behind any
 * write from an earlier session.
 */
public final class WardrobeSessions implements Listener {

    /** A player's live wardrobe for one scope. */
    public record Session(UUID owner, String scope, WardrobeData data) {
    }

    private static final class PendingLoad {
        private final String scope;
        private final int capacity;
        private final List<Consumer<Session>> ready = new ArrayList<>();
        private final List<Runnable> failed = new ArrayList<>();

        private PendingLoad(String scope, int capacity) {
            this.scope = scope;
            this.capacity = capacity;
        }
    }

    private final RoyalWardrobePlugin plugin;
    private final Map<UUID, Session> live = new HashMap<>();
    private final Map<UUID, PendingLoad> loading = new HashMap<>();

    public WardrobeSessions(RoyalWardrobePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Run {@code ready} with the player's live wardrobe for {@code scope}, loading it first if needed.
     * {@code failed} runs instead when the wardrobe can't be read. Both run on the main thread, and
     * neither runs if the player has left by then.
     */
    public void with(Player player, String scope, int capacity, Consumer<Session> ready, Runnable failed) {
        UUID id = player.getUniqueId();
        Session current = live.get(id);
        if (current != null) {
            if (current.scope().equals(scope) && current.data().capacity() >= capacity) {
                ready.accept(current);
                return;
            }
            // Profile switched, or the menu grew: this copy is finished with. Shut any menu still
            // showing it, so it can't keep being edited alongside the copy that replaces it.
            closeMenuFor(player, current.data());
            live.remove(id);
        }

        PendingLoad pending = loading.get(id);
        if (pending == null || !pending.scope.equals(scope) || pending.capacity != capacity) {
            pending = new PendingLoad(scope, capacity);
            loading.put(id, pending);
            startLoad(id, pending);
        }
        pending.ready.add(ready);
        pending.failed.add(failed);
    }

    private void startLoad(UUID id, PendingLoad pending) {
        plugin.storage().submit(() -> {
            WardrobeData data = plugin.storage().load(id, pending.scope, pending.capacity);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (loading.get(id) != pending) {
                    return;                      // superseded by a quit, a reload or another scope
                }
                loading.remove(id);
                if (plugin.getServer().getPlayer(id) == null) {
                    return;
                }
                if (data == null) {
                    pending.failed.forEach(Runnable::run);
                    return;
                }
                Session session = new Session(id, pending.scope, data);
                live.put(id, session);
                pending.ready.forEach(callback -> callback.accept(session));
            });
        });
    }

    /** Whether {@code data} is still the live copy — a callback that outlived its session must not write. */
    public boolean isLive(UUID owner, WardrobeData data) {
        Session session = live.get(owner);
        return session != null && session.data() == data;
    }

    /** Close every open wardrobe and forget every live copy, e.g. before the menu's shape changes. */
    public void invalidateAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof WardrobeHolder) {
                player.closeInventory();
            }
        }
        live.clear();
        loading.clear();
    }

    private static void closeMenuFor(Player player, WardrobeData data) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof WardrobeHolder holder
                && holder.data() == data) {
            player.closeInventory();
        }
    }

    /** Writes are already queued per action; all a quit has to do is drop the memory. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        live.remove(id);
        loading.remove(id);
    }
}
