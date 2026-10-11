package com.mystipixel.royalwardrobe.storage;

import com.mystipixel.royalwardrobe.wardrobe.WardrobeData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Wardrobe slots whose last write failed. The live copy and the player's armor still agree, so the
 * table is brought forward to the live copy by rewriting those slots, never the other way round.
 * Main-thread only.
 */
public final class FailedWrites {

    public record Key(UUID owner, String scope) {
    }

    /** The writes for one retry, and the failure count when it was taken. */
    public record Retry(Key key, List<WardrobeStorage.SlotWrite> writes, int failures) {
    }

    private static final class Entry {
        private final WardrobeData data;
        private final Set<Integer> slots = new TreeSet<>();
        private int failures;

        private Entry(WardrobeData data) {
            this.data = data;
        }
    }

    private final Map<Key, Entry> entries = new HashMap<>();

    public Key failed(UUID owner, String scope, WardrobeData data, int... indexes) {
        Key key = new Key(owner, scope);
        Entry entry = entries.computeIfAbsent(key, k -> new Entry(data));
        for (int index : indexes) {
            entry.slots.add(index);
        }
        entry.failures++;
        return key;
    }

    public void retryFailed(Retry retry) {
        Entry entry = entries.get(retry.key());
        if (entry != null) {
            entry.failures++;
        }
    }

    public void saved(Retry retry) {
        Entry entry = entries.get(retry.key());
        // a failure reported after this retry was taken may cover a newer state, so it stays pending
        if (entry != null && entry.failures == retry.failures()) {
            entries.remove(retry.key());
        }
    }

    public boolean isPending(Key key) {
        return entries.containsKey(key);
    }

    public Set<Key> keys() {
        return Set.copyOf(entries.keySet());
    }

    public int failures(Key key) {
        return entries.get(key).failures;
    }

    // snapshots item stacks, so call it on the main thread
    public Retry retry(Key key) {
        Entry entry = entries.get(key);
        List<WardrobeStorage.SlotWrite> writes = new ArrayList<>(entry.slots.size());
        for (int index : entry.slots) {
            writes.add(WardrobeStorage.SlotWrite.of(entry.data, index));
        }
        return new Retry(key, writes, entry.failures);
    }
}
