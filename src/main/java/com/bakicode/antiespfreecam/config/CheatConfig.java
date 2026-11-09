package com.bakicode.antiespfreecam.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Central, hot-swappable configuration for the Anti-ESP / Anti-Freecam engines.
 *
 * <p>All values are read once at plugin enable (and on {@code /antiespfreecam reload}) into final-ish
 * fields so that the Netty pipeline threads never touch the Bukkit configuration API.</p>
 */
public final class CheatConfig {

    /* ------------------------------------------------------------------ */
    /* Anti-ESP                                                            */
    /* ------------------------------------------------------------------ */

    private final boolean espEnabled;
    private final boolean hidePlayers;
    private final boolean hideMobs;
    private final boolean hideContainers;

    /** Distance (<=) at which a target is always considered visible. */
    private final double alwaysVisibleDistance;
    /** Distance (>=) at which a target is always considered hidden. */
    private final double alwaysHiddenDistance;

    /** Visibility cache time-to-live in milliseconds. */
    private final long cacheTtlMillis;

    /** Number of dedicated raytrace worker threads. */
    private final int raytraceThreads;

    /** Minimum milliseconds between two raytrace passes for the same observer/target pair. */
    private final long minTraceIntervalMillis;

    /** Whether the plugin sends an explicit ENTITY_DESTROY packet when hiding an entity. */
    private final boolean sendExplicitDestroy;

    /** Maximum amount of entities tracked per observer. */
    private final int maxTrackedEntitiesPerObserver;

    /* ------------------------------------------------------------------ */
    /* Anti-Freecam                                                        */
    /* ------------------------------------------------------------------ */

    private final boolean freecamEnabled;
    private final double survivalReach;
    private final double creativeReach;
    private final boolean cancelThroughWalls;
    private final boolean cancelOutOfReach;
    private final boolean flagOnViolation;
    private final int violationThreshold;
    private final boolean alertStaff;

    /* ------------------------------------------------------------------ */
    /* Exemptions                                                          */
    /* ------------------------------------------------------------------ */

    private final Set<UUID> exemptPlayers;
    private final Set<String> exemptPermissions;
    private final Set<String> blockedWorlds;

    private CheatConfig(Builder b) {
        this.espEnabled = b.espEnabled;
        this.hidePlayers = b.hidePlayers;
        this.hideMobs = b.hideMobs;
        this.hideContainers = b.hideContainers;
        this.alwaysVisibleDistance = b.alwaysVisibleDistance;
        this.alwaysHiddenDistance = b.alwaysHiddenDistance;
        this.cacheTtlMillis = b.cacheTtlMillis;
        this.raytraceThreads = b.raytraceThreads;
        this.minTraceIntervalMillis = b.minTraceIntervalMillis;
        this.sendExplicitDestroy = b.sendExplicitDestroy;
        this.maxTrackedEntitiesPerObserver = b.maxTrackedEntitiesPerObserver;

        this.freecamEnabled = b.freecamEnabled;
        this.survivalReach = b.survivalReach;
        this.creativeReach = b.creativeReach;
        this.cancelThroughWalls = b.cancelThroughWalls;
        this.cancelOutOfReach = b.cancelOutOfReach;
        this.flagOnViolation = b.flagOnViolation;
        this.violationThreshold = b.violationThreshold;
        this.alertStaff = b.alertStaff;

        this.exemptPlayers = new HashSet<>(b.exemptPlayers);
        this.exemptPermissions = new HashSet<>(b.exemptPermissions);
        this.blockedWorlds = new HashSet<>(b.blockedWorlds);
    }

    /* ------------------------------------------------------------------ */
    /* Loading                                                             */
    /* ------------------------------------------------------------------ */

    public static CheatConfig load(FileConfiguration cfg) {
        Builder b = new Builder();

        b.espEnabled = cfg.getBoolean("esp.enabled", true);
        b.hidePlayers = cfg.getBoolean("esp.hide-players", true);
        b.hideMobs = cfg.getBoolean("esp.hide-mobs", true);
        b.hideContainers = cfg.getBoolean("esp.hide-containers", true);

        b.alwaysVisibleDistance = cfg.getDouble("esp.always-visible-distance", 2.5D);
        b.alwaysHiddenDistance = cfg.getDouble("esp.always-hidden-distance", 48.0D);
        b.cacheTtlMillis = Math.max(1L, cfg.getLong("esp.cache-ttl-millis", 100L));
        b.raytraceThreads = Math.max(1, Math.min(8, cfg.getInt("esp.raytrace-threads", 2)));
        b.minTraceIntervalMillis = Math.max(0L, cfg.getLong("esp.min-trace-interval-millis", 50L));
        b.sendExplicitDestroy = cfg.getBoolean("esp.send-explicit-destroy", true);
        b.maxTrackedEntitiesPerObserver = Math.max(16, cfg.getInt("esp.max-tracked-entities-per-observer", 512));

        b.freecamEnabled = cfg.getBoolean("freecam.enabled", true);
        b.survivalReach = cfg.getDouble("freecam.survival-reach", 4.5D);
        b.creativeReach = cfg.getDouble("freecam.creative-reach", 5.2D);
        b.cancelThroughWalls = cfg.getBoolean("freecam.cancel-through-walls", true);
        b.cancelOutOfReach = cfg.getBoolean("freecam.cancel-out-of-reach", true);
        b.flagOnViolation = cfg.getBoolean("freecam.flag-on-violation", true);
        b.violationThreshold = Math.max(1, cfg.getInt("freecam.violation-threshold", 3));
        b.alertStaff = cfg.getBoolean("freecam.alert-staff", true);

        for (String raw : cfg.getStringList("exempt.players")) {
            UUID uuid = parseUuid(raw);
            if (uuid != null) {
                b.exemptPlayers.add(uuid);
            }
        }
        b.exemptPermissions.addAll(cfg.getStringList("exempt.permissions"));
        b.blockedWorlds.addAll(cfg.getStringList("exempt.blocked-worlds"));

        // Sanity: the hidden gate must sit strictly beyond the visible gate.
        if (b.alwaysHiddenDistance <= b.alwaysVisibleDistance) {
            b.alwaysHiddenDistance = b.alwaysVisibleDistance + 1.0D;
        }
        return new CheatConfig(b);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Accessors                                                           */
    /* ------------------------------------------------------------------ */

    public boolean isEspEnabled() {
        return espEnabled;
    }

    public boolean isHidePlayers() {
        return hidePlayers;
    }

    public boolean isHideMobs() {
        return hideMobs;
    }

    public boolean isHideContainers() {
        return hideContainers;
    }

    public double getAlwaysVisibleDistance() {
        return alwaysVisibleDistance;
    }

    public double getAlwaysHiddenDistance() {
        return alwaysHiddenDistance;
    }

    public long getCacheTtlMillis() {
        return cacheTtlMillis;
    }

    public int getRaytraceThreads() {
        return raytraceThreads;
    }

    public long getMinTraceIntervalMillis() {
        return minTraceIntervalMillis;
    }

    public boolean isSendExplicitDestroy() {
        return sendExplicitDestroy;
    }

    public int getMaxTrackedEntitiesPerObserver() {
        return maxTrackedEntitiesPerObserver;
    }

    public boolean isFreecamEnabled() {
        return freecamEnabled;
    }

    public double getSurvivalReach() {
        return survivalReach;
    }

    public double getCreativeReach() {
        return creativeReach;
    }

    public boolean isCancelThroughWalls() {
        return cancelThroughWalls;
    }

    public boolean isCancelOutOfReach() {
        return cancelOutOfReach;
    }

    public boolean isFlagOnViolation() {
        return flagOnViolation;
    }

    public int getViolationThreshold() {
        return violationThreshold;
    }

    public boolean isAlertStaff() {
        return alertStaff;
    }

    /**
     * Determines whether the given observer is exempt from every check.
     */
    public boolean isExempt(Player player) {
        if (player == null) {
            return true;
        }
        if (exemptPlayers.contains(player.getUniqueId())) {
            return true;
        }
        for (String permission : exemptPermissions) {
            if (player.hasPermission(permission)) {
                return true;
            }
        }
        if (player.getWorld() != null && blockedWorlds.contains(player.getWorld().getName())) {
            return true;
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Builder                                                             */
    /* ------------------------------------------------------------------ */

    private static final class Builder {
        private boolean espEnabled = true;
        private boolean hidePlayers = true;
        private boolean hideMobs = true;
        private boolean hideContainers = true;
        private double alwaysVisibleDistance = 2.5D;
        private double alwaysHiddenDistance = 48.0D;
        private long cacheTtlMillis = 100L;
        private int raytraceThreads = 2;
        private long minTraceIntervalMillis = 50L;
        private boolean sendExplicitDestroy = true;
        private int maxTrackedEntitiesPerObserver = 512;

        private boolean freecamEnabled = true;
        private double survivalReach = 4.5D;
        private double creativeReach = 5.2D;
        private boolean cancelThroughWalls = true;
        private boolean cancelOutOfReach = true;
        private boolean flagOnViolation = true;
        private int violationThreshold = 3;
        private boolean alertStaff = true;

        private final Set<UUID> exemptPlayers = new HashSet<>();
        private final Set<String> exemptPermissions = new HashSet<>();
        private final Set<String> blockedWorlds = new HashSet<>();
    }
}
