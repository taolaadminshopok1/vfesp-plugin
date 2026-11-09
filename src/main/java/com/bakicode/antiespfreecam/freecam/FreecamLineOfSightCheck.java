package com.bakicode.antiespfreecam.freecam;

import com.bakicode.antiespfreecam.config.CheatConfig;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Blocks "ghost phasing" interactions: a player whose camera has been detached from their body
 * must not be able to interact with objects that are behind a solid wall.
 *
 * <p>The check verifies the line of sight between the player's <em>authoritative server-side eye
 * position</em> and the interaction target. If a fully opaque block obstructs that line, the
 * interaction is cancelled.</p>
 *
 * <p>This is the second half of the freecam mitigation: the reach check catches interactions
 * that are too far away, while this check catches interactions that are close enough but
 * separated from the player by a wall.</p>
 */
public final class FreecamLineOfSightCheck {

    private final Plugin plugin;
    private final CheatConfig config;

    /** Violation counters per player. */
    private final Map<UUID, AtomicInteger> violations = new HashMap<>();

    public FreecamLineOfSightCheck(Plugin plugin, CheatConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Validates the line of sight to a block position.
     *
     * @param player the interacting player
     * @param blockX block X
     * @param blockY block Y
     * @param blockZ block Z
     * @return {@code true} when the target is reachable, {@code false} when a wall blocks it
     */
    public boolean checkBlock(Player player, int blockX, int blockY, int blockZ) {
        if (!config.isFreecamEnabled() || !config.isCancelThroughWalls() || config.isExempt(player)) {
            return true;
        }

        Location eye = FreecamReachCheck.eyeLocation(player);
        if (eye == null) {
            return true;
        }

        // The block itself is the target; it must never be treated as the obstruction.
        double targetX = blockX + 0.5D;
        double targetY = blockY + 0.5D;
        double targetZ = blockZ + 0.5D;

        if (!hasLineOfSight(eye, targetX, targetY, targetZ, blockX, blockY, blockZ)) {
            flag(player, "Freecam Phase (block)");
            return false;
        }
        return true;
    }

    /**
     * Validates the line of sight to an entity.
     *
     * @param player the interacting player
     * @param target the interacted entity
     * @return {@code true} when the target is reachable, {@code false} when a wall blocks it
     */
    public boolean checkEntity(Player player, Entity target) {
        if (!config.isFreecamEnabled() || !config.isCancelThroughWalls() || config.isExempt(player)) {
            return true;
        }
        if (target == null) {
            return true;
        }

        Location eye = FreecamReachCheck.eyeLocation(player);
        if (eye == null) {
            return true;
        }

        Location targetLocation = target.getLocation();
        if (targetLocation == null || targetLocation.getWorld() == null) {
            return true;
        }

        // Aim at the centre of the target's hitbox, at head height.
        org.bukkit.util.BoundingBox box = target.getBoundingBox();
        double targetX;
        double targetY;
        double targetZ;
        if (box == null) {
            targetX = targetLocation.getX();
            targetY = targetLocation.getY();
            targetZ = targetLocation.getZ();
        } else {
            targetX = (box.getMinX() + box.getMaxX()) * 0.5D;
            targetY = (box.getMinY() + box.getMaxY()) * 0.5D;
            targetZ = (box.getMinZ() + box.getMaxZ()) * 0.5D;
        }

        if (!hasLineOfSight(eye, targetX, targetY, targetZ,
                (int) Math.floor(targetX), (int) Math.floor(targetY), (int) Math.floor(targetZ))) {
            flag(player, "Freecam Phase (entity)");
            return false;
        }
        return true;
    }

    /**
     * Traces a ray from the eye to the target and reports whether any solid opaque block
     * obstructs it.
     *
     * @param eye       the authoritative eye position
     * @param targetX   target X
     * @param targetY   target Y
     * @param targetZ   target Z
     * @param ignoreX   the X of the block that must be ignored (the target itself)
     * @param ignoreY   the Y of the block that must be ignored
     * @param ignoreZ   the Z of the block that must be ignored
     */
    private boolean hasLineOfSight(Location eye, double targetX, double targetY, double targetZ,
                                   int ignoreX, int ignoreY, int ignoreZ) {
        World world = eye.getWorld();
        if (world == null) {
            return true;
        }

        double dirX = targetX - eye.getX();
        double dirY = targetY - eye.getY();
        double dirZ = targetZ - eye.getZ();

        double lengthSquared = (dirX * dirX) + (dirY * dirY) + (dirZ * dirZ);
        if (lengthSquared < 1.0E-8D) {
            return true;
        }

        int blockX = floor(eye.getX());
        int blockY = floor(eye.getY());
        int blockZ = floor(eye.getZ());

        int stepX = dirX > 0.0D ? 1 : -1;
        int stepY = dirY > 0.0D ? 1 : -1;
        int stepZ = dirZ > 0.0D ? 1 : -1;

        double deltaX = dirX == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / dirX);
        double deltaY = dirY == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / dirY);
        double deltaZ = dirZ == 0.0D ? Double.MAX_VALUE : Math.abs(1.0D / dirZ);

        double maxX = boundaryDistance(eye.getX(), blockX, stepX, deltaX);
        double maxY = boundaryDistance(eye.getY(), blockY, stepY, deltaY);
        double maxZ = boundaryDistance(eye.getZ(), blockZ, stepZ, deltaZ);

        double rayLength = Math.sqrt(lengthSquared);
        double travelled = 0.0D;
        int maxSteps = 256;

        for (int step = 0; step < maxSteps; step++) {
            if (blockX == floor(targetX) && blockY == floor(targetY) && blockZ == floor(targetZ)) {
                return true;
            }
            // Skip the target's own block.
            if (blockX != ignoreX || blockY != ignoreY || blockZ != ignoreZ) {
                Block block = world.getBlockAt(blockX, blockY, blockZ);
                if (isOpaque(block)) {
                    return false;
                }
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
        }
        return true;
    }

    /**
     * @return {@code true} when the block fully obstructs sight
     */
    private static boolean isOpaque(Block block) {
        if (block == null) {
            return false;
        }
        if (block.isEmpty() || block.isLiquid()) {
            return false;
        }
        if (!block.getType().isSolid()) {
            return false;
        }
        // Glass, leaves and fences do not constitute a "solid wall" for ghost phasing.
        String name = block.getType().name();
        return !(name.endsWith("_GLASS") || name.endsWith("_GLASS_PANE") || name.contains("LEAVES")
                || name.endsWith("FENCE") || name.endsWith("FENCE_GATE") || name.endsWith("WALL")
                || name.endsWith("BAR") || name.endsWith("DOOR") || name.endsWith("TRAPDOOR")
                || name.endsWith("STAIRS") || name.endsWith("SLAB") || name.endsWith("LADDER")
                || name.endsWith("CHEST") || name.endsWith("SHULKER") || name.endsWith("BED")
                || name.endsWith("RAIL") || name.endsWith("BUTTON") || name.endsWith("SIGN"));
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

    /**
     * Records a violation and notifies the staff.
     */
    public void flag(Player player, String type) {
        if (!config.isFlagOnViolation()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        int count = violations.computeIfAbsent(uuid, key -> new AtomicInteger()).incrementAndGet();

        if (count >= config.getViolationThreshold()) {
            if (config.isAlertStaff()) {
                String message = "[AntiESPFreecam] " + player.getName() + " triggered " + type
                        + " (x" + count + ")";
                for (Player staff : plugin.getServer().getOnlinePlayers()) {
                    if (staff.hasPermission("antiespfreecam.alerts")) {
                        staff.sendMessage(message);
                    }
                }
                plugin.getLogger().warning(message);
            }
            violations.get(uuid).set(0);
        }
    }

    /**
     * @return the number of violations recorded for a player
     */
    public int getViolationCount(UUID uuid) {
        AtomicInteger counter = violations.get(uuid);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Clears the violation counter of a player.
     */
    public void clearViolations(UUID uuid) {
        violations.remove(uuid);
    }
}
