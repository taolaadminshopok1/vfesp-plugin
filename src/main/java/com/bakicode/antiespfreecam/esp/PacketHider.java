package com.bakicode.antiespfreecam.esp;

import com.bakicode.antiespfreecam.config.CheatConfig;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Outbound packet interception for the Anti-ESP engine.
 *
 * <p>This listener runs on the Netty pipeline thread. It therefore never touches the Bukkit
 * API and never performs any raytracing: it only reads the verdicts that the worker pool
 * published into the {@link VisibilityCache}.</p>
 *
 * <p>When a target is invisible the listener drops the spawn / metadata / movement / equipment
 * packets. The client consequently never learns anything about that entity, which makes
 * client-side ESP, tracers, chams and glow mathematically impossible: there is nothing to
 * render.</p>
 */
public final class PacketHider extends PacketAdapter {

    /** Packet types that carry entity state and must therefore be filtered. */
    private static final PacketType[] FILTERED_SERVER_PACKETS = {
            PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.ENTITY_METADATA,
            PacketType.Play.Server.REL_ENTITY_MOVE,
            PacketType.Play.Server.REL_ENTITY_MOVE_LOOK,
            PacketType.Play.Server.ENTITY_LOOK,
            PacketType.Play.Server.ENTITY_TELEPORT,
            PacketType.Play.Server.ENTITY_EQUIPMENT,
            PacketType.Play.Server.BLOCK_ACTION,
            PacketType.Play.Server.TILE_ENTITY_DATA,
    };

    private final CheatConfig config;
    private final VisibilityTracker tracker;

    public PacketHider(Plugin plugin, CheatConfig config, VisibilityTracker tracker) {
        super(plugin, ListenerPriority.LOW, FILTERED_SERVER_PACKETS);
        this.config = config;
        this.tracker = tracker;
    }

    @Override
    public void onPacketSending(PacketEvent event) {
        if (!config.isEspEnabled()) {
            return;
        }
        Player observer = event.getPlayer();
        if (observer == null || config.isExempt(observer)) {
            return;
        }

        PacketContainer packet = event.getPacket();
        PacketType type = packet.getType();

        // Block entity / container handling.
        if (type == PacketType.Play.Server.BLOCK_ACTION || type == PacketType.Play.Server.TILE_ENTITY_DATA) {
            handleBlockEntity(event, observer, packet, type);
            return;
        }

        Integer entityId = extractEntityId(packet, type);
        if (entityId == null) {
            return;
        }

        VisibilityTracker.EntityState target = tracker.findState(entityId);
        if (target == null) {
            // Unknown entity: let the packet through. The server may be spawning an entity that
            // has not been mirrored yet; blocking it would break the entity entirely.
            return;
        }

        if (!tracker.shouldTrack(target)) {
            return;
        }

        UUID observerId = observer.getUniqueId();

        // Read the cached verdict. A missing verdict means the entity has not been evaluated
        // yet; in that case we optimistically allow the packet so that entities never fail to
        // spawn for legitimate players.
        Boolean visible = tracker.cachedVisibility(observerId, entityId);
        if (visible == null || visible) {
            if (visible == Boolean.TRUE && tracker.isHidden(observerId, entityId)) {
                // The entity became visible again: queue a re-spawn so the client restores it
                // with full metadata and equipment.
                tracker.requestRehydration(observerId, entityId);
            }
            return;
        }

        // Hidden: drop the packet entirely. The client never learns the entity's state.
        event.setCancelled(true);

        if (isSpawnPacket(type)) {
            // The client must not receive the spawn at all, so it never knows the entity.
            tracker.clearHidden(observerId, entityId);
        } else {
            tracker.markHidden(observerId, entityId);
        }
    }

    /**
     * Handles container (chest / shulker) packets.
     */
    private void handleBlockEntity(PacketEvent event, Player observer, PacketContainer packet, PacketType type) {
        if (!config.isHideContainers()) {
            return;
        }
        BlockPosition position = extractBlockPosition(packet, type);
        if (position == null) {
            return;
        }
        if (tracker.isContainerVisible(observer, position)) {
            return;
        }
        event.setCancelled(true);
    }

    /**
     * @return {@code true} when the packet type creates a new client-side entity
     */
    private static boolean isSpawnPacket(PacketType type) {
        return type == PacketType.Play.Server.SPAWN_ENTITY;
    }

    /* ------------------------------------------------------------------ */
    /* Packet field extraction                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Extracts the entity id from a packet.
     *
     * <p>ProtocolLib 5.x does not expose a dedicated {@code getEntityIds()} accessor, so the
     * entity id is read from the packet's integer fields. For every filtered packet type the
     * entity id is the <em>first</em> integer field of the packet, which holds for all vanilla
     * clients from 1.8 through 1.21.x.</p>
     *
     * @return the entity id, or {@code null} when it could not be determined
     */
    static Integer extractEntityId(PacketContainer packet, PacketType type) {
        try {
            if (isEntityScoped(type)) {
                return readFirstInt(packet);
            }
        } catch (Exception ex) {
            // Never let a reflection failure break the packet pipeline.
            return null;
        }
        return null;
    }

    /**
     * @return {@code true} when the packet's first integer field is the entity id
     */
    private static boolean isEntityScoped(PacketType type) {
        return type == PacketType.Play.Server.SPAWN_ENTITY
                || type == PacketType.Play.Server.ENTITY_METADATA
                || type == PacketType.Play.Server.REL_ENTITY_MOVE
                || type == PacketType.Play.Server.REL_ENTITY_MOVE_LOOK
                || type == PacketType.Play.Server.ENTITY_LOOK
                || type == PacketType.Play.Server.ENTITY_TELEPORT
                || type == PacketType.Play.Server.ENTITY_EQUIPMENT;
    }

    /**
     * Reads the first integer field of a packet.
     */
    private static Integer readFirstInt(PacketContainer packet) {
        if (packet.getIntegers().size() == 0) {
            return null;
        }
        return packet.getIntegers().readSafely(0);
    }

    /**
     * Extracts the block position from a block entity packet.
     */
    static BlockPosition extractBlockPosition(PacketContainer packet, PacketType type) {
        try {
            if (type == PacketType.Play.Server.BLOCK_ACTION || type == PacketType.Play.Server.TILE_ENTITY_DATA) {
                if (packet.getBlockPositionModifier().size() == 0) {
                    return null;
                }
                return packet.getBlockPositionModifier().readSafely(0);
            }
        } catch (Exception ex) {
            return null;
        }
        return null;
    }

    /**
     * Removes all bookkeeping for an observer (on disconnect).
     */
    public void forget(UUID observer) {
        tracker.clearHiddenEntities(observer);
    }
}
