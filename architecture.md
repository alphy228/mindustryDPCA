# Architecture

## Runtime and distribution

This is a Mindustry v160.7 server plugin. `CoolPlugin` wires the gameplay services, database, UI sprite transport, and survival director. The plugin JAR contains code, not an embedded asset pack. Deploy `config/mods/coolplugin.jar` alongside the repository's `config/assets` and `config/alphystuff` files. Keep these directories when updating an existing server; preserve its player database.

## Player lifecycle and unit trees

`PlayerUnitSystem` owns player units, faction/tree choices, payload ownership, death cooldowns, and reconnect recovery. `UnitTree` describes the eleven native unit lines. `UnitTreeMenu` and `FactionJoinMenu` present the horizontal picker; unavailable lines remain visible but disabled. Account/session/database services persist next-game tree preferences and equipment per base unit type, shared by its painted variants. In-game carried items are volatile and are cleared on transfers/gameover, rather than becoming persistent equipment.

A disconnect retains the owned unit and equipment in the world. Survival records include connection state, living state, health fraction, and an effective position. Nested payload traversal resolves an embarked player's position to the outer holder instead of the stored unit coordinates. Choice corrections are restricted to the opening five minutes. Before 50 minutes, deaths unlock automatic deployment as the chosen branch's tier one after 40 seconds of simulation time, including a total-party wipe. After the barrier-collapse threshold, death is permanent until the next map; previously deployed players cannot bypass elimination with `/respawn` or reconnect.

## Survival director

`NeoplasmDirector` owns one world-local clock, spawn queue, wave index, spawn-marker cache, and death-age history. World load reloads configuration and resets all transient state. Updates run only on the authoritative server during active, unpaused gameplay. Native automatic waves and core-loss gameover are disabled so the director controls the survival lifecycle.

Continuous enemies start at 60 seconds. The interval is:

```
7 / connectedPlayerCount^0.8 * healthReliefMultiplier
```

The relief multiplier ranges from 1 to 4. Living players contribute their missing-health fraction. Dead players initially contribute a half-weight zero-health sample, fading linearly to no contribution over 600 seconds. Spawn progress is integrated against the current interval, so damage and healing change the rate immediately. The first continuous enemy appears at the start threshold.

The first wave is at 220 seconds; later waves are 150 seconds apart. Each definition has one to three bosses, lower-tier swarms, and a random continuous pool. Swarms scale with connected player count. Boss effective health scales by `1 + 0.65 * extraPlayers`, in addition to the native boss status. Native status multipliers reduce incoming damage; raw health remains at raw max health to avoid double-scaling.

Spawn origins are selected randomly from vanilla `Blocks.spawn` overlays. Ground units use compatible terrain at the marker or within twelve tiles. Unsupported spawns are skipped with a warning, not relocated to arbitrary map positions. Maps without markers do not spawn enemies. Wave orders are drained at up to eight units per update.

Automatic survival gameover is removed: neither a party wipe nor an empty server ends the map. Enemy spawning pauses when no players are connected, while the gameplay clock follows the server's normal pause state. Administrative map changes remain available.

## Editable waves

`config/alphystuff/waves.json` is strict JSON, read on world load. `NeoplasmWaveConfig` parses and validates settings, consecutive `wave1`, `wave2`, … definitions, and unit identifiers. Invalid configuration is logged and falls back to built-in defaults for that world.

Each wave contains:

```json
{
  "bosses": [{"unit": "mace", "count": 1}],
  "swarm": [{"unit": "dagger", "base": 3, "perExtraPlayer": 2}],
  "continuous": ["dagger", "crawler"]
}
```

The shipped configuration contains 25 progressively stronger definitions. Beyond the final definition, the last pool/boss roster repeats, with swarm counts and boss health increasing by the configured endless-growth factor. Boss counts remain capped by the definition. Timing, relief, player scaling, and endless growth are editable in `settings`. The legacy empty-server-grace setting remains readable for config compatibility but no longer ends the match.

## Enemy AI

`NeoplasmAI` prioritizes the nearest targetable player or their payload holder. When no player is in a weapon's range, targetable enemy buildings in range take priority; nonplayer enemy units remain fallback targets. Ground pursuit uses native dynamic-goal control pathfinding and approaches passable tiles near solid holders. Ordinary units close to ram distance instead of maintaining a custom firing ring. Flying units with native circle-target behavior retain their native dive/turn movement. Native weapons retain their own targeting compatibility, aiming, firing, and projectile behavior. Missile controllers are not replaced.

## Items, research, and presentation

`PlayerItemSystem`, `WorldItemLogicManager`, station menus, and storage services retain shared inventory/equipment behavior. Visual attachment physics runs on clients rather than authoritative server physics. Research services retain separate saved progress scopes and T1 branch-wide unit unlocking. `MixedTechTree` migrates legacy trees once (`mixedVersion: 1`), interleaving native building prerequisites, produced resources, station equipment, generated paint cans, and the five available T1 branches. All usable buildings require research unless explicitly whitelisted; map-authored bans still take precedence. Existing IDs, prices, conditions, partial payments, and saved unlocks are retained for matching content. Previously researched content remains usable without repurchasing newly inserted ancestors, and its parent path stays visible. World-load expansion covers late DataPatch content; catalogue reloads register generated equipment research. No separate T2–T5 unit research nodes are added. Ground-drop count labels sit 3.5 world units below the icon centre for a minimal gap. `UiSpriteSync` distributes custom UI textures for reconnect-safe menus; external assets remain under `config/assets`.

`tools/RenderUnitTreeArt.java` runs the actual Mindustry SDL/OpenGL renderer. Its eleven card scenes simulate native unit updates, weapons, projectiles, abilities, and real build plans. It does not paint substitute projectile/effect illustrations or modify native weapon parameters to stage a shot. Final transparent cards are 300×350 PNGs under `config/assets/sprites/unit-trees`. `RenderUnitTreeMenu.java` renders actual `Core.scene` menu previews at three horizontal scroll positions.

## Verification

Build with Java 17 using `bash gradlew jar --no-daemon`. Run `tools/colouring-validation.sh` with `JAVA_HOME`, `MINDUSTRY_JAR`, and `SERVER_JAR` pointing to Java 17 and v160.7 binaries. The suite covers existing colouring/equipment/research behavior, tree persistence/loadouts, survival timing and scaling, nested payloads, respawn, logout/gameover, and AI targeting. Native artwork/menu tools require an OpenGL display (Xvfb with software Mesa is sufficient).

The combined delivery is a binary-capable git patch against master commit `41e17fe956403d0fcab320ecc7f5e4b8efb2ef46`. It includes source, external artwork, configuration, and this architecture document, without README edits or the compiled JAR. The asset-free JAR is delivered separately.

## Map protection

`BuildSystem` starts `MapProtection`. Faction spawn floors/overlays are cached at world load. Buildings covering any marked tile are removed on the next authoritative update; tile-change callbacks also schedule immediate safe cleanup after native multiblock linking finishes. This includes construction placeholders, payload-delivered structures, and multiblocks whose centre is outside the marker. Removal is unconditional, without explosions or salvage, so protected barriers cannot obstruct spawnpoints either.

Large shield projectors use a native `BaseShieldBuild` subclass that rejects damage, bullet hits, and kill calls. Targeting/destruction flags and power-consumer removal are mirrored in `config/assets/patches/map-protection.hjson` for clients. Server action filtering, builder-plan filtering, and synchronous cancellation of native deconstruction protect them from dismantling. They retain their native shield update/repulsion behavior at full efficiency without a power network. Explicit administrative tile replacement remains possible; spawn-floor cleanup takes priority over barrier protection.

Faction spawnpoints are dynamic: floor/overlay change events add and remove protected tiles throughout the match, including markers painted shortly after world load. Player deployment scans the current map on every attempt. During the opening ten simulation seconds, missing faction floors defer deployment and retry at the normal half-second scan cadence instead of immediately using a fallback. After that grace period, genuinely missing markers retain the existing core-site/centre fallback. Automatic deployment retries no longer use the old twenty-second wall-clock chat-reminder throttle.


## Late-game escalation and shelter-weighted difficulty

`SurvivalSettings` loads `config/alphystuff/survival.json` on world load. Its defaults are:

| Gameplay time | Change |
| --- | --- |
| 50 minutes | Remove all small/large barrier projectors; permanently eliminate dead players; double fast/flying swarm groups and add a mobile air contingent. |
| 60 minutes | Radiation sickness stack 1. |
| 65 minutes | Stack 2. |
| 70 minutes | Stack 3. |
| 75 minutes | Burning on living player-owned units. |
| 80 minutes | Personal warned nukes. |

Each radiation stack is a separate native status with 1 damage/sec before native effective-health modifiers. Player-owned units inside payloads receive equivalent authoritative damage because payload units do not tick native statuses. Upstream radiation icons are copied unchanged, with attribution in `config/assets/RADIATION-CREDITS.txt`; none are embedded in the JAR.

Nukes maintain independent UUID-keyed cooldowns. The first warning appears at the 80-minute threshold; subsequent warnings start after 60, 58, 56, … seconds, bounded at 10 seconds. A strike targets the player's position at warning time with 20% probability; otherwise it picks a point uniformly by area within 100 tiles, clamped to map bounds. Five-second red blast-radius/countdown markers precede the impact, with a falling thorium item marker, a native reactor explosion, and reactor-like damage/radius (5000 / 19 tiles). Warnings are fixed targets, not unavoidable homing strikes. Map changes clear pending impacts and histories.

Wave scheduling remains every 150 seconds even when everyone is sheltered. Only the difficulty index changes. The exposed fraction `p` is living connected players' effective current HP outside active friendly large barriers divided by their total effective current HP. For `p < 0.5`, advance by 0 with probability `1 - 2p`, otherwise 1; above 0.5, advance by 2 with probability `2p - 1`, otherwise 1. Thus 0/50/100% exposed HP guarantees +0/+1/+2. At index zero, scheduled enemies use wave1. Barrier collapse makes surviving players fully exposed.

## Ground resource drops and interaction effects

`NeoplasmDrops` adapts the supplied CruxDrops table to Neoplasm deaths. Its eight tier columns, item probabilities, one-item weighted selection, four-times amount multiplier, and per-tier fluctuation bounds are retained; special unknown units do not drop resources. Unlike the supplied block implementation, drops stay at the death coordinates as an item texture plus a small outlined count with no text background. They place no blocks and have no terrain-search requirement. Uncollected drops last until pickup or world reset.

Within `4 * tilesize + unit.hitSize` world units, a connected living player's unit takes what fits in its compatible resource stack, then connected carried/world containers accept the remainder. World containers connect within native `Vars.buildingRange` (220 world units), replacing the old 15-tile limit. Only actually deposited quantities are removed from the drop. Full/incompatible inventories leave it untouched, and partial pickups retain the exact remainder. Item-coloured native transfer particles travel to the player, then to containers. Construction withdrawals use directional container-to-unit particles rather than stationary mining particles.

## Flying terrain correction

`FlyingTerrainGuard` uses the native darkness renderer's fully-black threshold (`World.getDarkness >= 3.5`), never leg-step passability. A cached exact Euclidean nearest-open exit field directs spawn-zone-like acceleration toward non-black terrain. The outward drift target grows smoothly from 18% to 35% of effective unit speed over two tiles. Inward resistance grows separately to full cancellation by two tiles. Depth-dependent bounded relaxation toward that target replaces unbounded additive impulses; repeated ticks cannot fling the unit away, and already-faster outward motion is not accelerated. Tangential motion is preserved. Ordinary correction never restores a previous position. The emergency six-tile backstop remains separate from ordinary incremental remote-player corrections; it retains momentum. All flying units, not just player-owned ones, are affected. Cached A* detours exclude fully-black tiles, with a shared maximum of two searches per update: Neoplasm pursuit supplies its real target, while other AI/command-controlled flyers use motion lookahead. Native combat targeting and circle attacks remain active on unobstructed paths. Terrain changes/world loads invalidate the exit field and routes.

The existing per-owner WorldProcessor receives short-lived bounded outward-speed targets for local-player prediction; it applies them at most once per tick, only on the owning client, and never on the server or a remote spectator. Coordinate correction is reserved for the six-tile emergency. Empty-equipment rigs retain the guard, and the established 250-global/10-per-owner/500-instruction limits remain unchanged. No server-side attachment physics was added.

`tools/disable-setprop.js` is a reversible diagnostic hook for existing and newly compiled logic processors. Load it in the testing client to disable that client's `setprop` instructions; server execution alone cannot disable client world logic. `setpropTest.enable()` restores the original instructions without editing source code or jump indices. The hook intentionally disables all `setprop` uses, including unrelated maps' logic.

Remote player movement is client-position-driven: velocity changes alone do not enforce the soft boundary. The server now applies incremental current-position corrections during ordinary pushback, cancelling the inward component according to penetration and adding only the bounded outward drift. These corrections are synchronized even when client logic is disabled, including zero-input/idle players. Non-player flyers use the same target without needing AI orders. The nearest-open field uses a linear-time Euclidean distance transform, avoiding diagonal normals on flat walls.
