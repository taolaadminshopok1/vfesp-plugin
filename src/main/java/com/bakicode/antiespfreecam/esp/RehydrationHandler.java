package com.bakicode.antiespfreecam.esp;

import com.bakicode.antiespfreecam.config.CheatConfig;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restores entities that became visible again.
 *
 * <p>When the Anti-ESP engine hides an entity, the client either never received its spawn
 * packet or was told to forget it. The moment the entity steps out from behind a wall, the
 * client must receive a complete, fresh set of packets: spawn, metadata, equipment and head
 * rotation. Sending only the spawn would leave the client with a "naked" entity (no skin, no
 * name, no equipment), which is an immediate cheat tell.</p>
 *
 * <p>This handler performs that full re-synchronisation using Paper's native
 * {@link Player#showEntity(Plugin, Entity)} / {@link Player#hideEntity(Plugin, Entity)} API,
 * which lets the server itself emit a correctly formed, version-correct spawn packet. This is
 * considerably more robust than hand-assembling protocol packets, because the vanilla server
 * fills in every field the client expects.</p>
 */
public final class RehydrationHandler {

    private final Plugin plugin;
    private final CheatConfig config;
    private final VisibilityTracker tracker;

    /**
     * Entities that are currently hidden from a given observer.
     */
    private final Map<UUID, Set<Integer>> hidden = new HashMap<>();

    /**
     * Entities that were hidden using the native Bukkit API and therefore need an explicit
     * {@code showEntity} call to be restored.
     */
    private final Map<UUID, Set<Integer>> nativelyHidden = new HashMap<>();

    public RehydrationHandler(Plugin plugin, CheatConfig config, VisibilityTracker tracker) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
    }

    /**
     * Processes the pending rehydration requests.
     *
     * <p><b>Must be called from the main server thread.</b></p>
     *
     * @return the number of entities that were re-spawned
     */
    public int processPendingRequests() {
        java.util.List<VisibilityTracker.RehydrationRequest> requests = tracker.drainRehydrationRequests();
        if (requests.isEmpty()) {
            return 0;
        }

        int rehydrated = 0;
        for (VisibilityTracker.RehydrationRequest request : requests) {
            if (resend(request.observer, request.entityId)) {
                rehydrated++;
            }
        }
        return rehydrated;
    }

    /**
     * Re-sends the full entity state to the observer.
     *
     * @param observerId the observing player's UUID
     * @param entityId   the target entity's network id
     * @return {@code true} when the entity was successfully re-spawned
     */
    public boolean resend(UUID observerId, int entityId) {
        Player observer = plugin.getServer().getPlayer(observerId);
        if (observer == null || !observer.isOnline()) {
            return false;
        }

        Entity entity = findEntity(entityId);
        if (entity == null || !entity.isValid()) {
            tracker.clearHidden(observerId, entityId);
            forget(observerId, entityId);
            return false;
        }

        // Re-check visibility: the entity may have moved away again in the meantime.
        VisibilityTracker.EntityState state = tracker.findState(entityId);
        if (state == null) {
            return false;
        }
        Boolean visible = tracker.cachedVisibility(observerId, entityId);
        if (visible != null && !visible) {
            // Still hidden: keep the marker so we retry on a later tick.
            tracker.markHidden(observerId, entityId);
            return false;
        }

        try {
            // Restore the entity for this observer. The server emits a complete spawn packet
            // including metadata and equipment.
            observer.showEntity(plugin, entity);

            // If we previously hid it through the native API, drop that bookkeeping.
            Set<Integer> nativeSet = nativelyHidden.get(observerId);
            if (nativeSet != null) {
                nativeSet.remove(entityId);
            }

            tracker.clearHidden(observerId, entityId);
            forget(observerId, entityId);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to rehydrate entity " + entityId
                    + " for " + observer.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * Hides an entity from an observer using the native Bukkit API.
     *
     * <p>This is used as a complement to the packet filter: it guarantees that the client
     * receives an explicit {@code ENTITY_DESTROY} and therefore drops the entity from its
     * memory instead of keeping a stale copy around.</p>
     *
     * @param observerId the observing player's UUID
     * @param entity     the entity to hide
     */
    public void hideFromObserver(UUID observerId, Entity entity) {
        if (entity == null || observerId == null) {
            return;
        }
        Player observer = plugin.getServer().getPlayer(observerId);
        if (observer == null || !observer.isOnline()) {
            return;
        }
        try {
            observer.hideEntity(plugin, entity);
            hidden.computeIfAbsent(observerId, key -> new HashSet<>()).add(entity.getEntityId());
            nativelyHidden.computeIfAbsent(observerId, key -> new HashSet<>()).add(entity.getEntityId());
        } catch (Exception ex) {
            plugin.getLogger().fine("Could not hide entity " + entity.getEntityId()
                    + " from " + observer.getName() + ": " + ex.getMessage());
        }
    }

    /**
     * Resolves an entity id back to a Bukkit entity.
     */
    private Entity findEntity(int entityId) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getEntityId() == entityId) {
                return online;
            }
        }
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getEntityId() == entityId) {
                    return entity;
                }
            }
        }
        return null;
    }

    /**
     * Removes the bookkeeping for a single pair.
     */
    private void forget(UUID observerId, int entityId) {
        Set<Integer> set = hidden.get(observerId);
        if (set != null) {
            set.remove(entityId);
        }
        Set<Integer> nativeSet = nativelyHidden.get(observerId);
        if (nativeSet != null) {
            nativeSet.remove(entityId);
        }
    }

    /**
     * Removes all bookkeeping for an observer.
     */
    public void forgetObserver(UUID observerId) {
        hidden.remove(observerId);
        nativelyHidden.remove(observerId);
    }

    /**
     * @return the number of entities currently hidden from the observer
     */
    public int hiddenCount(UUID observerId) {
        Set<Integer> set = hidden.get(observerId);
        return set == null ? 0 : set.size();
    }
}
