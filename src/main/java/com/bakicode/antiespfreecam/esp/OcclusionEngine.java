package com.bakicode.antiespfreecam.esp;

import com.bakicode.antiespfreecam.util.BoundingBoxSampler;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-speed multi-point occlusion raytracer.
 *
 * <p><b>Threading model.</b> Raytracing reads the world, which is owned by the main server
 * thread. To honour the "never raytrace on the main thread" requirement without introducing
 * thread-unsafe world access, the engine works on an immutable snapshot:</p>
 *
 * <ul>
 *   <li>The main thread captures a compact snapshot of the block data around each observer into
 *       a flat {@code byte[]} palette structure, using Bukkit's {@link ChunkSnapshot}.</li>
 *   <li>The packet threads and worker threads trace against that immutable snapshot, which is
 *       safe to read concurrently without any locking.</li>
 * </ul>
 *
 * <p>{@link ChunkSnapshot} is the key to making this cheap: it is a true immutable copy of a
 * chunk's block data that can be read from any thread. Reading a block from it is a plain array
 * lookup, whereas {@code World#getBlockAt} performs a full chunk lookup plus a world mutation
 * check on every single call. For a 49x49x33 capture volume that is the difference between
 * ~79,000 expensive calls and 16 cheap ones.</p>
 */
public final class OcclusionEngine {

    /** Block classification used by the raytracer. */
    public static final byte TRANSPARENT = 0;
    public static final byte OPAQUE = 1;
    /** Semi-transparent: blocks sight, but is not a "solid wall" (glass, leaves, fences, bars). */
    public static final byte SEMI_TRANSPARENT = 2;

    /** Horizontal radius (in blocks) of the captured snapshot around an observer. */
    private static final int SNAPSHOT_RADIUS = 24;
    /** Vertical radius (in blocks) of the captured snapshot around an observer. */
    private static final int SNAPSHOT_HEIGHT = 16;

    /** Number of blocks along one axis of the snapshot (must be odd). */
    private static final int SNAPSHOT_WIDTH = (SNAPSHOT_RADIUS * 2) + 1;
    private static final int SNAPSHOT_LENGTH = (SNAPSHOT_RADIUS * 2) + 1;
    private static final int SNAPSHOT_VERTICAL = (SNAPSHOT_HEIGHT * 2) + 1;

    /**
     * Cached classification of every material the engine has already seen.
     *
     * <p>Classification involves a handful of string comparisons, so caching it per material
     * turns the per-block cost into a single hash lookup.</p>
     */
    private static final ConcurrentHashMap<Material, Byte> CLASSIFICATION_CACHE = new ConcurrentHashMap<>();

    private final VisibilityCache cache;
    private final ExecutorService workers;
    private final AtomicInteger workerCounter = new AtomicInteger();

    /** Per-observer snapshots, written by the main thread and read by the packet threads. */
    private final ConcurrentHashMap<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * Chunk snapshots shared between all observers during a single capture round.
     *
     * <p>Without this, two players standing next to each other would each capture the same
     * chunks, doubling the cost of the most expensive part of the plugin.</p>
     */
    private final ConcurrentHashMap<ChunkKey, ChunkSnapshot> chunkCache = new ConcurrentHashMap<>();

    public OcclusionEngine(VisibilityCache cache, int threadCount) {
        this.cache = cache;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "AntiESP-Raytrace-" + workerCounter.incrementAndGet());
            thread.setDaemon(true);
            // Workers must never outrank the main thread; they are pure computation.
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
        this.workers = Executors.newFixedThreadPool(Math.max(1, threadCount), factory);
    }

    /* ------------------------------------------------------------------ */
    /* Snapshot management (main thread)                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Starts a new capture round and drops the shared chunk snapshot cache.
     *
     * <p><b>Must be called from the main server thread, once per tick.</b></p>
     */
    public void beginCaptureRound() {
        chunkCache.clear();
    }

    /**
     * Captures a fresh block snapshot around the given observer.
     *
     * <p><b>Must be called from the main server thread.</b></p>
     */
    public void captureSnapshot(Player observer) {
        if (observer == null || !observer.isOnline()) {
            return;
        }
        Location loc = observer.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        snapshots.put(observer.getUniqueId(), Snapshot.capture(observer.getUniqueId(), world, loc, chunkCache));
    }

    /**
     * Drops the snapshot belonging to an observer.
     */
    public void removeObserver(UUID observer) {
        snapshots.remove(observer);
    }

    /**
     * Drops all snapshots (used on plugin disable / world change).
     */
    public void clearSnapshots() {
        snapshots.clear();
        chunkCache.clear();
    }

    /* ------------------------------------------------------------------ */
    /* Raytracing                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Traces the sample rays of the supplied buffer and reports whether the target is visible.
     *
     * <p>Thread safe: reads only the immutable snapshot belonging to the observer.</p>
     *
     * @param observerId the observing player's UUID
     * @param eyeX       observer eye X
     * @param eyeY       observer eye Y
     * @param eyeZ       observer eye Z
     * @param sample     the populated sample buffer
     * @return {@code true} when at least one ray reaches the target unobstructed
     */
    public boolean trace(double eyeX, double eyeY, double eyeZ,
                         BoundingBoxSampler.Sample sample, UUID observerId) {
        Snapshot snapshot = snapshots.get(observerId);
        if (snapshot == null) {
            // No snapshot available: fail open (visible) rather than hiding legitimate entities.
            return true;
        }

        for (int i = 0; i < BoundingBoxSampler.SAMPLE_COUNT; i++) {
            org.bukkit.util.Vector point = sample.point(i);
            if (point == null) {
                continue;
            }
            if (isRayClear(snapshot, eyeX, eyeY, eyeZ, point.getX(), point.getY(), point.getZ())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Traces a single ray through the snapshot using an Amanatides-Woo voxel traversal.
     *
     * @return {@code true} when the ray reaches the destination without crossing an opaque block
     */
    private boolean isRayClear(Snapshot snapshot,
                               double fromX, double fromY, double fromZ,
                               double toX, double toY, double toZ) {
        double dirX = toX - fromX;
        double dirY = toY - fromY;
        double dirZ = toZ - fromZ;

        double lengthSquared = (dirX * dirX) + (dirY * dirY) + (dirZ * dirZ);
        if (lengthSquared < 1.0E-8D) {
            return true;
        }

        int blockX = floor(fromX);
        int blockY = floor(fromY);
        int blockZ = floor(fromZ);

        int stepX = dirX > 0.0D ? 1 : -1;
        int stepY = dirY > 0.0D ? 1 : -1;
        int stepZ = dirZ > 0.0D ? 1 : -1;

        double deltaX = dirX == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / dirX);
        double deltaY = dirY == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / dirY);
        double deltaZ = dirZ == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / dirZ);

        double maxX = boundaryDistance(fromX, blockX, stepX, deltaX);
        double maxY = boundaryDistance(fromY, blockY, stepY, deltaY);
        double maxZ = boundaryDistance(fromZ, blockZ, stepZ, deltaZ);

        // Bound the number of steps to avoid pathological loops on degenerate input.
        int maxSteps = snapshot.maxSteps();
        double travelled = 0.0D;
        double rayLength = Math.sqrt(lengthSquared);

        for (int step = 0; step < maxSteps; step++) {
            // The destination block itself must not be treated as an obstruction.
            if (blockX == floor(toX) && blockY == floor(toY) && blockZ == floor(toZ)) {
                return true;
            }

            byte type = snapshot.get(blockX, blockY, blockZ);
            if (type == OPAQUE) {
                return false;
            }

            if (maxX < maxY && maxX < maxZ) {
                blockX += stepX;
                travelled = maxX;
                maxX += deltaX;
            } else if (maxY < maxZ) {
                blockY += stepY;
                travelled = maxY;
                maxY += deltaY;
            } else {
                blockZ += stepZ;
                travelled = maxZ;
                maxZ += deltaZ;
            }

            if (travelled > rayLength) {
                return true;
            }
            if (!snapshot.contains(blockX, blockY, blockZ)) {
                // Outside the captured volume: we cannot prove an obstruction, so allow sight.
                return true;
            }
        }
        return true;
    }

    /**
     * Computes the distance along the ray to the first voxel boundary.
     */
    private static double boundaryDistance(double origin, int block, int step, double delta) {
        if (step > 0) {
            return ((block + 1) - origin) * delta;
        }
        if (step < 0) {
            return (origin - block) * delta;
        }
        return Double.MAX_VALUE;
    }

    /**
     * Fast floor for positive and negative values.
     */
    private static int floor(double value) {
        int casted = (int) value;
        return (value < casted) ? casted - 1 : casted;
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Shuts the worker pool down.
     */
    public void shutdown() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(2L, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        snapshots.clear();
        chunkCache.clear();
    }

    /**
     * Submits a task to the worker pool.
     */
    public void submitRefresh(Runnable task) {
        if (task == null || workers.isShutdown()) {
            return;
        }
        try {
            workers.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Pool is shutting down; dropping the task is acceptable.
        }
    }

    /**
     * Exposes the cache for the packet layer.
     */
    public VisibilityCache getCache() {
        return cache;
    }

    /* ------------------------------------------------------------------ */
    /* Snapshot implementation                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Immutable block snapshot around one observer.
     */
    public static final class Snapshot {

        private final UUID owner;
        private final UUID worldId;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final byte[] blocks;
        private final long capturedAt;

        Snapshot(UUID owner, UUID worldId, int originX, int originY, int originZ,
                 byte[] blocks, long capturedAt) {
            this.owner = owner;
            this.worldId = worldId;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.blocks = blocks;
            this.capturedAt = capturedAt;
        }

        /**
         * Captures a snapshot of the world around the given location.
         *
         * <p><b>Must be called from the main server thread.</b></p>
         */
        static Snapshot capture(UUID owner, World world, Location loc,
                                Map<ChunkKey, ChunkSnapshot> chunkCache) {
            int baseX = loc.getBlockX();
            int baseY = loc.getBlockY();
            int baseZ = loc.getBlockZ();

            int originX = baseX - SNAPSHOT_RADIUS;
            int originY = baseY - SNAPSHOT_HEIGHT;
            int originZ = baseZ - SNAPSHOT_RADIUS;

            byte[] blocks = new byte[SNAPSHOT_WIDTH * SNAPSHOT_LENGTH * SNAPSHOT_VERTICAL];

            int index = 0;
            for (int y = 0; y < SNAPSHOT_VERTICAL; y++) {
                for (int z = 0; z < SNAPSHOT_LENGTH; z++) {
                    int worldZ = originZ + z;
                    int chunkZ = worldZ >> 4;
                    int localZ = worldZ & 15;

                    for (int x = 0; x < SNAPSHOT_WIDTH; x++) {
                        int worldX = originX + x;
                        int chunkX = worldX >> 4;
                        int localX = worldX & 15;

                        ChunkSnapshot chunk = chunkSnapshot(world, chunkX, chunkZ, chunkCache);
                        if (chunk == null) {
                            // The chunk is not loaded: we cannot prove an obstruction here.
                            blocks[index++] = TRANSPARENT;
                            continue;
                        }
                        Material material = chunk.getBlockType(localX, originY + y, localZ);
                        blocks[index++] = classify(material);
                    }
                }
            }

            return new Snapshot(owner, world.getUID(), originX, originY, originZ,
                    blocks, System.currentTimeMillis());
        }

        /**
         * Obtains the chunk snapshot for the given chunk coordinates, using the shared cache.
         */
        private static ChunkSnapshot chunkSnapshot(World world, int chunkX, int chunkZ,
                                                   Map<ChunkKey, ChunkSnapshot> chunkCache) {
            ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
            ChunkSnapshot cached = chunkCache.get(key);
            if (cached != null) {
                return cached;
            }
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return null;
            }
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            // includeMaxblocky = false, includeBiome = false: only block data is needed.
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
            chunkCache.put(key, snapshot);
            return snapshot;
        }

        public UUID getOwner() {
            return owner;
        }

        public UUID getWorldId() {
            return worldId;
        }

        public long getCapturedAt() {
            return capturedAt;
        }

        /**
         * @return the block classification at the given world coordinates, or {@code TRANSPARENT}
         *         when the coordinates fall outside the snapshot
         */
        public byte get(int x, int y, int z) {
            int index = index(x, y, z);
            if (index < 0) {
                return TRANSPARENT;
            }
            return blocks[index];
        }

        /**
         * @return {@code true} when the coordinates lie inside the captured volume
         */
        public boolean contains(int x, int y, int z) {
            return index(x, y, z) >= 0;
        }

        private int index(int x, int y, int z) {
            int localX = x - originX;
            int localY = y - originY;
            int localZ = z - originZ;
            if (localX < 0 || localX >= SNAPSHOT_WIDTH
                    || localY < 0 || localY >= SNAPSHOT_VERTICAL
                    || localZ < 0 || localZ >= SNAPSHOT_LENGTH) {
                return -1;
            }
            return ((localY * SNAPSHOT_LENGTH) + localZ) * SNAPSHOT_WIDTH + localX;
        }

        /**
         * Upper bound of ray steps within the snapshot volume.
         */
        int maxSteps() {
            return (SNAPSHOT_WIDTH + SNAPSHOT_LENGTH + SNAPSHOT_VERTICAL) * 2;
        }
    }

    /**
     * Key identifying a chunk snapshot inside the shared per-round cache.
     */
    private static final class ChunkKey {
        private final UUID worldId;
        private final int chunkX;
        private final int chunkZ;
        private final int hash;

        ChunkKey(UUID worldId, int chunkX, int chunkZ) {
            this.worldId = worldId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.hash = (31 * (31 * worldId.hashCode() + chunkX)) + chunkZ;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ChunkKey key)) {
                return false;
            }
            return chunkX == key.chunkX && chunkZ == key.chunkZ && worldId.equals(key.worldId);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * Classifies a material into the raytracer's opacity categories.
     *
     * <p>Glass, leaves, fences, iron bars and open doors are treated as {@code SEMI_TRANSPARENT}:
     * they do not count as a solid wall, so an entity behind them stays visible. This mirrors
     * how vanilla renders these blocks and avoids the "entity pops in when standing behind
     * glass" behaviour that would otherwise be an instant cheat tell.</p>
     *
     * <p>The result is cached per material, because this runs tens of thousands of times per
     * tick.</p>
     */
    static byte classify(Material material) {
        if (material == null || material == Material.AIR || material == Material.CAVE_AIR
                || material == Material.VOID_AIR) {
            return TRANSPARENT;
        }
        Byte cached = CLASSIFICATION_CACHE.get(material);
        if (cached != null) {
            return cached;
        }
        byte result = computeClassification(material);
        CLASSIFICATION_CACHE.put(material, result);
        return result;
    }

    /**
     * Computes the classification of a material without caching it.
     */
    private static byte computeClassification(Material material) {
        if (!material.isSolid()) {
            return TRANSPARENT;
        }
        String name = material.name();
        // Semi transparent families.
        if (name.endsWith("_GLASS") || name.endsWith("_GLASS_PANE") || name.contains("LEAVES")
                || name.endsWith("FENCE") || name.endsWith("FENCE_GATE") || name.endsWith("WALL")
                || name.endsWith("BAR") || name.endsWith("DOOR") || name.endsWith("TRAPDOOR")
                || name.endsWith("BUTTON") || name.endsWith("RAIL") || name.endsWith("STAIRS")
                || name.endsWith("SLAB") || name.endsWith("SIGN") || name.endsWith("LADDER")
                || name.endsWith("GATE") || name.endsWith("CHEST") || name.endsWith("SHULKER")
                || name.endsWith("BED") || name.endsWith("FLOWER") || name.endsWith("SAPLING")
                || name.endsWith("CARROT") || name.endsWith("POTATO") || name.endsWith("DEAD_BUSH")
                || name.endsWith("TORCH") || name.endsWith("LANTERN") || name.endsWith("WEB")
                || name.endsWith("VINE") || name.endsWith("LILY_PAD")) {
            return SEMI_TRANSPARENT;
        }
        return OPAQUE;
    }

    /**
     * Classifies a live block. Retained for callers that hold a {@link Block} reference.
     */
    static byte classify(Block block) {
        if (block == null) {
            return TRANSPARENT;
        }
        return classify(block.getType());
    }
}
