package com.bakicode.antiespfreecam.freecam;

import com.bakicode.antiespfreecam.config.CheatConfig;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-authoritative distance limiter for freecam "ghost reach" interactions.
 *
 * <p><b>The freecam problem.</b> A freecam cheat decouples the client's camera from the server
 * validated player hitbox. The player body stays where the server thinks it is, while the
 * camera roams freely through walls. The cheat then issues interaction packets for objects the
 * camera sees but the body cannot reach.</p>
 *
 * <p><b>The mitigation.</b> Every interaction is validated against the player's <em>true
 * server-side eye position</em>, never against anything the client claims. If the distance from
 * that authoritative eye position to the interaction target exceeds the allowed reach, the
 * interaction is cancelled and the player is flagged.</p>
 */
public final class FreecamReachCheck {

    private final Plugin plugin;
    private final CheatConfig config;

    /** Violation counters per player. */
    private final Map<UUID, AtomicInteger> violations = new ConcurrentHashMap<>();

    public FreecamReachCheck(Plugin plugin, CheatConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Determines the maximum allowed reach for a player.
     *
     * <p>Creative and spectator players are allowed the larger creative reach; everyone else is
     * limited to the survival reach.</p>
     */
    public double maxReachFor(Player player) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return config.getCreativeReach();
        }
        return config.getSurvivalReach();
    }

    /**
     * Validates an interaction with a block position.
     *
     * @param player   the interacting player
     * @param blockX   block X
     * @param blockY   block Y
     * @param blockZ   block Z
     * @return {@code true} when the interaction is within reach and may proceed
     */
    public boolean checkBlock(Player player, int blockX, int blockY, int blockZ) {
        if (!config.isFreecamEnabled() || config.isExempt(player)) {
            return true;
        }

        Location eye = eyeLocation(player);
        if (eye == null) {
            return true;
        }

        // Distance to the centre of the targeted block.
        double dx = eye.getX() - (blockX + 0.5D);
        double dy = eye.getY() - (blockY + 0.5D);
        double dz = eye.getZ() - (blockZ + 0.5D);
        double distance = Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));

        double maxReach = maxReachFor(player);
        if (distance <= maxReach) {
            return true;
        }

        if (config.isCancelOutOfReach()) {
            flag(player, "Freecam Reach (block)", distance, maxReach);
            return false;
        }
        flag(player, "Freecam Reach (block)", distance, maxReach);
        return true;
    }

    /**
     * Validates an interaction with an entity.
     *
     * @param player the interacting player
     * @param target the entity being interacted with
     * @return {@code true} when the interaction is within reach and may proceed
     */
    public boolean checkEntity(Player player, Entity target) {
        if (!config.isFreecamEnabled() || config.isExempt(player)) {
            return true;
        }
        if (target == null) {
            return true;
        }

        Location eye = eyeLocation(player);
        if (eye == null) {
            return true;
        }

        org.bukkit.util.BoundingBox box = target.getBoundingBox();
        if (box == null) {
            return true;
        }

        // Distance to the closest point of the target's hitbox.
        double distance = distanceToBox(eye, box);

        double maxReach = maxReachFor(player);
        if (distance <= maxReach) {
            return true;
        }

        flag(player, "Freecam Reach (entity)", distance, maxReach);
        return !config.isCancelOutOfReach();
    }

    /**
     * Computes the shortest distance from a point to an axis aligned box.
     */
    public static double distanceToBox(Location point, org.bukkit.util.BoundingBox box) {
        double x = point.getX();
        double y = point.getY();
        double z = point.getZ();

        double minX = box.getMinX();
        double minY = box.getMinY();
        double minZ = box.getMinZ();
        double maxX = box.getMaxX();
        double maxY = box.getMaxY();
        double maxZ = box.getMaxZ();

        double dx = Math.max(Math.max(minX - x, 0.0D), x - maxX);
        double dy = Math.max(Math.max(minY - y, 0.0D), y - maxY);
        double dz = Math.max(Math.max(minZ - z, 0.0D), z - maxZ);

        return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
    }

    /**
     * Returns the authoritative server-side eye location of a player.
     */
    public static Location eyeLocation(Player player) {
        if (player == null) {
            return null;
        }
        Location location = player.getLocation();
        if (location == null) {
            return null;
        }
        return location.clone().add(0.0D, player.getEyeHeight(), 0.0D);
    }

    /**
     * Records a violation and notifies the staff.
     */
    public void flag(Player player, String type, double distance, double maxReach) {
        if (!config.isFlagOnViolation()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        int count = violations.computeIfAbsent(uuid, key -> new AtomicInteger()).incrementAndGet();

        if (count >= config.getViolationThreshold()) {
            if (config.isAlertStaff()) {
                notifyStaff(player, type, distance, maxReach, count);
            }
            // Reset the counter so the alert is not repeated every single interaction.
            violations.get(uuid).set(0);
        }
    }

    /**
     * Sends an alert to all staff members.
     */
    private void notifyStaff(Player player, String type, double distance, double maxReach, int count) {
        String message = String.format(
                "[AntiESPFreecam] %s triggered %s (distance %.2f > max %.2f, x%d)",
                player.getName(), type, distance, maxReach, count);

        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission("antiespfreecam.alerts")) {
                staff.sendMessage(message);
            }
        }
        plugin.getLogger().warning(message);
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
