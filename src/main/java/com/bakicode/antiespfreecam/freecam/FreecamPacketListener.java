package com.bakicode.antiespfreecam.freecam;

import com.bakicode.antiespfreecam.config.CheatConfig;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Inbound packet listener for the Anti-Freecam engine.
 *
 * <p>Intercepts the interaction packets a client can send and validates them against the
 * player's authoritative server-side position:</p>
 *
 * <ul>
 *   <li>{@code USE_ITEM_ON} / {@code INTERACT_AT_TARGET} - block interactions</li>
 *   <li>{@code USE_ENTITY} / {@code INTERACT_AT_TARGET} - entity interactions</li>
 *   <li>{@code BLOCK_DIG} - block breaking</li>
 * </ul>
 *
 * <p>When a check fails the packet is cancelled, which means the server never applies the
 * interaction. The player is flagged for staff review.</p>
 */
public final class FreecamPacketListener extends PacketAdapter {

    private static final PacketType[] CLIENT_PACKETS = {
            PacketType.Play.Client.USE_ITEM_ON,
            PacketType.Play.Client.USE_ENTITY,
            PacketType.Play.Client.BLOCK_DIG,
    };

    private final CheatConfig config;
    private final FreecamReachCheck reachCheck;
    private final FreecamLineOfSightCheck losCheck;

    public FreecamPacketListener(Plugin plugin, CheatConfig config,
                                 FreecamReachCheck reachCheck, FreecamLineOfSightCheck losCheck) {
        super(plugin, ListenerPriority.LOW, CLIENT_PACKETS);
        this.config = config;
        this.reachCheck = reachCheck;
        this.losCheck = losCheck;
    }

    @Override
    public void onPacketReceiving(PacketEvent event) {
        if (!config.isFreecamEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || config.isExempt(player)) {
            return;
        }

        PacketContainer packet = event.getPacket();
        PacketType type = packet.getType();

        if (type == PacketType.Play.Client.USE_ITEM_ON || type == PacketType.Play.Client.BLOCK_DIG) {
            handleBlockInteraction(event, player, packet, type);
            return;
        }
        if (type == PacketType.Play.Client.USE_ENTITY) {
            handleEntityInteraction(event, player, packet);
        }
    }

    /**
     * Validates a block interaction.
     */
    private void handleBlockInteraction(PacketEvent event, Player player, PacketContainer packet, PacketType type) {
        BlockPosition position = extractPosition(packet, type);
        if (position == null) {
            return;
        }

        // Distance check first: it is the cheapest and catches the majority of freecam abuse.
        if (!reachCheck.checkBlock(player, position.getX(), position.getY(), position.getZ())) {
            event.setCancelled(true);
            return;
        }

        // Line of sight check: catches interactions that are close but behind a wall.
        if (!losCheck.checkBlock(player, position.getX(), position.getY(), position.getZ())) {
            event.setCancelled(true);
        }
    }

    /**
     * Validates an entity interaction.
     */
    private void handleEntityInteraction(PacketEvent event, Player player, PacketContainer packet) {
        Integer entityId = extractEntityId(packet);
        if (entityId == null) {
            return;
        }

        Entity target = resolveEntity(entityId);
        if (target == null) {
            return;
        }

        if (!reachCheck.checkEntity(player, target)) {
            event.setCancelled(true);
            return;
        }

        if (!losCheck.checkEntity(player, target)) {
            event.setCancelled(true);
        }
    }

    /**
     * Resolves an entity id back to a Bukkit entity.
     *
     * <p><b>Note:</b> this runs on a Netty thread, therefore it must not perform any world
     * iteration. The entity is resolved from the server's own entity lookup via the player's
     * world only when the interaction targets an entity the server already knows.</p>
     */
    private Entity resolveEntity(int entityId) {
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.getEntityId() == entityId) {
                return online;
            }
        }
        return null;
    }

    /**
     * Extracts the targeted block position from a packet.
     */
    static BlockPosition extractPosition(PacketContainer packet, PacketType type) {
        try {
            if (type == PacketType.Play.Client.USE_ITEM_ON) {
                if (packet.getBlockPositionModifier().size() == 0) {
                    return null;
                }
                return packet.getBlockPositionModifier().readSafely(0);
            }
            if (type == PacketType.Play.Client.BLOCK_DIG) {
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
     * Extracts the target entity id from an interaction packet.
     */
    static Integer extractEntityId(PacketContainer packet) {
        try {
            if (packet.getIntegers().size() == 0) {
                return null;
            }
            return packet.getIntegers().readSafely(0);
        } catch (Exception ex) {
            return null;
        }
    }
}
