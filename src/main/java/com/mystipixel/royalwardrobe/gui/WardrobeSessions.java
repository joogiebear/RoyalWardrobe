package com.mystipixel.royalwardrobe.gui;

import com.mystipixel.royalwardrobe.RoyalWardrobePlugin;
import com.mystipixel.royalwardrobe.storage.FailedWrites;
import com.mystipixel.royalwardrobe.storage.WardrobeStorage;
import com.mystipixel.royalwardrobe.wardrobe.WardrobeData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The one live copy of each online player's wardrobe. Every path (menu, commands, rename) must use it:
 * a second copy goes stale and lets a set's pieces be taken twice.
 *
 * <p>Main-thread only. Loads go through the storage writer thread, so they queue behind earlier writes.
 */
public final class WardrobeSessions implements Listener {

    /** Why a wardrobe could not be opened. BUSY: another server sharing the database is still saving it. */
    public enum LoadFailure { UNREADABLE, BUSY }

    /** A player's live wardrobe for one scope. */
    public record Session(UUID owner, String scope, WardrobeData data) {
    }

    private static final class PendingLoad {
        private final String scope;
        private final int capacity;
        private final List<Consumer<Session>> ready = new ArrayList<>();
        private final List<Consumer<LoadFailure>> failed = new ArrayList<>();

        private PendingLoad(String scope, int capacity) {
            this.scope = scope;
            this.capacity = capacity;
        }
    }

    private static final long RETRY_FIRST_TICKS = 100L;
    private static final long RETRY_MAX_TICKS = 1200L;

    private final RoyalWardrobePlugin plugin;
    private final Map<UUID, Session> live = new HashMap<>();
    private final Map<UUID, PendingLoad> loading = new HashMap<>();
    private final FailedWrites failedWrites = new FailedWrites();
    private final Set<FailedWrites.Key> retrying = new HashSet<>();

    public WardrobeSessions(RoyalWardrobePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Run {@code ready} with the player's live wardrobe for {@code scope}, loading it first if needed.
     * {@code failed} runs instead when the wardrobe can't be read. Both run on the main thread, and
     * neither runs if the player has left by then.
     */
    public void with(Player player, String scope, int capacity, Consumer<Session> ready, Consumer<LoadFailure> failed) {
        UUID id = player.getUniqueId();
        Session current = live.get(id);
        if (current != null) {
            if (current.scope().equals(scope) && current.data().capacity() >= capacity) {
                ready.accept(current);
                return;
            }
            // profile switched or the menu grew: close any menu still editing the old copy
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

    // waits for the server the player came from (off both the main and the writer thread), then loads
    // on the writer thread, behind this server's own earlier writes
    private void startLoad(UUID id, PendingLoad pending) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            WardrobeStorage.Claim claim = plugin.storage().awaitHandoff(id);
            if (claim != WardrobeStorage.Claim.READY) {
                finishLoad(id, pending, null, claim == WardrobeStorage.Claim.BUSY ? LoadFailure.BUSY : LoadFailure.UNREADABLE);
                return;
            }
            plugin.storage().submit(() -> {
                WardrobeData data = plugin.storage().load(id, pending.scope, pending.capacity);
                finishLoad(id, pending, data, LoadFailure.UNREADABLE);
            });
        });
    }

    private void finishLoad(UUID id, PendingLoad pending, WardrobeData data, LoadFailure failure) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (loading.get(id) != pending) {
                    return;                      // superseded by a quit, a reload or another scope
                }
                loading.remove(id);
                if (plugin.getServer().getPlayer(id) == null) {
                    return;
                }
                if (data == null) {
                    pending.failed.forEach(callback -> callback.accept(failure));
                    return;
                }
                Session session = new Session(id, pending.scope, data);
                live.put(id, session);
                pending.ready.forEach(callback -> callback.accept(session));
        });
    }

    /** Whether {@code data} is still the live copy. A callback that outlived its session must not write. */
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

    /** Whether a failed write for this wardrobe is still being retried; it must not be loaded or changed until then. */
    public boolean hasUnsavedWrites(UUID owner, String scope) {
        return failedWrites.isPending(new FailedWrites.Key(owner, scope));
    }

    /**
     * Record slots whose write failed and keep rewriting them from {@code data} until one commits.
     * The player's gear already matches {@code data}, so the table is rolled forward, not the player back.
     */
    public void writeFailed(UUID owner, String scope, WardrobeData data, int... indexes) {
        FailedWrites.Key key = failedWrites.failed(owner, scope, data, indexes);
        Player player = plugin.getServer().getPlayer(owner);
        if (player != null) {
            closeMenuFor(player, data);
        }
        scheduleRetry(key);
    }

    private void scheduleRetry(FailedWrites.Key key) {
        if (!retrying.add(key)) {
            return;                              // the retry in flight reschedules itself if still needed
        }
        int doublings = Math.min(failedWrites.failures(key) - 1, 4);
        long delay = Math.min(RETRY_MAX_TICKS, RETRY_FIRST_TICKS << doublings);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> retry(key), delay);
    }

    private void retry(FailedWrites.Key key) {
        FailedWrites.Retry retry = failedWrites.retry(key);
        plugin.storage().submit(() -> {
            WardrobeStorage.SaveResult result = plugin.storage().saveAll(key.owner(), key.scope(), retry.writes());
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                retrying.remove(key);
                if (result == WardrobeStorage.SaveResult.CONFLICT) {
                    // another server owns newer data now; storage logged the refused slots
                    failedWrites.discard(key);
                    Session session = live.get(key.owner());
                    if (session != null && session.scope().equals(key.scope())) {
                        changedElsewhere(key.owner(), session.data());
                    }
                    releaseIfGone(key.owner());
                    return;
                }
                if (result == WardrobeStorage.SaveResult.SAVED) {
                    failedWrites.saved(retry);
                } else {
                    failedWrites.retryFailed(retry);
                }
                if (failedWrites.isPending(key)) {
                    scheduleRetry(key);
                    return;
                }
                plugin.getLogger().info("Saved wardrobe " + key.owner() + "/" + key.scope() + " after an earlier failed write.");
                Player player = plugin.getServer().getPlayer(key.owner());
                if (player != null) {
                    plugin.menu().notifySaved(player);
                } else {
                    releaseIfGone(key.owner());
                }
            });
        });
    }

    /**
     * A save was refused because another server changed this wardrobe after it was loaded here. The
     * live copy is stale: close the menu, forget it and tell the player, so the next open reloads.
     */
    public void changedElsewhere(UUID owner, WardrobeData data) {
        Session session = live.get(owner);
        if (session != null && session.data() == data) {
            live.remove(owner);
        }
        Player player = plugin.getServer().getPlayer(owner);
        if (player != null) {
            closeMenuFor(player, data);
            plugin.menu().notifyChangedElsewhere(player);
        }
    }

    // once nothing for this player is left to write, another server sharing the database may load them
    private void releaseIfGone(UUID owner) {
        if (plugin.getServer().getPlayer(owner) == null && !failedWrites.hasOwner(owner)) {
            plugin.storage().submit(() -> plugin.storage().release(owner));
        }
    }

    /**
     * Queue one last write of every wardrobe still behind its player, then release every player whose
     * wardrobe is fully saved. Call on disable, before storage drains.
     */
    public void flushFailedWrites() {
        for (FailedWrites.Key key : failedWrites.keys()) {
            FailedWrites.Retry retry = failedWrites.retry(key);
            plugin.storage().submit(() -> {
                WardrobeStorage.SaveResult result = plugin.storage().saveAll(key.owner(), key.scope(), retry.writes());
                if (result == WardrobeStorage.SaveResult.SAVED) {
                    plugin.storage().release(key.owner());
                } else if (result == WardrobeStorage.SaveResult.FAILED) {
                    plugin.getLogger().log(Level.SEVERE, "Wardrobe " + key.owner() + "/" + key.scope()
                            + " was never saved: the table is behind the player's gear for slots "
                            + retry.writes().stream().map(w -> String.valueOf(w.idx() + 1)).toList() + ".");
                }
            });
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!failedWrites.hasOwner(id)) {
                plugin.storage().submit(() -> plugin.storage().release(id));
            }
        }
    }

    /**
     * Writes are already queued per action, so a quit drops the memory and, behind those writes,
     * lets another server load the wardrobe. A write still being retried holds that back until it lands.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        live.remove(id);
        loading.remove(id);
        if (!failedWrites.hasOwner(id)) {
            plugin.storage().submit(() -> plugin.storage().release(id));
        }
    }
}
