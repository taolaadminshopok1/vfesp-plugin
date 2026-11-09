<div align="center">

# AntiESPFreecam

**Anti-ESP packet occlusion & anti-freecam enforcement for Paper / Purpur 1.20.2 – 1.21.x**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/Paper-1.20.2--1.21.x-blue.svg)](https://papermc.io/)
[![ProtocolLib](https://img.shields.io/badge/ProtocolLib-5.3.0-red.svg)](https://github.com/dmulloy2/ProtocolLib)

</div>

---

## What it does

Client-side ESP and freecam are **rendering** cheats. The server cannot stop a modified client
from drawing a box around an entity it knows the position of — it can only stop the client from
*learning* that position in the first place.

This plugin takes that approach literally:

| Vector | Mitigation |
| :--- | :--- |
| **ESP** (tracers, chams, glow, box ESP, radar) | Entities and containers the observer has no line of sight to are **removed from the client's world entirely**. No packet → nothing to render. |
| **Freecam** (camera detachment, ghost reach, wall phasing) | Every interaction is validated against the player's **authoritative server-side eye position**, so a detached camera cannot reach or phase through walls. |

---

## Anti-ESP engine

### The fundamental law

> If the client does not receive the packet, it is mathematically impossible for client-side ESP
> to render it.

Cancelling a spawn packet is *not* enough — the client keeps the entity in memory and keeps
rendering it. This plugin therefore maintains an explicit per-client visibility state machine:

```
UNKNOWN  ──spawn delivered──►  VISIBLE  ──hideEntity()──►  DESTROYED
   │                             │                          │
   │                             │                    showEntity()
   └──────────────► FILTERED ◄───┘                          │
                     │                                      │
                     └────────spawn resent──────────────────┘
```

* `hideEntity()` makes the server emit an explicit `ENTITY_DESTROY` and stop tracking that
  entity for that client.
* `showEntity()` re-registers it and emits a **complete** spawn packet — including metadata and
  equipment — so the entity never appears "naked" (no skin, no name), which would itself be a
  cheat tell.
* Paper's `showEntity` is a no-op unless `hideEntity` was called first, so the two calls are
  strictly paired. The plugin tracks that pairing explicitly and falls back to a hand-built
  spawn packet when the entity was never natively destroyed.

### Multi-point occlusion raytracing

For every observer/target pair, six independent rays are traced against the target's bounding
box:

```
Center  ·  Head  ·  Feet  ·  Top-Left  ·  Bottom-Right  ·  Front
```

The target is visible when **at least one** ray reaches it unobstructed. Ray traversal uses an
Amanatides–Woo voxel walk over an immutable block snapshot.

### Distance gates

| Range | Behaviour |
| :--- | :--- |
| `≤ 2.5 m` | **Always visible.** Preserves the legitimate "peek around a corner" advantage and avoids pop-in artefacts. |
| `2.5 – 48 m` | Occlusion raytracing decides. |
| `≥ 48 m` | **Always hidden.** Bounds CPU cost and defeats long-range radar ESP. |

### Threading model

No Bukkit API call ever happens off the main thread.

| Thread | Responsibility |
| :--- | :--- |
| **Main** | Refreshes the mirrored entity/observer state, captures block snapshots, applies visibility transitions. |
| **Raytrace workers** | Perform the occlusion maths against immutable snapshots. Pure computation, never touch Bukkit. |
| **Netty** | *Only* reads the pre-computed verdicts. Never computes, never calls Bukkit. |

Block snapshots are captured with Bukkit's immutable `ChunkSnapshot` and shared between all
observers within a tick, which avoids ~79,000 `World#getBlockAt` calls per player per tick.

---

## Anti-Freecam engine

A freecam cheat decouples the client's camera from the server-validated player hitbox. The body
stays where the server thinks it is while the camera roams through walls.

### Ghost reach

Interaction packets (`USE_ITEM_ON`, `USE_ENTITY`, `BLOCK_DIG`) are validated against the
**true server-side eye location** — never against anything the client claims.

| Game mode | Maximum reach |
| :--- | :--- |
| Survival | `4.5` blocks |
| Creative / Spectator | `5.2` blocks |

Exceeding it cancels the event, drops the packet and flags the player.

### Ghost phasing

Even within reach, the line of sight between the eye and the target is verified. A solid opaque
block on that line cancels the interaction — which prevents opening chests through locked doors
or walls.

### Scouting

Freecam players fly through walls; because the server-side body has no line of sight to the
objects it passes, the Anti-ESP engine above shows them pure void instead of players, chests or
ores.

---

## Requirements

* **Paper / Purpur** 1.20.2 – 1.21.x
* **Java 21**
* **ProtocolLib** 5.3.0

---

## Installation

1. Drop the jar into `plugins/`.
2. Install [ProtocolLib](https://github.com/dmulloy2/ProtocolLib) if it is not present yet.
3. Start the server. `config.yml` is generated on first run.

---

## Building from source

```bash
git clone https://github.com/taolaadminshopok1/vfesp-plugin.git
cd vfesp-plugin
mvn clean package
```

The plugin jar lands in `target/AntiESPFreecam-<version>.jar`.

---

## Configuration

<details>
<summary><b>config.yml</b> (click to expand)</summary>

```yaml
esp:
  enabled: true
  hide-players: true
  hide-mobs: true
  hide-containers: true
  always-visible-distance: 2.5
  always-hidden-distance: 48.0
  cache-ttl-millis: 100
  raytrace-threads: 2
  min-trace-interval-millis: 50
  send-explicit-destroy: true
  max-tracked-entities-per-observer: 512

freecam:
  enabled: true
  survival-reach: 4.5
  creative-reach: 5.2
  cancel-through-walls: true
  cancel-out-of-reach: true
  flag-on-violation: true
  violation-threshold: 3
  alert-staff: true

exempt:
  players: []
  permissions:
    - antiespfreecam.bypass
  blocked-worlds: []
```

</details>

### Notes on the tuning knobs

* **`always-hidden-distance`** is a hard gate, not a tendency. Entities beyond it are hidden
  from *every* observer. Raise it to 64–80 if a 48 m hard cutoff feels too abrupt for players
  using a spyglass or elytra.
* **`send-explicit-destroy: false`** keeps the entity on the client but starves it of updates,
  so it cannot be tracked or drawn as ESP. It is a softer alternative to a full destroy, at the
  cost of a stale position remaining in memory.
* **`raytrace-threads`** only affects the worker pool. The main thread never raytraces.

---

## Permissions

| Permission | Default | Description |
| :--- | :--- | :--- |
| `antiespfreecam.admin` | `op` | Reload and inspect the configuration. |
| `antiespfreecam.alerts` | `op` | Receive alerts when a player triggers a freecam check. |
| `antiespfreecam.bypass` | `false` | Exempt a player from every check. |

---

## Project layout

```
com.bakicode.antiespfreecam
├── AntiESPFreecam.java               # Entry point, registration, lifecycle
├── config/
│   └── CheatConfig.java              # Reach limits, raytrace gates, cache TTL
├── esp/
│   ├── OcclusionEngine.java          # Multi-point raytracer + block snapshots
│   ├── VisibilityCache.java          # Thread-safe TTL cache
│   ├── PacketHider.java              # Outbound entity/container packet filtering
│   ├── RehydrationHandler.java       # Destroy / re-spawn state machine
│   └── VisibilityTracker.java        # Thread-safe mirror between all three threads
├── freecam/
│   ├── FreecamReachCheck.java        # Server-authoritative distance limiter
│   ├── FreecamLineOfSightCheck.java  # Solid-wall interaction blocker
│   └── FreecamPacketListener.java    # Inbound interaction packet listener
└── util/
    └── BoundingBoxSampler.java       # Six-point hitbox sampler
```

---

## License

Released under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the full text.

```
AntiESPFreecam
Copyright (C) 2026  bakicode

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
General Public License for more details.

You should have received a copy of the GNU General Public License along with this program. If
not, see <https://www.gnu.org/licenses/>.
```

---

## Disclaimer

This plugin is intended for **server administration on servers you own or are authorised to
administer**. It is not intended to be used to gain an advantage in competitive play on servers
you do not control, and it does not attempt to detect or circumvent any client-side software on
the player's machine — it removes the information such software would need, rather than
attempting to inspect the client.
