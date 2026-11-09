package com.bakicode.antiespfreecam.esp;

import com.bakicode.antiespfreecam.config.CheatConfig;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies the visibility transitions on the main thread: it destroys the entities an observer
 * must not see, and re-spawns the ones that became visible again.
 *
 * <p><b>Why cancelling packets is not enough.</b> Dropping the outbound spawn / metadata /
 * movement packets does <em>not</em> hide an entity from the client: the client keeps it in
 * memory and keeps rendering it, so a tracer, a chams overlay or a box ESP still draws it. The
 * entity has to be explicitly destroyed on the client, and later re-spawned with its full state
 * once it becomes visible again.</p>
 *
 * <p><b>How the destroy and the re-spawn are performed.</b> Paper's native
 * {@link Player#hideEntity(Plugin, Entity)} and {@link Player#showEntity(Plugin, Entity)} do
 * exactly that. {@code hideEntity} unregisters the entity from the player's tracker entry, which
 * makes the server emit an {@code ENTITY_DESTROY} packet and stop sending further updates for
 * that entity. {@code showEntity} re-registers it and emits a complete spawn packet including
 * metadata and equipment.</p>
 *
 * <p><b>Pairing requirement.</b> Paper implements visibility through a per-entity "inverted
 * visibility" set. {@code showEntity} only emits a spawn packet when the entity is present in
 * that set, i.e. when {@code hideEntity} was called before. The two calls are therefore strictly
 * paired, and the state machine in {@link VisibilityTracker} tracks that pairing. When an entity
 * was never destroyed (its spawn packet was simply filtered), the spawn has to be sent
 * explicitly instead.</p>
 */
public final class RehydrationHandler {

    private final Plugin plugin;
    private final CheatConfig config;
    private final VisibilityTracker tracker;

    public RehydrationHandler(Plugin plugin, CheatConfig config, VisibilityTracker tracker) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
    }

    /**
     * Applies the desired visibility for every observer.
     *
     * <p><b>Must be called from the main server thread.</b></p>
     *
     * @return the number of visibility transitions that were applied
     */
    public int apply() {
        if (!config.isEspEnabled()) {
            return 0;
        }

        int transitions = 0;
        for (Player observer : plugin.getServer().getOnlinePlayers()) {
            if (config.isExempt(observer)) {
                continue;
            }
            transitions += applyFor(observer);
        }
        return transitions;
    }

    /**
     * Applies the desired visibility for a single observer.
     */
    private int applyFor(Player observer) {
        UUID observerId = observer.getUniqueId();
        Map<Integer, Boolean> desired = tracker.getDesired(observerId);
        if (desired.isEmpty()) {
            return 0;
        }

        List<Entity> nearby = tracker.getNearbyEntities(observerId);
        int transitions = 0;

        // --- Entities that should be hidden ------------------------------------
        for (Map.Entry<Integer, Boolean> entry : desired.entrySet()) {
            if (entry.getValue()) {
                continue;
            }
            int entityId = entry.getKey();
            VisibilityTracker.ClientState state = tracker.getClientState(observerId, entityId);
            if (state == VisibilityTracker.ClientState.DESTROYED) {
                // Already hidden.
                continue;
            }

            Entity entity = resolve(nearby, entityId);
            if (entity == null) {
                continue;
            }

            if (state == VisibilityTracker.ClientState.VISIBLE) {
                // The client holds the entity: destroy it explicitly so the client drops it
                // from memory and stops rendering it.
                if (config.isSendExplicitDestroy()) {
                    try {
                        observer.hideEntity(plugin, entity);
                        transitions++;
                        tracker.setClientState(observerId, entityId,
                                VisibilityTracker.ClientState.DESTROYED);
                        continue;
                    } catch (Exception ex) {
                        // The entity may have been despawned; the filtered packets keep the
                        // client from learning anything about it either way.
                    }
                }
                // The explicit destroy is disabled: the client keeps the entity but never
                // receives any update for it, so it cannot be tracked or rendered as ESP.
                tracker.setClientState(observerId, entityId, VisibilityTracker.ClientState.FILTERED);
                continue;
            }
            // If the state is UNKNOWN the client never received the spawn packet, so there is
            // nothing to destroy. The packet listener keeps filtering its updates.
            tracker.setClientState(observerId, entityId, VisibilityTracker.ClientState.FILTERED);
        }

        // --- Entities that should be visible again ------------------------------
        for (Map.Entry<Integer, Boolean> entry : desired.entrySet()) {
            if (!entry.getValue()) {
                continue;
            }
            int entityId = entry.getKey();
            VisibilityTracker.ClientState state = tracker.getClientState(observerId, entityId);
            if (state == VisibilityTracker.ClientState.VISIBLE) {
                continue;
            }

            Entity entity = resolve(nearby, entityId);
            if (entity == null) {
                continue;
            }

            try {
                if (state == VisibilityTracker.ClientState.DESTROYED) {
                    // The entity was destroyed through the native API, so showEntity emits a
                    // fresh, complete spawn packet including metadata and equipment.
                    observer.showEntity(plugin, entity);
                } else {
                    // The entity was never destroyed (its spawn packet was filtered), so
                    // showEntity would be a no-op: the spawn has to be sent explicitly.
                    sendSpawn(observer, entity);
                }
                tracker.setClientState(observerId, entityId, VisibilityTracker.ClientState.VISIBLE);
                transitions++;
            } catch (Exception ex) {
                // Ignore: the next tick will retry.
            }
        }

        return transitions;
    }

    /**
     * Resolves an entity id to a live Bukkit entity.
     */
    private Entity resolve(List<Entity> nearby, int entityId) {
        for (Entity entity : nearby) {
            if (entity.getEntityId() == entityId) {
                return entity;
            }
        }
        return null;
    }

    /**
     * Sends an explicit spawn packet for an entity the client does not know about.
     */
    private void sendSpawn(Player observer, Entity entity) {
        try {
            PacketContainer packet = ProtocolLibrary.getProtocolManager()
                    .createPacket(PacketType.Play.Server.SPAWN_ENTITY);
            packet.getIntegers().write(0, entity.getEntityId());
            packet.getUUIDs().write(0, entity.getUniqueId());
            packet.getDoubles().write(0, entity.getLocation().getX());
            packet.getDoubles().write(1, entity.getLocation().getY());
            packet.getDoubles().write(2, entity.getLocation().getZ());
            packet.getBytes().write(0, toAngle(entity.getLocation().getYaw()));
            packet.getBytes().write(1, toAngle(entity.getLocation().getPitch()));
            ProtocolLibrary.getProtocolManager().sendServerPacket(observer, packet);
        } catch (Exception ignored) {
            // The next tick will retry.
        }
    }

    /**
     * Shows every tracked entity to a player.
     *
     * <p>Used on plugin disable and on world change, so that a reload never leaves a client
     * with missing entities.</p>
     */
    public void restoreAll(Player player) {
        UUID observerId = player.getUniqueId();
        List<Entity> nearby = tracker.getNearbyEntities(observerId);
        for (Entity entity : nearby) {
            try {
                player.showEntity(plugin, entity);
            } catch (Exception ignored) {
                // The entity may have been despawned.
            }
        }
        tracker.forgetObserverState(observerId);
    }

    /**
     * Converts a yaw/pitch value in degrees into the vanilla's 1/256 degree byte angle.
     */
    private static byte toAngle(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        }
        if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return (byte) (wrapped * 256.0D / 360.0D);
    }
}
