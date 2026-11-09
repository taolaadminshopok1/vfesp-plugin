package com.bakicode.antiespfreecam.esp;

import com.bakicode.antiespfreecam.config.CheatConfig;
import com.bakicode.antiespfreecam.util.BoundingBoxSampler;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 *   <li>The <em>Netty threads</em> only ever <em>read</em> the cache. They never compute
 *       visibility and never call Bukkit.</li>
 * </ul>
 *
 * <p>This split guarantees that no Bukkit API call ever happens off the main thread, while the
 * expensive raytracing still stays off the main thread as required.</p>
 */
public final class VisibilityTracker {

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

        /**
         * @return the horizontal centre of the bounding box
         */
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

    /** Entities the client currently believes are hidden; used to suppress redundant destroys. */
    private final ConcurrentHashMap<UUID, java.util.Set<Integer>> hiddenEntities = new ConcurrentHashMap<>();

    /** Requests for entities that need to be re-spawned to a client. */
    private final ConcurrentLinkedQueue<RehydrationRequest> rehydrationQueue = new ConcurrentLinkedQueue<>();

    private final AtomicInteger entityCount = new AtomicInteger();

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
        hiddenEntities.remove(uuid);
        lastTraceTime.clear();
        pendingTraces.clear();
    }

    /**
     * Clears every tracked entity (used on world change / plugin reload).
     */
    public void clearEntities() {
        entities.clear();
        cache.clear();
        entityCount.set(0);
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

        double maxDistance = config.getAlwaysHiddenDistance();
        java.util.Collection<Entity> nearby = world.getNearbyEntities(location, maxDistance, maxDistance, maxDistance);

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
            if (distanceSquared <= config.getAlwaysVisibleDistance() * config.getAlwaysVisibleDistance()) {
                // Always visible: publish the verdict so the packet layer does not have to
                // special case it.
                cache.put(observerId, state.entityId, true);
                continue;
            }
            if (distanceSquared >= maxDistance * maxDistance) {
                // Always hidden.
                cache.put(observerId, state.entityId, false);
                continue;
            }

            submitTrace(observerId, observerState, state);
        }
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
     *
     * <p>Containers are evaluated on the calling thread because the block position is already
     * known and a single ray trace is cheap.</p>
     */
    public boolean isContainerVisible(Player observer, com.comphenix.protocol.wrappers.BlockPosition position) {
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
     *
     * @param observerId the observer's UUID
     * @param entityId   the target's network id
     * @return {@code true} when the entity should be visible, {@code false} when it should be
     *         hidden, and {@code null} when no verdict is cached yet
     */
    public Boolean cachedVisibility(UUID observerId, int entityId) {
        return cache.get(observerId, entityId);
    }

    /**
     * Publishes an explicit verdict for a pair.
     */
    public void publishVisibility(UUID observerId, int entityId, boolean visible) {
        cache.put(observerId, entityId, visible);
    }

    /* ------------------------------------------------------------------ */
    /* Hidden entity bookkeeping                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Records that the client has been told to forget an entity.
     */
    public void markHidden(UUID observer, int entityId) {
        hiddenEntities.computeIfAbsent(observer, key -> ConcurrentHashMap.newKeySet()).add(entityId);
    }

    /**
     * @return {@code true} when the client currently believes the entity is hidden
     */
    public boolean isHidden(UUID observer, int entityId) {
        java.util.Set<Integer> ids = hiddenEntities.get(observer);
        return ids != null && ids.contains(entityId);
    }

    /**
     * Clears the hidden marker for an entity.
     */
    public void clearHidden(UUID observer, int entityId) {
        java.util.Set<Integer> ids = hiddenEntities.get(observer);
        if (ids != null) {
            ids.remove(entityId);
        }
    }

    /**
     * @return the number of entities currently hidden from the observer
     */
    public int hiddenCount(UUID observer) {
        java.util.Set<Integer> ids = hiddenEntities.get(observer);
        return ids == null ? 0 : ids.size();
    }

    /**
     * Drops every hidden-entity marker belonging to an observer.
     */
    public void clearHiddenEntities(UUID observer) {
        if (observer == null) {
            return;
        }
        hiddenEntities.remove(observer);
    }

    /* ------------------------------------------------------------------ */
    /* Rehydration queue                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Queues a request to re-spawn an entity to a client.
     */
    public void requestRehydration(UUID observer, int entityId) {
        rehydrationQueue.add(new RehydrationRequest(observer, entityId));
    }

    /**
     * Drains the rehydration queue.
     */
    public java.util.List<RehydrationRequest> drainRehydrationRequests() {
        if (rehydrationQueue.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<RehydrationRequest> requests = new java.util.ArrayList<>(rehydrationQueue.size());
        RehydrationRequest request;
        while ((request = rehydrationQueue.poll()) != null) {
            requests.add(request);
        }
        return requests;
    }

    /**
     * A pending re-spawn request.
     */
    public static final class RehydrationRequest {
        public final UUID observer;
        public final int entityId;

        RehydrationRequest(UUID observer, int entityId) {
            this.observer = observer;
            this.entityId = entityId;
        }
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
        hiddenEntities.clear();
        rehydrationQueue.clear();
        lastTraceTime.clear();
        pendingTraces.clear();
    }
}
