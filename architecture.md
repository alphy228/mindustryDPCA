# Architecture

## Runtime and distribution

This is a Mindustry v160.7 server plugin. `CoolPlugin` wires the gameplay services, database, UI sprite transport, and survival director. The plugin JAR contains code, not an embedded asset pack. Deploy `config/mods/coolplugin.jar` alongside the repository's `config/assets` and `config/alphystuff` files. Keep these directories when updating an existing server; preserve its player database.

## Player lifecycle and unit trees

`PlayerUnitSystem` owns player units, faction/tree choices, payload ownership, death cooldowns, and reconnect recovery. `UnitTree` describes the eleven native unit lines. `UnitTreeMenu` and `FactionJoinMenu` present the horizontal picker; unavailable lines remain visible but disabled. Account/session/database services persist next-game tree preferences and equipment per base unit type, shared by its painted variants. In-game carried items are volatile and are cleared on transfers/gameover, rather than becoming persistent equipment.

A disconnect retains the owned unit and equipment in the world. Survival records include connection state, living state, health fraction, and an effective position. Nested payload traversal resolves an embarked player's position to the outer holder instead of the stored unit coordinates. Choice corrections are restricted to the opening five minutes. Partial-party deaths unlock automatic deployment as the chosen tree's tier one after 40 seconds of simulation time. A total party wipe ends the round rather than waiting for respawn.

## Survival director

`NeoplasmDirector` owns one world-local clock, spawn queue, wave index, spawn-marker cache, and death-age history. World load reloads configuration and resets all transient state. Updates run only on the authoritative server during active, unpaused gameplay. Native automatic waves and core-loss gameover are disabled so the director controls the survival lifecycle.

Continuous enemies start at 60 seconds. The interval is:

```
7 / connectedPlayerCount^0.8 * healthReliefMultiplier
```

The relief multiplier ranges from 1 to 4. Living players contribute their missing-health fraction. Dead players initially contribute a half-weight zero-health sample, fading linearly to no contribution over 600 seconds. Spawn progress is integrated against the current interval, so damage and healing change the rate immediately. The first continuous enemy appears at the start threshold.

The first wave is at 220 seconds; later waves are 150 seconds apart. Each definition has one to three bosses, lower-tier swarms, and a random continuous pool. Swarms scale with connected player count. Boss effective health scales by `1 + 0.65 * extraPlayers`, in addition to the native boss status. Native status multipliers reduce incoming damage; raw health remains at raw max health to avoid double-scaling.

Spawn origins are selected randomly from vanilla `Blocks.spawn` overlays. Ground units use compatible terrain at the marker or within twelve tiles. Unsupported spawns are skipped with a warning, not relocated to arbitrary map positions. Maps without markers do not spawn enemies. Wave orders are drained at up to eight units per update.

Once players have deployed, no living survivors means immediate gameover while players remain connected. When the final player disconnects, the director waits 40 simulation seconds before ending the round, even if retained units survive. Enemy spawning pauses during that empty-server grace window. A reconnect cancels the empty-server timer. The normal Mindustry gameover event is emitted for the server's map-rotation handling.

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

The shipped configuration contains 25 progressively stronger definitions. Beyond the final definition, the last pool/boss roster repeats, with swarm counts and boss health increasing by the configured endless-growth factor. Boss counts remain capped by the definition. Timing, relief, player scaling, logout grace, and endless growth are editable in `settings`.

## Enemy AI

`NeoplasmAI` prioritizes the nearest targetable player or their payload holder. Nonplayer enemy units and buildings remain valid fallback targets. Ground pursuit uses native dynamic-goal control pathfinding and approaches passable tiles near solid holders. Ordinary units close to ram distance instead of maintaining a custom firing ring. Flying units with native circle-target behavior retain their native dive/turn movement. Native weapons retain their own targeting compatibility, aiming, firing, and projectile behavior. Missile controllers are not replaced.

## Items, research, and presentation

`PlayerItemSystem`, `WorldItemLogicManager`, station menus, and storage services retain shared inventory/equipment behavior. Visual attachment physics runs on clients rather than authoritative server physics. Research services handle faction-independent progression and tree-wide tier-one unlocking. `UiSpriteSync` distributes custom UI textures for reconnect-safe menus; external assets remain under `config/assets`.

`tools/RenderUnitTreeArt.java` runs the actual Mindustry SDL/OpenGL renderer. Its eleven card scenes simulate native unit updates, weapons, projectiles, abilities, and real build plans. It does not paint substitute projectile/effect illustrations or modify native weapon parameters to stage a shot. Final transparent cards are 300×350 PNGs under `config/assets/sprites/unit-trees`. `RenderUnitTreeMenu.java` renders actual `Core.scene` menu previews at three horizontal scroll positions.

## Verification

Build with Java 17 using `bash gradlew jar --no-daemon`. Run `tools/colouring-validation.sh` with `JAVA_HOME`, `MINDUSTRY_JAR`, and `SERVER_JAR` pointing to Java 17 and v160.7 binaries. The suite covers existing colouring/equipment/research behavior, tree persistence/loadouts, survival timing and scaling, nested payloads, respawn, logout/gameover, and AI targeting. Native artwork/menu tools require an OpenGL display (Xvfb with software Mesa is sufficient).

The combined delivery is a binary-capable git patch against master commit `41e17fe956403d0fcab320ecc7f5e4b8efb2ef46`. It includes source, external artwork, configuration, and this architecture document, without README edits or the compiled JAR. The asset-free JAR is delivered separately.

## Map protection

`BuildSystem` starts `MapProtection`. Faction spawn floors/overlays are cached at world load. Buildings covering any marked tile are removed on the next authoritative update; tile-change callbacks also schedule immediate safe cleanup after native multiblock linking finishes. This includes construction placeholders, payload-delivered structures, and multiblocks whose centre is outside the marker. Removal is unconditional, without explosions or salvage, so protected barriers cannot obstruct spawnpoints either.

Large shield projectors use a native `BaseShieldBuild` subclass that rejects damage, bullet hits, and kill calls. Targeting/destruction flags and power-consumer removal are mirrored in `config/assets/patches/map-protection.hjson` for clients. Server action filtering, builder-plan filtering, and synchronous cancellation of native deconstruction protect them from dismantling. They retain their native shield update/repulsion behavior at full efficiency without a power network. Explicit administrative tile replacement remains possible; spawn-floor cleanup takes priority over barrier protection.

Faction spawnpoints are dynamic: floor/overlay change events add and remove protected tiles throughout the match, including markers painted shortly after world load. Player deployment scans the current map on every attempt. During the opening ten simulation seconds, missing faction floors defer deployment and retry at the normal half-second scan cadence instead of immediately using a fallback. After that grace period, genuinely missing markers retain the existing core-site/centre fallback. Automatic deployment retries no longer use the old twenty-second wall-clock chat-reminder throttle.
