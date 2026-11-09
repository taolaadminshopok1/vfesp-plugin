package com.bakicode.antiespfreecam.esp;

import com.bakicode.antiespfreecam.config.CheatConfig;
import com.bakicode.antiespfreecam.util.BoundingBoxSampler;
import com.comphenix.protocol.wrappers.BlockPosition;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe bridge between the Netty packet threads, the raytrace workers and the Bukkit main
 * thread.
 *
 * <p><b>Threading model.</b></p>
 * <ul>
 *   <li>The <em>main thread</em> owns the Bukkit API. It refreshes the mirrored entity and
 *       observer state, captures the per-observer block snapshot and submits raytrace jobs.</li>
 *   <li>The <em>raytrace workers</em> perform the actual occlusion maths against the immutable
 *       snapshots and publish the verdicts into the {@link VisibilityCache}. They never touch
 *       the Bukkit API.</li>
 *   <li>The <em>Netty threads</em> only ever <em>read</em> the cache and the client state. They
 *       never compute visibility and never call Bukkit.</li>
 * </ul>
 *
 * <p>This split guarantees that no Bukkit API call ever happens off the main thread, while the
 * expensive raytracing still stays off the main thread as required.</p>
 */
public final class VisibilityTracker {

    /**
     * The state of an entity as far as a single client is concerned.
     *
     * <p>Cancelling packets alone does <em>not</em> hide an entity: the client keeps it in
     * memory and keeps rendering it. The entity has to be explicitly destroyed and later
     * re-spawned, and those two operations must be paired correctly or the client ends up with
     * a broken view of the world.</p>
     */
    public enum ClientState {
        /**
         * The client has never been told about this entity, or the server has not sent its
         * spawn packet yet.
         */
        UNKNOWN,
        /**
         * The client holds the entity and renders it normally.
         */
        VISIBLE,
        /**
         * The client was told to destroy the entity through the native API and no longer holds
         * it. Restoring it is done with {@code showEntity}, which emits a complete spawn.
         */
        DESTROYED,
        /**
         * The client still holds the entity, but its updates are being filtered. Restoring it
         * requires an explicit spawn packet, because {@code showEntity} would be a no-op.
         */
        FILTERED
    }

    /**
     * Immutable snapshot of an entity's spatial state.
     *
     * <p>All fields are primitive so that reads from foreign threads are safe: once published
     * through a {@link ConcurrentHashMap} the values never change.</p>
     */
    public static final class EntityState {
        public final int entityId;
        public final UUID uuid;
        public final double minX;
        public final double minY;
        public final double minZ;
        public final double maxX;
        public final double maxY;
        public final double maxZ;
        public final double headY;
        public final UUID worldId;
        public final boolean player;
        /** Timestamp of the last main-thread refresh, used to prune stale entries. */
        public volatile long lastSeen;

        EntityState(int entityId, UUID uuid, BoundingBox box, double headY, UUID worldId, boolean player) {
            this.entityId = entityId;
            this.uuid = uuid;
            this.minX = box.getMinX();
            this.minY = box.getMinY();
            this.minZ = box.getMinZ();
            this.maxX = box.getMaxX();
            this.maxY = box.getMaxY();
            this.maxZ = box.getMaxZ();
            this.headY = headY;
            this.worldId = worldId;
            this.player = player;
            this.lastSeen = System.currentTimeMillis();
        }

        public int getEntityId() {
            return entityId;
        }

        public UUID getUuid() {
            return uuid;
        }

        public boolean isPlayer() {
            return player;
        }

        public UUID getWorldId() {
            return worldId;
        }

        public double centerX() {
            return (minX + maxX) * 0.5D;
        }

        public double centerY() {
            return (minY + maxY) * 0.5D;
        }

        public double centerZ() {
            return (minZ + maxZ) * 0.5D;
        }
    }

    /**
     * Immutable snapshot of an observer's spatial state.
     */
    public static final class ObserverState {
        public final double eyeX;
        public final double eyeY;
        public final double eyeZ;
        public final UUID worldId;

        ObserverState(double eyeX, double eyeY, double eyeZ, UUID worldId) {
            this.eyeX = eyeX;
            this.eyeY = eyeY;
            this.eyeZ = eyeZ;
            this.worldId = worldId;
        }
    }

    private final CheatConfig config;
    private final OcclusionEngine occlusion;
    private final VisibilityCache cache;

    /** Entity id -> state. Written by the main thread, read by the packet threads. */
    private final ConcurrentHashMap<Integer, EntityState> entities = new ConcurrentHashMap<>();

    /** Player UUID -> observer state. Written by the main thread, read by the packet threads. */
    private final ConcurrentHashMap<UUID, ObserverState> observers = new ConcurrentHashMap<>();

    /** Pairs whose raytrace is currently queued, preventing duplicate submissions. */
    private final ConcurrentHashMap<Long, Boolean> pendingTraces = new ConcurrentHashMap<>();

    /** Last evaluation timestamp per pair, used to throttle re-evaluations. */
    private final ConcurrentHashMap<Long, Long> lastTraceTime = new ConcurrentHashMap<>();

    /**
     * The visibility the server currently wants each observer to have for each entity.
     *
     * <p>Written by the main thread and by the raytrace workers, read by the main thread when it
     * applies the visibility transitions.</p>
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, Boolean>> desired =
            new ConcurrentHashMap<>();

    /**
     * The state the client currently believes for each entity.
     *
     * <p>Read by the Netty threads to decide whether a packet may be delivered.</p>
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Integer, ClientState>> clientStates =
            new ConcurrentHashMap<>();

    private final AtomicInteger entityCount = new AtomicInteger();

    /**
     * The entities that were in range during the last evaluation, per observer.
     *
     * <p><b>Main thread only.</b> The visibility transition step needs the live Bukkit entity
     * objects in order to call the native show/hide API, and iterating the world twice per tick
     * would be wasteful.</p>
     */
    private final Map<UUID, java.util.List<Entity>> nearbyCache = new ConcurrentHashMap<>();

    public VisibilityTracker(CheatConfig config) {
        this.config = config;
        this.cache = new VisibilityCache(config.getCacheTtlMillis());
        this.occlusion = new OcclusionEngine(cache, config.getRaytraceThreads());
    }

    /* ------------------------------------------------------------------ */
    /* Main thread updates                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * Refreshes the observer state of a player and captures a fresh block snapshot.
     *
     * <p><b>Must be called from the main server thread.</b></p>
     */
    public void updateObserver(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        observers.put(player.getUniqueId(), new ObserverState(
                location.getX(),
                location.getY() + player.getEyeHeight(),
                location.getZ(),
                world.getUID()));
        occlusion.captureSnapshot(player);
    }

    /**
     * Refreshes the state of an entity.
     *
     * <p><b>Must be called from the main server thread.</b></p>
     */
    public void updateEntity(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return;
        }
        Location location = entity.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        BoundingBox box = entity.getBoundingBox();
        if (box == null) {
            return;
        }

        double headY = location.getY();
        if (entity instanceof LivingEntity living) {
            headY = location.getY() + living.getEyeHeight();
        }

        EntityState state = new EntityState(entity.getEntityId(), entity.getUniqueId(), box,
                headY, world.getUID(), entity instanceof Player);
        entities.put(state.entityId, state);
        entityCount.set(entities.size());
    }

    /**
     * Removes an entity from the mirror.
     */
    public void removeEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        entities.remove(entity.getEntityId());
        cache.invalidateEntity(entity.getEntityId());
        entityCount.set(entities.size());
    }

    /**
     * Removes an observer and all of its cached visibility data.
     */
    public void removeObserver(UUID uuid) {
        if (uuid == null) {
            return;
        }
        observers.remove(uuid);
        occlusion.removeObserver(uuid);
        cache.invalidateObserver(uuid);
        desired.remove(uuid);
        clientStates.remove(uuid);
        nearbyCache.remove(uuid);
        lastTraceTime.clear();
        pendingTraces.clear();
    }

    /**
     * Clears every tracked entity (used on world change / plugin reload).
     */
    public void clearEntities() {
        entities.clear();
        cache.clear();
        desired.clear();
        entityCount.set(0);
    }

    /**
     * Removes entity states that have not been refreshed for a while.
     *
     * <p>Entities that left the tracking radius would otherwise accumulate in the mirror
     * forever.</p>
     */
    public void pruneStaleEntities() {
        long threshold = System.currentTimeMillis() - 5_000L;
        entities.entrySet().removeIf(entry -> entry.getValue().lastSeen < threshold);
        entityCount.set(entities.size());
    }

    /* ------------------------------------------------------------------ */
    /* Visibility evaluation                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Evaluates the visibility of every nearby target for the given observer and queues the
     * resulting raytraces on the worker pool.
     *
     * <p><b>Must be called from the main server thread.</b></p>
     *
     * @param observer the observing player
     */
    public void evaluate(Player observer) {
        if (!config.isEspEnabled() || observer == null || !observer.isOnline()) {
            return;
        }
        UUID observerId = observer.getUniqueId();
        ObserverState observerState = observers.get(observerId);
        if (observerState == null) {
            return;
        }

        Location location = observer.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        ConcurrentHashMap<Integer, Boolean> desiredForObserver =
                desired.computeIfAbsent(observerId, key -> new ConcurrentHashMap<>());

        double maxDistance = config.getAlwaysHiddenDistance();
        double visibleGate = config.getAlwaysVisibleDistance();
        double visibleGateSq = visibleGate * visibleGate;
        double maxDistanceSq = maxDistance * maxDistance;

        java.util.Collection<Entity> nearby =
                world.getNearbyEntities(location, maxDistance, maxDistance, maxDistance);

        for (Entity target : nearby) {
            if (target.getEntityId() == observer.getEntityId()) {
                continue;
            }
            EntityState state = entities.get(target.getEntityId());
            if (state == null) {
                continue;
            }
            if (!state.worldId.equals(observerState.worldId)) {
                continue;
            }
            if (!shouldTrack(state)) {
                continue;
            }

            double distanceSquared = distanceSquared(observerState, state);
            if (distanceSquared <= visibleGateSq) {
                // Always visible.
                desiredForObserver.put(state.entityId, Boolean.TRUE);
                cache.put(observerId, state.entityId, true);
                continue;
            }
            if (distanceSquared >= maxDistanceSq) {
                // Always hidden.
                desiredForObserver.put(state.entityId, Boolean.FALSE);
                cache.put(observerId, state.entityId, false);
                continue;
            }

            // Inside the interesting range. The desired map is the single source of truth: it
            // keeps the last known verdict until a fresh raytrace replaces it, so the entity
            // never flickers while the trace is in flight.
            Boolean previous = desiredForObserver.get(state.entityId);
            if (previous == null) {
                // Never evaluated: assume visible until the first raytrace completes, so that
                // legitimate entities never pop out of existence.
                desiredForObserver.put(state.entityId, Boolean.TRUE);
            }
            submitTrace(observerId, observerState, state);
        }

        // Drop entities that are no longer in range.
        desiredForObserver.keySet().removeIf(entityId -> {
            EntityState state = entities.get(entityId);
            if (state == null) {
                return true;
            }
            double distanceSquared = distanceSquared(observerState, state);
            return distanceSquared >= maxDistanceSq;
        });

        // Remember the live entities for the transition step.
        java.util.List<Entity> live = new java.util.ArrayList<>(nearby.size());
        for (Entity entity : nearby) {
            if (entity.getEntityId() != observer.getEntityId()) {
                live.add(entity);
            }
        }
        nearbyCache.put(observerId, live);
    }

    /**
     * Queues an occlusion raytrace for a single observer/target pair.
     */
    private void submitTrace(UUID observerId, ObserverState observerState, EntityState state) {
        long key = pairKey(observerId, state.entityId);

        // Throttle: only re-evaluate a pair once per configured interval.
        long now = System.nanoTime();
        long intervalNanos = config.getMinTraceIntervalMillis() * 1_000_000L;
        Long last = lastTraceTime.get(key);
        if (last != null && now - last < intervalNanos) {
            return;
        }
        if (pendingTraces.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        lastTraceTime.put(key, now);

        occlusion.submitRefresh(() -> {
            try {
                boolean visible = computeVisibility(observerId, observerState, state);
                cache.put(observerId, state.entityId, visible);
                ConcurrentHashMap<Integer, Boolean> desiredForObserver = desired.get(observerId);
                if (desiredForObserver != null) {
                    desiredForObserver.put(state.entityId, visible);
                }
            } finally {
                pendingTraces.remove(key);
            }
        });
    }

    /**
     * Performs the multi-point raytrace on a worker thread.
     */
    private boolean computeVisibility(UUID observerId, ObserverState observer, EntityState target) {
        BoundingBoxSampler.Sample sample = new BoundingBoxSampler.Sample();
        sample.setObserver(observer.eyeX, observer.eyeY, observer.eyeZ);
        BoundingBoxSampler.sample(target.minX, target.minY, target.minZ,
                target.maxX, target.maxY, target.maxZ, target.headY, sample);
        return occlusion.trace(observer.eyeX, observer.eyeY, observer.eyeZ, sample, observerId);
    }

    /**
     * Computes the squared distance between an observer and a target.
     */
    public double distanceSquared(Player observer, EntityState target) {
        ObserverState observerState = observers.get(observer.getUniqueId());
        if (observerState == null || target == null) {
            return Double.MAX_VALUE;
        }
        return distanceSquared(observerState, target);
    }

    /**
     * Computes the squared distance between two mirrored states.
     */
    private static double distanceSquared(ObserverState observer, EntityState target) {
        if (!observer.worldId.equals(target.worldId)) {
            return Double.MAX_VALUE;
        }
        double dx = observer.eyeX - target.centerX();
        double dy = observer.eyeY - target.centerY();
        double dz = observer.eyeZ - target.centerZ();
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    /**
     * Determines whether a container (chest / shulker) is visible to the observer.
     */
    public boolean isContainerVisible(Player observer, BlockPosition position) {
        if (!config.isHideContainers() || position == null) {
            return true;
        }
        ObserverState observerState = observers.get(observer.getUniqueId());
        if (observerState == null) {
            return true;
        }

        double centerX = position.getX() + 0.5D;
        double centerY = position.getY() + 0.5D;
        double centerZ = position.getZ() + 0.5D;

        double dx = observerState.eyeX - centerX;
        double dy = observerState.eyeY - centerY;
        double dz = observerState.eyeZ - centerZ;
        double distance = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));

        if (distance <= config.getAlwaysVisibleDistance()) {
            return true;
        }
        if (distance >= config.getAlwaysHiddenDistance()) {
            return false;
        }

        BoundingBoxSampler.Sample sample = new BoundingBoxSampler.Sample();
        sample.setObserver(observerState.eyeX, observerState.eyeY, observerState.eyeZ);
        sample.point(BoundingBoxSampler.POINT_CENTER).setX(centerX).setY(centerY).setZ(centerZ);

        return occlusion.trace(observerState.eyeX, observerState.eyeY, observerState.eyeZ,
                sample, observer.getUniqueId());
    }

    /* ------------------------------------------------------------------ */
    /* Client state tracking                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Returns the state the client currently believes for an entity.
     */
    public ClientState getClientState(UUID observer, int entityId) {
        ConcurrentHashMap<Integer, ClientState> states = clientStates.get(observer);
        if (states == null) {
            return ClientState.UNKNOWN;
        }
        ClientState state = states.get(entityId);
        return state == null ? ClientState.UNKNOWN : state;
    }

    /**
     * Records the state the client currently believes for an entity.
     */
    public void setClientState(UUID observer, int entityId, ClientState state) {
        clientStates.computeIfAbsent(observer, key -> new ConcurrentHashMap<>()).put(entityId, state);
    }

    /**
     * @return the desired visibility map of an observer
     */
    public Map<Integer, Boolean> getDesired(UUID observer) {
        ConcurrentHashMap<Integer, Boolean> map = desired.get(observer);
        return map == null ? Map.of() : map;
    }

    /**
     * Returns the authoritative desired visibility of a pair.
     *
     * <p>Unlike {@link #cachedVisibility(UUID, int)} this never expires: the desired visibility
     * is the outcome of the last evaluation and stays valid until the next evaluation replaces
     * it. This is what the packet listener uses, so that a TTL expiry can never make a hidden
     * entity flicker into view.</p>
     *
     * @return {@code true} when the entity should be visible, {@code false} when it should be
     *         hidden, {@code null} when it has not been evaluated yet
     */
    public Boolean desiredVisibility(UUID observer, int entityId) {
        ConcurrentHashMap<Integer, Boolean> map = desired.get(observer);
        if (map == null) {
            return null;
        }
        return map.get(entityId);
    }

    /**
     * @return the live entities that were in range during the last evaluation
     */
    public java.util.List<Entity> getNearbyEntities(UUID observer) {
        java.util.List<Entity> list = nearbyCache.get(observer);
        return list == null ? java.util.List.of() : list;
    }

    /**
     * Drops the per-observer client state, e.g. after every entity was restored.
     */
    public void forgetObserverState(UUID observer) {
        if (observer == null) {
            return;
        }
        ConcurrentHashMap<Integer, ClientState> states = clientStates.get(observer);
        if (states != null) {
            states.clear();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Packet thread queries                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Looks up the mirrored state of an entity.
     */
    public EntityState findState(int entityId) {
        return entities.get(entityId);
    }

    /**
     * @return {@code true} when the entity type should participate in ESP filtering
     */
    public boolean shouldTrack(EntityState state) {
        if (state == null) {
            return false;
        }
        if (state.isPlayer()) {
            return config.isHidePlayers();
        }
        return config.isHideMobs();
    }

    /**
     * Returns the cached visibility verdict for a pair.
     *
     * <p>Called from the Netty threads. The verdict is <em>only</em> read from the cache: the
     * actual computation happens on the worker pool, so this method never blocks.</p>
     */
    public Boolean cachedVisibility(UUID observerId, int entityId) {
        return cache.get(observerId, entityId);
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Builds a compact composite key for an observer/target pair.
     */
    private static long pairKey(UUID observer, int entityId) {
        return (observer.getMostSignificantBits() * 31L) + entityId;
    }

    /* ------------------------------------------------------------------ */
    /* Accessors                                                           */
    /* ------------------------------------------------------------------ */

    public OcclusionEngine getOcclusion() {
        return occlusion;
    }

    public VisibilityCache getCache() {
        return cache;
    }

    public CheatConfig getConfig() {
        return config;
    }

    public int getTrackedEntityCount() {
        return entityCount.get();
    }

    /**
     * Shuts down the worker pool.
     */
    public void shutdown() {
        occlusion.shutdown();
        entities.clear();
        observers.clear();
        desired.clear();
        clientStates.clear();
        lastTraceTime.clear();
        pendingTraces.clear();
    }
}
