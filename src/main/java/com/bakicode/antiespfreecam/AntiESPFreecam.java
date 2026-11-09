package com.bakicode.antiespfreecam;

import com.bakicode.antiespfreecam.config.CheatConfig;
import com.bakicode.antiespfreecam.esp.OcclusionEngine;
import com.bakicode.antiespfreecam.esp.PacketHider;
import com.bakicode.antiespfreecam.esp.RehydrationHandler;
import com.bakicode.antiespfreecam.esp.VisibilityCache;
import com.bakicode.antiespfreecam.esp.VisibilityTracker;
import com.bakicode.antiespfreecam.freecam.FreecamLineOfSightCheck;
import com.bakicode.antiespfreecam.freecam.FreecamPacketListener;
import com.bakicode.antiespfreecam.freecam.FreecamReachCheck;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Anti-ESP / Anti-Freecam plugin entry point.
 *
 * <p>The plugin neutralises two client-side cheat vectors:</p>
 * <ol>
 *   <li><b>ESP</b> - entities and containers that the observer has no line of sight to are
 *       removed from the client's world entirely, so tracers, chams, glow and box ESP have
 *       nothing to draw.</li>
 *   <li><b>Freecam</b> - interactions are validated against the player's authoritative
 *       server-side position, so a detached camera cannot reach or phase through walls.</li>
 * </ol>
 */
public final class AntiESPFreecam extends JavaPlugin implements Listener {

    private CheatConfig config;
    private VisibilityTracker tracker;
    private PacketHider packetHider;
    private RehydrationHandler rehydrationHandler;
    private FreecamReachCheck reachCheck;
    private FreecamLineOfSightCheck lineOfSightCheck;
    private FreecamPacketListener freecamListener;

    private BukkitTask updateTask;
    private BukkitTask cacheTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = CheatConfig.load(getConfig());

        // --- Anti-ESP -------------------------------------------------------
        this.tracker = new VisibilityTracker(config);
        this.rehydrationHandler = new RehydrationHandler(this, config, tracker);
        this.packetHider = new PacketHider(this, config, tracker);

        // --- Anti-Freecam ----------------------------------------------------
        this.reachCheck = new FreecamReachCheck(this, config);
        this.lineOfSightCheck = new FreecamLineOfSightCheck(this, config);
        this.freecamListener = new FreecamPacketListener(this, config, reachCheck, lineOfSightCheck);

        registerPacketListeners();
        getServer().getPluginManager().registerEvents(this, this);
        startTasks();

        getLogger().info("AntiESPFreecam enabled (ESP: " + config.isEspEnabled()
                + ", Freecam: " + config.isFreecamEnabled() + ")");
    }

    @Override
    public void onDisable() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (cacheTask != null) {
            cacheTask.cancel();
            cacheTask = null;
        }

        // Restore every hidden entity so that no player is left with a broken client state.
        if (rehydrationHandler != null) {
            for (Player online : getServer().getOnlinePlayers()) {
                restoreAll(online);
            }
        }

        unregisterPacketListeners();

        if (tracker != null) {
            tracker.shutdown();
        }
        getLogger().info("AntiESPFreecam disabled.");
    }

    /* ------------------------------------------------------------------ */
    /* Registration                                                         */
    /* ------------------------------------------------------------------ */

    private void registerPacketListeners() {
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        manager.addPacketListener(packetHider);
        manager.addPacketListener(freecamListener);
    }

    private void unregisterPacketListeners() {
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        manager.removePacketListener(packetHider);
        manager.removePacketListener(freecamListener);
    }

    private void startTasks() {
        // The main-thread update loop refreshes the mirrored entity state, captures the block
        // snapshots and queues the raytrace jobs. Everything expensive happens on the worker
        // pool, so this loop stays cheap.
        this.updateTask = getServer().getScheduler().runTaskTimer(this, this::updateAll, 1L, 1L);

        // The cache sweep runs less frequently; it only removes expired entries.
        this.cacheTask = getServer().getScheduler().runTaskTimer(this, this::purgeCaches,
                20L * 10L, 20L * 5L);
    }

    /* ------------------------------------------------------------------ */
    /* Main thread work                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Refreshes the mirrored state and queues the occlusion raytraces.
     */
    private void updateAll() {
        if (!config.isEspEnabled()) {
            return;
        }

        for (Player player : getServer().getOnlinePlayers()) {
            if (config.isExempt(player)) {
                continue;
            }

            tracker.updateObserver(player);

            // Refresh the entities near the player. Only nearby entities matter: anything
            // beyond the hidden distance gate is hidden regardless.
            org.bukkit.Location location = player.getLocation();
            org.bukkit.World world = location.getWorld();
            if (world == null) {
                continue;
            }

            double radius = config.getAlwaysHiddenDistance();
            for (Entity entity : world.getNearbyEntities(location, radius, radius, radius)) {
                tracker.updateEntity(entity);
            }

            // Queue the raytraces for this observer.
            tracker.evaluate(player);
        }

        // Restore entities that became visible again.
        rehydrationHandler.processPendingRequests();
    }

    /**
     * Removes expired cache entries.
     */
    private void purgeCaches() {
        tracker.getCache().purgeExpired();
    }

    /**
     * Shows every currently hidden entity to a player.
     */
    private void restoreAll(Player player) {
        // Drop the native hide bookkeeping and make every tracked entity visible again, so a
        // plugin reload or shutdown never leaves a client with missing entities.
        rehydrationHandler.forgetObserver(player.getUniqueId());
        tracker.clearHiddenEntities(player.getUniqueId());

        org.bukkit.Location location = player.getLocation();
        org.bukkit.World world = location.getWorld();
        if (world == null) {
            return;
        }

        double radius = config.getAlwaysHiddenDistance();
        for (Entity entity : world.getNearbyEntities(location, radius, radius, radius)) {
            try {
                player.showEntity(this, entity);
            } catch (Exception ignored) {
                // The entity may have been despawned in the meantime.
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Events                                                               */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tracker.updateObserver(player);
        tracker.updateEntity(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        tracker.removeObserver(uuid);
        rehydrationHandler.forgetObserver(uuid);
        reachCheck.clearViolations(uuid);
        lineOfSightCheck.clearViolations(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        // A teleport invalidates all cached visibility verdicts for that player.
        tracker.getCache().invalidateObserver(event.getPlayer().getUniqueId());
        tracker.updateObserver(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        tracker.clearEntities();
        tracker.getOcclusion().clearSnapshots();
    }

    /* ------------------------------------------------------------------ */
    /* Accessors                                                            */
    /* ------------------------------------------------------------------ */

    public CheatConfig getCheatConfig() {
        return config;
    }

    public VisibilityTracker getTracker() {
        return tracker;
    }

    public VisibilityCache getVisibilityCache() {
        return tracker.getCache();
    }

    public OcclusionEngine getOcclusionEngine() {
        return tracker.getOcclusion();
    }

    public FreecamReachCheck getReachCheck() {
        return reachCheck;
    }

    public FreecamLineOfSightCheck getLineOfSightCheck() {
        return lineOfSightCheck;
    }

    /**
     * Reloads the configuration from disk and applies it.
     */
    public void reload() {
        reloadConfig();
        this.config = CheatConfig.load(getConfig());
        getLogger().info("Configuration reloaded.");
    }
}
