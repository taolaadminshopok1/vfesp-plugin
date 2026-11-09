package com.bakicode.antiespfreecam.util;

import org.bukkit.util.Vector;

/**
 * Produces the multi-point sample set used for occlusion raytracing.
 *
 * <p>For every observer/target pair we trace six independent rays towards the target's
 * bounding box: {@code Center, Head, Feet, Top-Left, Bottom-Right} plus a
 * {@code Front} sample slightly towards the observer. The target is considered visible when
 * <em>at least one</em> of those rays reaches the target without being blocked by an opaque
 * block.</p>
 *
 * <p>This class is deliberately free of any Bukkit API calls: it operates purely on primitive
 * coordinates so that it can run on Netty pipeline threads and worker threads without any
 * synchronisation against the main thread.</p>
 */
public final class BoundingBoxSampler {

    /** Number of sample points produced per target. */
    public static final int SAMPLE_COUNT = 6;

    /** Indices into {@link Sample#point(int)}. */
    public static final int POINT_CENTER = 0;
    public static final int POINT_HEAD = 1;
    public static final int POINT_FEET = 2;
    public static final int POINT_TOP_LEFT = 3;
    public static final int POINT_BOTTOM_RIGHT = 4;
    /** Sixth sample: slightly towards the observer, improves detection of partially exposed targets. */
    public static final int POINT_FRONT = 5;

    private BoundingBoxSampler() {
        throw new AssertionError("Utility class");
    }

    /**
     * Fills the supplied sample buffer with the six world-space sample points of the target.
     *
     * @param minX  minimum X of the target's bounding box
     * @param minY  minimum Y of the target's bounding box
     * @param minZ  minimum Z of the target's bounding box
     * @param maxX  maximum X of the target's bounding box
     * @param maxY  maximum Y of the target's bounding box
     * @param maxZ  maximum Z of the target's bounding box
     * @param headY absolute world Y of the target's head/eye level
     * @param sample destination scratch buffer
     * @return {@code true} if the sample was filled successfully
     */
    public static boolean sample(double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ,
                                 double headY,
                                 Sample sample) {
        if (sample == null) {
            return false;
        }

        double centerX = (minX + maxX) * 0.5D;
        double centerY = (minY + maxY) * 0.5D;
        double centerZ = (minZ + maxZ) * 0.5D;

        double effectiveHeadY = headY;
        if (!Double.isFinite(effectiveHeadY) || effectiveHeadY <= minY || effectiveHeadY > maxY + 0.5D) {
            effectiveHeadY = maxY - 0.1D;
        }

        // Feet: at the lower edge of the bounding box.
        double feetY = minY + 0.05D;

        sample.points[POINT_CENTER].setX(centerX).setY(centerY).setZ(centerZ);
        sample.points[POINT_HEAD].setX(centerX).setY(effectiveHeadY).setZ(centerZ);
        sample.points[POINT_FEET].setX(centerX).setY(feetY).setZ(centerZ);
        sample.points[POINT_TOP_LEFT].setX(minX).setY(maxY).setZ(minZ);
        sample.points[POINT_BOTTOM_RIGHT].setX(maxX).setY(minY).setZ(maxZ);

        // Front sample: a third of a block towards the observer in the XZ plane, at head height.
        double dx = centerX - sample.observerX;
        double dz = centerZ - sample.observerZ;
        double length = Math.sqrt((dx * dx) + (dz * dz));
        double frontX = centerX;
        double frontZ = centerZ;
        if (length > 1.0E-6D) {
            frontX = centerX - (dx / length) * 0.35D;
            frontZ = centerZ - (dz / length) * 0.35D;
        }
        sample.points[POINT_FRONT].setX(frontX).setY(effectiveHeadY).setZ(frontZ);

        return true;
    }

    /**
     * Reusable scratch buffer holding the observer eye position and the sampled target points.
     *
     * <p>Instances are <em>not</em> thread safe: each worker thread must own its own buffer.</p>
     */
    public static final class Sample {

        private final Vector[] points = new Vector[SAMPLE_COUNT];

        private double observerX;
        private double observerY;
        private double observerZ;

        public Sample() {
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                points[i] = new Vector();
            }
        }

        /**
         * Positions the observer eye within this buffer.
         */
        public void setObserver(double x, double y, double z) {
            this.observerX = x;
            this.observerY = y;
            this.observerZ = z;
        }

        public double observerX() {
            return observerX;
        }

        public double observerY() {
            return observerY;
        }

        public double observerZ() {
            return observerZ;
        }

        public Vector[] points() {
            return points;
        }

        public Vector point(int index) {
            return points[index];
        }
    }
}
