package com.bakicode.antiespfreecam.esp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe, sliding-window visibility cache.
 *
 * <p>Keyed by {@code (observer UUID, target entity id)}. Entries expire after a configurable
 * time-to-live; the cache is swept lazily on access and periodically by the owning engine.</p>
 *
 * <p>All operations are lock-free with respect to the reading threads: the backing map is a
 * {@link ConcurrentHashMap} and every entry carries an immutable expiry stamp, so readers never
 * block writers and writers never block readers.</p>
 */
public final class VisibilityCache {

    /** Immutable cache entry carrying the visibility verdict and its expiry timestamp. */
    private static final class Entry {
        private final boolean visible;
        private final long expiresAtNanos;

        Entry(boolean visible, long expiresAtNanos) {
            this.visible = visible;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public VisibilityCache(long ttlMillis) {
        this.ttlNanos = Math.max(1L, ttlMillis) * 1_000_000L;
    }

    /**
     * Looks up a cached verdict.
     *
     * @param observer the observing player's UUID
     * @param entityId the target entity's network id
     * @return the cached verdict, or {@code null} when absent or expired
     */
    public Boolean get(UUID observer, int entityId) {
        Key key = new Key(observer, entityId);
        Entry entry = entries.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        if (entry.expiresAtNanos - System.nanoTime() <= 0L) {
            // Expired: remove lazily. Racing removals are harmless.
            entries.remove(key, entry);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.visible;
    }

    /**
     * Stores a verdict with the configured TTL.
     */
    public void put(UUID observer, int entityId, boolean visible) {
        entries.put(new Key(observer, entityId), new Entry(visible, System.nanoTime() + ttlNanos));
    }

    /**
     * Stores a verdict, optionally with a custom lifetime.
     *
     * @param ttlMillis lifetime in milliseconds; values &lt;= 0 are ignored
     */
    public void put(UUID observer, int entityId, boolean visible, long ttlMillis) {
        if (ttlMillis <= 0L) {
            return;
        }
        entries.put(new Key(observer, entityId),
                new Entry(visible, System.nanoTime() + (ttlMillis * 1_000_000L)));
    }

    /**
     * Invalidates a single entry, e.g. after the target moved significantly.
     */
    public void invalidate(UUID observer, int entityId) {
        entries.remove(new Key(observer, entityId));
    }

    /**
     * Invalidates every entry belonging to an observer.
     *
     * @return the number of removed entries
     */
    public int invalidateObserver(UUID observer) {
        int removed = 0;
        for (Key key : entries.keySet()) {
            if (key.observer.equals(observer) && entries.remove(key) != null) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes every entry of the given observers in a single pass.
     */
    public int invalidateObservers(java.util.Collection<UUID> observers) {
        if (observers == null || observers.isEmpty()) {
            return 0;
        }
        java.util.Set<UUID> ids = java.util.Set.copyOf(observers);
        int removed = 0;
        for (Key key : entries.keySet()) {
            if (ids.contains(key.observer) && entries.remove(key) != null) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Removes every entry whose target entity id matches.
     */
    public int invalidateEntity(int entityId) {
        int removed = 0;
        for (Key key : entries.keySet()) {
            if (key.entityId == entityId && entries.remove(key) != null) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Sweeps expired entries.
     *
     * @return the number of removed entries
     */
    public int purgeExpired() {
        long now = System.nanoTime();
        int removed = 0;
        for (Map.Entry<Key, Entry> mapping : entries.entrySet()) {
            Entry entry = mapping.getValue();
            if (entry.expiresAtNanos - now <= 0L && entries.remove(mapping.getKey(), entry)) {
                removed++;
            }
        }
        if (removed > 0) {
            evictions.addAndGet(removed);
        }
        return removed;
    }

    /**
     * Removes every entry.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Current number of cached entries.
     */
    public int size() {
        return entries.size();
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    public long evictionCount() {
        return evictions.get();
    }

    /**
     * Composite cache key. Uses identity-free value equality so lookups work across threads.
     */
    private static final class Key {
        private final UUID observer;
        private final int entityId;
        private final int hash;

        Key(UUID observer, int entityId) {
            this.observer = observer;
            this.entityId = entityId;
            this.hash = (31 * observer.hashCode()) + entityId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return entityId == key.entityId && observer.equals(key.observer);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
