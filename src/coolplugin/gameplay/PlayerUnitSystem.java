package coolplugin.gameplay;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Time;
import coolplugin.database.AccountRecord;
import coolplugin.database.PlayerSessionManager;
import coolplugin.techtree.TechTreeService;
import mindustry.content.UnitTypes;
import mindustry.core.NetServer;
import mindustry.entities.Units;
import mindustry.game.EventType.PlayerConnectionConfirmed;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitControlEvent;
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.game.EventType.UnitDestroyEvent;
import mindustry.game.EventType.UnitUnloadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Payloadc;
import mindustry.gen.Player;
import mindustry.gen.Posc;
import mindustry.gen.Unit;
import mindustry.net.Administration.ActionType;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.Reconstructor.ReconstructorBuild;
import mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;

import static mindustry.Vars.*;

/**
 * Single-unit player lifecycle & faction spawn manager backed by {@link PlayerSessionManager} (`players.db`):
 * <ul>
 *   <li>On join (or via {@code /faction}), presents {@link FactionJoinMenu} (Sharded, Crux, grayed-out Neoplasm & Malis).</li>
 *   <li>Each faction persists its own current {@link UnitType} per account (Guest UUID or secured {@code login + password}).</li>
 *   <li>Spawns the player's saved faction unit at random {@code dp-<teamname>-spawnpoint} floor tile, or falls back to
 *       a core destruction site while logging a long poem about the mapper forgetting spawnpoints.</li>
 *   <li>Upgrading/swapping via {@link ReconstructorBuild}, {@link UnitFactoryBuild}, or {@link UnitAssemblerBuild}
 *       automatically updates that faction's saved {@link UnitType} in SQLite.</li>
 * </ul>
 */
public class PlayerUnitSystem {

    public float respawnUnlockSeconds = 20f;
    public float respawnOfferSeconds = 20f;
    public float exitReminderSeconds = 20f;
    public float cameraSnapSeconds = 0.5f;
    /** When true, getting killed resets that faction's saved unit back to {@code dagger} (disconnecting/switching factions preserves it). */
    public boolean resetUnitOnDeath = true;

    private static final int LOST_SCAN_THRESHOLD = 4;
    private static final float SCAN_INTERVAL_TICKS = 30f;

    private static @Nullable PlayerUnitSystem instance;

    private final BuildSystem buildSystem;
    private final @Nullable TechTreeService techTree;
    private final @Nullable PlayerSessionManager sessionManager;
    private final FactionJoinMenu factionMenu;

    /** uuid -> active faction in the current match/connection. */
    private final ObjectMap<String, FactionDef> chosenFactions = new ObjectMap<>();

    /** uuid -> currently owned single unit. */
    private final ObjectMap<String, Unit> owned = new ObjectMap<>();

    /** uuid -> current payload holder (Reconstructor, PayloadConveyor, carrier unit, etc.). */
    private final ObjectMap<String, Posc> holders = new ObjectMap<>();

    /** uuid -> UnitAssemblerBuild that consumed (`yeetPayload`) the player's unit while assembling an upgrade. */
    private final ObjectMap<String, UnitAssemblerBuild> assemblerHolders = new ObjectMap<>();

    /** Unit IDs freshly produced by a UnitFactory or UnitAssembler that a player may Ctrl+Click to swap into. */
    private final IntSet swappableProducedUnits = new IntSet();

    /** Players who currently have the initial join faction picker open. */
    private final ObjectSet<String> activeJoinMenus = new ObjectSet<>();

    /** uuid -> epoch millis when /respawn unlocks after unit death. */
    private final ObjectMap<String, Long> respawnAt = new ObjectMap<>();
    private final ObjectMap<String, Long> lastRespawnOffer = new ObjectMap<>();
    private final ObjectMap<String, Long> lastExitOffer = new ObjectMap<>();
    private final ObjectMap<String, Integer> lostScans = new ObjectMap<>();

    private final Seq<String> lostBuffer = new Seq<>();
    private final Interval timer = new Interval(1);
    private boolean started = false;

    public PlayerUnitSystem(BuildSystem buildSystem, @Nullable TechTreeService techTree) {
        this(buildSystem, techTree, null);
    }

    public PlayerUnitSystem(
        BuildSystem buildSystem,
        @Nullable TechTreeService techTree,
        @Nullable PlayerSessionManager sessionManager
    ) {
        this.buildSystem = buildSystem;
        this.techTree = techTree;
        this.sessionManager = sessionManager;
        this.factionMenu = new FactionJoinMenu(
            this::onFactionChosen,
            (player, res) -> onFactionMenuDismissed(player),
            this::resolveDisplayUnit
        );

        if (this.sessionManager != null) {
            this.sessionManager.setAccountSwitchListener(this::onPlayerAccountChanged);
        }
    }

    private void onFactionMenuDismissed(@Nullable Player player) {
        if (player == null) return;
        activeJoinMenus.remove(player.uuid());
        // Only force-reopen if the player has no faction yet (if they opened /faction mid-game and hit Esc, keep current faction)
        if (!hasChosenFaction(player)) {
            Time.run(15f, () -> {
                if (valid(player) && !hasChosenFaction(player)) {
                    openFactionMenuIfNeeded(player);
                }
            });
        }
    }

    public void start() {
        if (started) return;
        started = true;
        instance = this;
        factionMenu.register();

        if (netServer != null && netServer.admins != null) {
            netServer.admins.addActionFilter(action -> {
                if (action.player == null) return true;
                if (action.type == ActionType.respawn) {
                    return false;
                }
                if (action.type == ActionType.control) {
                    Unit current = owned.get(action.player.uuid());
                    if (action.unit != null && action.unit == current) return true;
                    return isValidFactoryOrAssemblerSwap(action.player, action.unit);
                }
                return true;
            });
        }

        Events.on(WorldLoadEvent.class, e -> {
            owned.clear();
            holders.clear();
            assemblerHolders.clear();
            swappableProducedUnits.clear();
            respawnAt.clear();
            lastRespawnOffer.clear();
            lastExitOffer.clear();
            lostScans.clear();
            activeJoinMenus.clear();
        });

        Events.on(PlayerConnectionConfirmed.class, e -> {
            if (e.player == null || e.player.con == null) return;
            String uuid = e.player.uuid();
            Core.app.post(() -> handleConnectionConfirmed(playerByUuid(uuid)));
        });

        Events.on(PlayerLeave.class, e -> {
            if (e.player == null) return;
            String uuid = e.player.uuid();
            activeJoinMenus.remove(uuid);
            // Faction can be changed on rejoin; live unit despawns cleanly WITHOUT wiping saved unitType in SQLite!
            chosenFactions.remove(uuid);
            respawnAt.remove(uuid);
            lastRespawnOffer.remove(uuid);
            lastExitOffer.remove(uuid);
            lostScans.remove(uuid);
            cleanupOwnedUnit(uuid);
        });

        Events.on(UnitDestroyEvent.class, e -> {
            if (e.unit != null) {
                swappableProducedUnits.remove(e.unit.id);
            }
            String uuid = ownerOf(e.unit);
            if (uuid == null) return;
            handleOwnedUnitLost(uuid);
        });

        Events.on(UnitControlEvent.class, e -> {
            if (e.player == null) return;
            String uuid = e.player.uuid();
            Unit ownedUnit = owned.get(uuid);

            if (e.unit != null && e.unit != ownedUnit) {
                if (isValidFactoryOrAssemblerSwap(e.player, e.unit)) {
                    performFactoryOrAssemblerSwap(e.player, e.unit);
                    return;
                }

                e.player.clearUnit();
                if (ownedUnit != null && !ownedUnit.dead() && ownedUnit.isValid()) {
                    e.player.unit(ownedUnit);
                }
            }
        });

        Events.on(UnitCreateEvent.class, this::handleUnitCreated);

        Events.on(UnitUnloadEvent.class, e -> {
            String uuid = ownerOf(e.unit);
            if (uuid == null) return;

            holders.remove(uuid);
            assemblerHolders.remove(uuid);
            lastExitOffer.remove(uuid);

            Player player = playerByUuid(uuid);
            if (player == null) return;

            Time.run(1f, () -> {
                if (player.con != null
                    && player.con.isConnected()
                    && e.unit.isValid()
                    && !e.unit.dead()) {
                    player.unit(e.unit);
                    snapCamera(player, e.unit);
                }
            });
        });

        Events.run(Trigger.update, () -> {
            if (state == null || !state.isGame() || state.isPaused()) return;

            enforceControlAndTrackPayloads();

            if (timer.get(0, SCAN_INTERVAL_TICKS)) {
                scanHolders();
                offerExitCommand();
                offerRespawnCommand();
            }
        });
    }

    public void registerCommands(CommandHandler handler) {
        handler.<Player>register(
            "faction",
            "Open the faction selection menu to choose or switch factions.",
            (args, player) -> requestOpenFactionMenu(player)
        );

        handler.<Player>register(
            "factions",
            "Open the faction selection menu to choose or switch factions.",
            (args, player) -> requestOpenFactionMenu(player)
        );

        handler.<Player>register(
            "respawn",
            "Respawn at your faction spawnpoint (unlocks 20 seconds after death).",
            (args, player) -> {
                FactionDef faction = chosenFactions.get(player.uuid());
                if (faction == null) {
                    openFactionMenuIfNeeded(player);
                    return;
                }

                Unit unit = owned.get(player.uuid());
                if (unit != null
                    && !unit.dead()
                    && (unit.isValid() || holders.containsKey(player.uuid()) || assemblerHolders.containsKey(player.uuid()))) {
                    player.sendMessage("[scarlet]You still have an active unit![]");
                    return;
                }

                Long at = respawnAt.get(player.uuid());
                if (at == null) {
                    player.sendMessage("[scarlet]Nothing to respawn from.[]");
                    return;
                }

                if (Time.millis() < at) {
                    int left = (int) Math.ceil((at - Time.millis()) / 1000.0);
                    player.sendMessage(
                        "[scarlet]/respawn unlocks in " + left + " second" + (left == 1 ? "" : "s") + ".[]"
                    );
                    return;
                }

                respawnAt.remove(player.uuid());
                lastRespawnOffer.remove(player.uuid());
                player.team(faction.team());
                spawnFor(player, faction, resolveStarterUnit(player, faction));
            }
        );

        handler.<Player>register(
            "exit",
            "Climb out of a payload block.",
            (args, player) -> exitPayload(player)
        );
    }

    public void requestOpenFactionMenu(Player player) {
        if (!valid(player)) return;
        Long cooldownUntil = respawnAt.get(player.uuid());
        if (cooldownUntil != null && Time.millis() < cooldownUntil) {
            int left = (int) Math.ceil((cooldownUntil - Time.millis()) / 1000.0);
            player.sendMessage("[scarlet]Wait " + left + "s for death cooldown before switching factions.[]");
            return;
        }
        factionMenu.open(player);
    }

    public FactionJoinMenu getFactionMenu() {
        return factionMenu;
    }

    public boolean hasChosenFaction(@Nullable Player player) {
        return player != null && chosenFactions.containsKey(player.uuid());
    }

    public @Nullable FactionDef getChosenFaction(@Nullable Player player) {
        return player == null ? null : chosenFactions.get(player.uuid());
    }

    public static @Nullable Unit ownedUnit(@Nullable Player player) {
        return instance == null || player == null ? null : instance.owned.get(player.uuid());
    }

    /**
     * Resolves the {@link UnitType} displayed in {@link FactionJoinMenu} for {@code (player, faction)},
     * returning {@code null} if the player has never started as {@code faction}.
     */
    public @Nullable UnitType resolveDisplayUnit(@Nullable Player player, @Nullable FactionDef faction) {
        if (player == null || faction == null) return null;
        if (chosenFactions.get(player.uuid()) == faction) {
            Unit live = owned.get(player.uuid());
            if (live != null && !live.dead() && live.type != null) {
                return live.type;
            }
        }
        if (sessionManager != null) {
            return sessionManager.resolveSavedUnitTypeOrNull(player, faction);
        }
        return null;
    }

    /** Resolves this player's saved {@link UnitType} for {@code faction} when spawning (defaulting to {@code dagger} on first start). */
    public UnitType resolveStarterUnit(Player player, FactionDef faction) {
        if (sessionManager != null && player != null && faction != null) {
            return sessionManager.resolveSavedUnitType(player, faction);
        }
        return UnitTypes.dagger;
    }

    private void persistUnitTypeForUuid(String uuid, @Nullable FactionDef faction, @Nullable UnitType unitType) {
        if (sessionManager == null || uuid == null || faction == null || unitType == null) return;
        sessionManager.saveUnitTypeByUuid(uuid, faction, unitType);
    }

    // ------------------------------------------------------------------ account login / logout hook

    private void onPlayerAccountChanged(Player player, AccountRecord newAccount) {
        if (!valid(player)) return;
        String uuid = player.uuid();

        if (newAccount.isGuest()) {
            // /logout: despawn active unit cleanly without altering the registered account's saved unitType, resync tech rules, then reopen faction menu
            cleanupOwnedUnit(uuid);
            chosenFactions.remove(uuid);
            if (sessionManager != null) {
                sessionManager.setActiveFaction(uuid, null);
            }
            respawnAt.remove(uuid);
            lastRespawnOffer.remove(uuid);
            lastExitOffer.remove(uuid);
            lostScans.remove(uuid);
            player.clearUnit();
            if (techTree != null) {
                techTree.syncPlayerRulesLive(player, null);
            }
            openFactionMenuIfNeeded(player);
            return;
        }

        // /login: immediately sync active faction + per-faction Tech Tree rules for the logged-in account!
        FactionDef currentFaction = chosenFactions.get(uuid);
        if (sessionManager != null) {
            sessionManager.setActiveFaction(uuid, currentFaction);
        }
        if (techTree != null) {
            techTree.syncPlayerRulesLive(player, null);
        }
        if (currentFaction == null) return;

        UnitType savedType = resolveStarterUnit(player, currentFaction);
        persistUnitTypeForUuid(uuid, currentFaction, savedType);
        Unit currentUnit = owned.get(uuid);
        if (currentUnit != null
            && !currentUnit.dead()
            && currentUnit.isValid()
            && currentUnit.type != savedType
            && currentUnit.health >= currentUnit.maxHealth * 0.99f) {
            float sx = currentUnit.x;
            float sy = currentUnit.y;
            cleanupOwnedUnit(uuid);
            Unit replacement = savedType.spawn(currentFaction.team(), sx, sy);
            Units.notifyUnitSpawn(replacement);
            owned.put(uuid, replacement);
            player.unit(replacement);
            snapCamera(player, replacement);
        }
    }

    // ------------------------------------------------------------------ join & faction selection

    private void handleConnectionConfirmed(@Nullable Player player) {
        if (!valid(player) || state.gameOver || world.isGenerating()) return;

        if (sessionManager != null) {
            sessionManager.getOrInitSession(player);
        }

        String uuid = player.uuid();
        FactionDef existingFaction = chosenFactions.get(uuid);

        if (existingFaction != null) {
            if (sessionManager != null) {
                sessionManager.setActiveFaction(uuid, existingFaction);
            }
            player.team(existingFaction.team());
            buildSystem.onPlayerWorldSynced(player);
            if (techTree != null) {
                techTree.syncPlayerRulesLive(player, null);
            }

            Unit unit = owned.get(uuid);
            if (unit != null && !unit.dead() && unit.isValid()) {
                activeJoinMenus.remove(uuid);
                player.unit(unit);
                snapCamera(player, unit);
                return;
            }
            if (unit != null && !unit.dead() && (holders.containsKey(uuid) || assemblerHolders.containsKey(uuid))) {
                activeJoinMenus.remove(uuid);
                return;
            }
            if (respawnAt.containsKey(uuid)) {
                activeJoinMenus.remove(uuid);
                player.clearUnit();
                return;
            }

            spawnFor(player, existingFaction, resolveStarterUnit(player, existingFaction));
            return;
        }

        player.clearUnit();
        openFactionMenuIfNeeded(player);
    }

    public void openFactionMenuIfNeeded(@Nullable Player player) {
        if (!valid(player) || hasChosenFaction(player)) return;
        String uuid = player.uuid();
        if (!activeJoinMenus.add(uuid)) return;
        factionMenu.open(player);
    }

    public void onFactionChosen(Player player, FactionDef faction) {
        if (!valid(player) || faction == null || !faction.available) return;
        String uuid = player.uuid();
        activeJoinMenus.remove(uuid);

        Long cooldownUntil = respawnAt.get(uuid);
        if (cooldownUntil != null && Time.millis() < cooldownUntil) {
            int left = (int) Math.ceil((cooldownUntil - Time.millis()) / 1000.0);
            player.sendMessage("[scarlet]Wait " + left + "s for death cooldown before switching factions.[]");
            return;
        }

        FactionDef previousFaction = chosenFactions.get(uuid);
        if (previousFaction == faction) {
            Unit existing = owned.get(uuid);
            if (existing != null
                && !existing.dead()
                && (existing.isValid() || holders.containsKey(uuid) || assemblerHolders.containsKey(uuid))) {
                if (existing.isValid()) {
                    player.unit(existing);
                }
                return;
            }
        } else if (previousFaction != null) {
            // Switching faction via /faction: despawn old faction's unit cleanly (preserving old faction's saved unitType in SQLite)
            cleanupOwnedUnit(uuid);
        }

        chosenFactions.put(uuid, faction);
        if (sessionManager != null) {
            sessionManager.setActiveFaction(uuid, faction);
        }
        respawnAt.remove(uuid);
        lastRespawnOffer.remove(uuid);

        player.team(faction.team());
        buildSystem.onPlayerWorldSynced(player);
        if (techTree != null) {
            techTree.syncPlayerRulesLive(player, null);
        }

        spawnFor(player, faction, resolveStarterUnit(player, faction));
    }

    // ------------------------------------------------------------------ spawning & mapper poem

    public void spawnFor(Player player, FactionDef faction, UnitType type) {
        if (!valid(player) || type == null) return;

        String uuid = player.uuid();
        Unit existing = owned.get(uuid);
        if (existing != null
            && !existing.dead()
            && (existing.isValid() || holders.containsKey(uuid) || assemblerHolders.containsKey(uuid))) {
            activeJoinMenus.remove(uuid);
            if (existing.isValid()) {
                player.unit(existing);
            }
            return;
        }

        player.team(faction.team());

        Tile spawnTile = findFactionSpawnTile(faction, type);
        if (spawnTile == null) {
            logMapperPoem(faction);
            spawnTile = buildSystem.pickFallbackCoreTile(faction.team());
        }
        if (spawnTile == null) {
            spawnTile = findEmergencyCenterTile(type);
        }

        if (spawnTile == null) {
            respawnAt.put(uuid, Time.millis());
            player.sendMessage("[scarlet]No valid spawn point found! Use [white]/respawn[] to try again.");
            return;
        }

        float wx = spawnTile.worldx() + Mathf.range(2f);
        float wy = spawnTile.worldy() + Mathf.range(2f);
        Unit unit = type.spawn(faction.team(), wx, wy);
        Units.notifyUnitSpawn(unit);

        owned.put(uuid, unit);
        holders.remove(uuid);
        assemblerHolders.remove(uuid);
        respawnAt.remove(uuid);
        lastRespawnOffer.remove(uuid);
        lastExitOffer.remove(uuid);
        lostScans.remove(uuid);
        activeJoinMenus.remove(uuid);

        persistUnitTypeForUuid(uuid, faction, type);

        player.unit(unit);
        snapCamera(player, unit);
    }

    public @Nullable Tile findFactionSpawnTile(FactionDef faction, UnitType type) {
        if (world == null || world.tiles == null || faction == null) return null;

        Seq<Tile> passable = new Seq<>();
        Seq<Tile> anyMatching = new Seq<>();

        for (Tile tile : world.tiles) {
            if (!faction.matchesSpawnTile(tile)) continue;
            anyMatching.add(tile);
            if (!tile.solid() && (type.flying || tile.floor() == null || !tile.floor().isLiquid)) {
                passable.add(tile);
            }
        }

        if (!passable.isEmpty()) return passable.random();
        if (!anyMatching.isEmpty()) return anyMatching.random();
        return null;
    }

    private @Nullable Tile findEmergencyCenterTile(UnitType type) {
        if (world == null || world.tiles == null) return null;
        int cx = world.width() / 2;
        int cy = world.height() / 2;
        int maxR = Math.max(world.width(), world.height()) / 2;
        for (int r = 0; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (Math.abs(dx) != r && Math.abs(dy) != r) continue;
                    Tile t = world.tile(cx + dx, cy + dy);
                    if (t != null && !t.solid() && (type.flying || t.floor() == null || !t.floor().isLiquid)) {
                        return t;
                    }
                }
            }
        }
        return world.tile(cx, cy);
    }

    private void logMapperPoem(FactionDef faction) {
        String floorName = faction.canonicalSpawnFloorName();
        String mapName = (state != null && state.map != null) ? state.map.plainName() : "Unnamed Abomination";
        Log.err(
            "\n================================================================================\n"
            + "  AN ELEGY FOR THE MAPPER'S BRAINCELLS  (Map: \"" + mapName + "\")\n"
            + "================================================================================\n"
            + "  O tragic architect of dirt and stone,\n"
            + "  Who sculpted cliffs and painted ores alone,\n"
            + "  You placed each boulder with artistic grace,\n"
            + "  Yet left NO SPAWNPOINT in this cursed place!\n"
            + "\n"
            + "  We searched the valleys, scanned the rivers wide,\n"
            + "  Checked every tile from edge to mountainside,\n"
            + "  For just ONE \"" + floorName + "\" upon the floor --\n"
            + "  Not two, not ten, just ONE! We asked no more!\n"
            + "\n"
            + "  Did amnesia strike you mid-design?\n"
            + "  Did spore-pods rot your cerebral spine?\n"
            + "  Did routers whisper in your sleepy ear:\n"
            + "  \"Who needs a spawnpoint? Players teleport by sheer\n"
            + "  force of imagination and despair\"?!\n"
            + "\n"
            + "  Behold the smoking crater of a Core,\n"
            + "  Where " + faction.title + " pilots dump onto the floor\n"
            + "  Like homeless daggers evicted in the rain,\n"
            + "  Because the mapper wouldn't use their brain!\n"
            + "\n"
            + "  Go open Editor -> paint \"" + floorName + "\" right now,\n"
            + "  Before the server weeps and takes a bow.\n"
            + "================================================================================"
        );
    }

    // ------------------------------------------------------------------ upgrades & swaps (Reconstructor / Factory / Assembler)

    private void handleUnitCreated(UnitCreateEvent e) {
        if (e.unit == null) return;

        // 1. Reconstructor upgrade
        if (e.spawner instanceof ReconstructorBuild reconBuild) {
            for (ObjectMap.Entry<String, Posc> entry : new ObjectMap.Entries<>(holders)) {
                if (entry.value != reconBuild) continue;

                String uuid = entry.key;
                Unit old = owned.get(uuid);
                if (old != null && !old.isValid() && !old.dead()) {
                    owned.put(uuid, e.unit);
                    holders.put(uuid, reconBuild);
                    lostScans.remove(uuid);
                    persistUnitTypeForUuid(uuid, chosenFactions.get(uuid), e.unit.type);
                }
                return;
            }
            return;
        }

        // 2. UnitAssembler upgrade/production
        if (e.spawner instanceof UnitAssemblerBuild asmBuild) {
            for (ObjectMap.Entry<String, UnitAssemblerBuild> entry : new ObjectMap.Entries<>(assemblerHolders)) {
                if (entry.value != asmBuild) continue;

                String uuid = entry.key;
                assemblerHolders.remove(uuid);
                holders.remove(uuid);
                lostScans.remove(uuid);
                lastExitOffer.remove(uuid);
                owned.put(uuid, e.unit);
                persistUnitTypeForUuid(uuid, chosenFactions.get(uuid), e.unit.type);

                Player player = playerByUuid(uuid);
                if (e.unit.isValid()) {
                    if (player != null) {
                        Time.run(1f, () -> {
                            if (valid(player) && e.unit.isValid() && !e.unit.dead()) {
                                player.unit(e.unit);
                                snapCamera(player, e.unit);
                            }
                        });
                    }
                } else {
                    Posc newHolder = findHolder(e.unit);
                    if (newHolder != null) {
                        holders.put(uuid, newHolder);
                    }
                }
                return;
            }

            swappableProducedUnits.add(e.unit.id);
            return;
        }

        // 3. UnitFactory production
        if (e.spawner instanceof UnitFactoryBuild) {
            swappableProducedUnits.add(e.unit.id);
        }
    }

    public boolean isValidFactoryOrAssemblerSwap(@Nullable Player player, @Nullable Unit targetUnit) {
        if (player == null || targetUnit == null || targetUnit.dead() || !targetUnit.isValid()) return false;
        if (!hasChosenFaction(player) || targetUnit.team != player.team()) return false;
        if (!swappableProducedUnits.contains(targetUnit.id)) return false;
        if (ownerOf(targetUnit) != null) return false;

        String uuid = player.uuid();
        Unit current = owned.get(uuid);
        if (current == null || current.dead()) return false;
        if (!current.isValid() && !holders.containsKey(uuid) && !assemblerHolders.containsKey(uuid)) return false;

        if (techTree != null && !techTree.isContentUnlocked(player, targetUnit.type)) {
            return false;
        }
        return true;
    }

    public boolean performFactoryOrAssemblerSwap(Player player, Unit newUnit) {
        if (!isValidFactoryOrAssemblerSwap(player, newUnit)) return false;

        String uuid = player.uuid();
        Unit oldUnit = owned.get(uuid);
        swappableProducedUnits.remove(newUnit.id);

        if (oldUnit != null
            && oldUnit.stack != null
            && oldUnit.stack.amount > 0
            && oldUnit.stack.item != null
            && newUnit.stack != null
            && newUnit.stack.amount == 0
            && newUnit.acceptsItem(oldUnit.stack.item)) {
            int moved = Math.min(oldUnit.stack.amount, newUnit.itemCapacity());
            if (moved > 0) {
                newUnit.stack.item = oldUnit.stack.item;
                newUnit.stack.amount = moved;
                oldUnit.stack.amount -= moved;
            }
        }

        removeUnitFromWorldOrPayload(uuid, oldUnit);

        holders.remove(uuid);
        assemblerHolders.remove(uuid);
        lostScans.remove(uuid);
        lastExitOffer.remove(uuid);
        owned.put(uuid, newUnit);
        persistUnitTypeForUuid(uuid, chosenFactions.get(uuid), newUnit.type);

        if (player.unit() != newUnit) {
            player.unit(newUnit);
        }
        buildSystem.syncClientCoreNow(player);
        return true;
    }

    private void cleanupOwnedUnit(String uuid) {
        Unit old = owned.remove(uuid);
        if (old != null) {
            removeUnitFromWorldOrPayload(uuid, old);
        } else {
            holders.remove(uuid);
            assemblerHolders.remove(uuid);
        }
    }

    private void removeUnitFromWorldOrPayload(String uuid, @Nullable Unit unit) {
        Posc holder = holders.remove(uuid);
        assemblerHolders.remove(uuid);
        if (unit == null) return;

        if (holder instanceof Building building && building.isValid()) {
            Payload payload = building.getPayload();
            if (payload instanceof UnitPayload up && up.unit == unit) {
                building.takePayload();
            } else if (building instanceof PayloadConveyorBuild conv
                && conv.item instanceof UnitPayload up && up.unit == unit) {
                conv.item = null;
            }
            syncPayloadHolder(building);
        } else if (holder instanceof Payloadc carrier) {
            carrier.payloads().remove(p -> p instanceof UnitPayload up && up.unit == unit);
        }

        if (!unit.dead() && unit.isAdded()) {
            Call.unitDespawn(unit);
        } else {
            unit.remove();
        }
    }

    // ------------------------------------------------------------------ death & periodic upkeep

    private void handleOwnedUnitLost(String uuid) {
        owned.remove(uuid);
        holders.remove(uuid);
        assemblerHolders.remove(uuid);
        lostScans.remove(uuid);
        lastExitOffer.remove(uuid);
        lastRespawnOffer.remove(uuid);
        activeJoinMenus.remove(uuid);

        if (resetUnitOnDeath) {
            persistUnitTypeForUuid(uuid, chosenFactions.get(uuid), UnitTypes.dagger);
        }

        respawnAt.put(uuid, Time.millis() + (long) (respawnUnlockSeconds * 1000f));

        Player player = playerByUuid(uuid);
        if (player != null) {
            player.sendMessage(
                "[scarlet]You died![] [accent]/respawn[] unlocks in " + (int) respawnUnlockSeconds + " seconds."
            );
            player.clearUnit();
        }
    }

    private void enforceControlAndTrackPayloads() {
        for (Player player : Groups.player) {
            if (player.con == null || !player.con.isConnected()) continue;

            String uuid = player.uuid();
            if (!chosenFactions.containsKey(uuid)) {
                if (player.unit() != null) {
                    player.clearUnit();
                }
                continue;
            }

            FactionDef faction = chosenFactions.get(uuid);
            if (faction != null && player.team() != faction.team()) {
                player.team(faction.team());
            }

            Unit ownedUnit = owned.get(uuid);
            if (ownedUnit == null || ownedUnit.dead()) {
                if (player.unit() != null) {
                    player.clearUnit();
                }
                continue;
            }

            if (!ownedUnit.isValid()) {
                if (!assemblerHolders.containsKey(uuid)) {
                    Posc immediateHolder = findHolder(ownedUnit);
                    if (immediateHolder instanceof UnitAssemblerBuild asm) {
                        assemblerHolders.put(uuid, asm);
                        holders.remove(uuid);
                    } else if (immediateHolder != null) {
                        holders.put(uuid, immediateHolder);
                    }
                }
                if (player.unit() != null && player.unit() != ownedUnit) {
                    player.clearUnit();
                }
                continue;
            }

            if (player.unit() != ownedUnit) {
                player.unit(ownedUnit);
            }
        }
    }

    private void scanHolders() {
        lostBuffer.clear();

        for (ObjectMap.Entry<String, Unit> entry : owned) {
            String uuid = entry.key;
            Unit unit = entry.value;

            if (unit == null) {
                lostBuffer.add(uuid);
                continue;
            }
            if (unit.dead()) continue;

            if (unit.isValid()) {
                holders.remove(uuid);
                assemblerHolders.remove(uuid);
                lostScans.remove(uuid);
                continue;
            }

            UnitAssemblerBuild trackedAsm = assemblerHolders.get(uuid);
            if (trackedAsm != null) {
                if (trackedAsm.isValid()) {
                    lostScans.remove(uuid);
                    continue;
                } else {
                    assemblerHolders.remove(uuid);
                    lostBuffer.add(uuid);
                    continue;
                }
            }

            Posc holder = findHolder(unit);
            if (holder instanceof UnitAssemblerBuild asm) {
                assemblerHolders.put(uuid, asm);
                holders.remove(uuid);
                lostScans.remove(uuid);
            } else if (holder != null) {
                holders.put(uuid, holder);
                lostScans.remove(uuid);
            } else {
                Posc previousHolder = holders.get(uuid);
                UnitAssemblerBuild adjAsm = findAdjacentConsumingAssembler(previousHolder, unit.type);
                if (adjAsm != null) {
                    assemblerHolders.put(uuid, adjAsm);
                    holders.remove(uuid);
                    lostScans.remove(uuid);
                    continue;
                }

                int misses = lostScans.get(uuid, 0) + 1;
                lostScans.put(uuid, misses);
                if (misses >= LOST_SCAN_THRESHOLD) {
                    lostBuffer.add(uuid);
                }
            }
        }

        for (String uuid : lostBuffer) {
            handleOwnedUnitLost(uuid);
        }
    }

    private @Nullable UnitAssemblerBuild findAdjacentConsumingAssembler(@Nullable Posc previousHolder, UnitType unitType) {
        if (!(previousHolder instanceof Building b)) return null;
        for (int i = 0; i < 4; i++) {
            Building near = b.nearby(i);
            if (near instanceof UnitAssemblerBuild asm && asm.isValid() && asm.blocks.get(unitType) > 0) {
                return asm;
            }
        }
        return null;
    }

    public @Nullable Posc findHolder(@Nullable Unit unit) {
        if (unit == null) return null;

        for (Building building : Groups.build) {
            Payload payload = building.getPayload();
            if (payload == null && building instanceof PayloadConveyorBuild conveyor) {
                payload = conveyor.item;
            }
            if (payload instanceof UnitPayload unitPayload && unitPayload.unit == unit) {
                return building;
            }
        }

        for (Unit carrierUnit : Groups.unit) {
            if (carrierUnit instanceof Payloadc payloadCarrier
                && payloadCarrier.payloads().contains(p -> p instanceof UnitPayload up && up.unit == unit)) {
                return carrierUnit;
            }
        }

        return null;
    }

    private void offerExitCommand() {
        for (Player player : Groups.player) {
            if (player.con == null || !player.con.isConnected()) continue;

            String uuid = player.uuid();
            Unit unit = owned.get(uuid);
            if (unit == null || unit.dead() || unit.isValid()) continue;
            if (assemblerHolders.containsKey(uuid)) continue;

            Posc holder = holders.get(uuid);
            if (!(holder instanceof Building building) || !building.isValid()) continue;

            long now = Time.millis();
            if (now - lastExitOffer.get(uuid, 0L) >= (long) (exitReminderSeconds * 1000f)) {
                lastExitOffer.put(uuid, now);
                player.sendMessage(
                    "[accent]You are inside [white]" + building.block.name + "[accent]. Type [white]/exit[] to climb out."
                );
            }
        }
    }

    private void offerRespawnCommand() {
        for (Player player : Groups.player) {
            if (player.con == null || !player.con.isConnected()) continue;
            if (!hasChosenFaction(player)) continue;

            String uuid = player.uuid();
            Long at = respawnAt.get(uuid);
            if (at == null || Time.millis() < at || !needsUnit(player)) continue;

            long now = Time.millis();
            if (now - lastRespawnOffer.get(uuid, 0L) >= (long) (respawnOfferSeconds * 1000f)) {
                lastRespawnOffer.put(uuid, now);
                player.sendMessage("[accent]Type [white]/respawn[] to deploy again.");
            }
        }
    }

    private void exitPayload(Player player) {
        String uuid = player.uuid();
        Unit unit = owned.get(uuid);

        if (unit == null || unit.dead() || unit.isValid()) {
            player.sendMessage("[scarlet]Your unit is not inside a payload block.");
            return;
        }

        if (assemblerHolders.containsKey(uuid)) {
            player.sendMessage("[scarlet]Your unit is already being assembled into an upgrade!");
            return;
        }

        Posc holder = findHolder(unit);
        if (!(holder instanceof Building build) || !build.isValid()) {
            player.sendMessage("[scarlet]Failed to exit. Try again.");
            return;
        }

        Payload taken = build.takePayload();
        if (!(taken instanceof UnitPayload unitPayload) || unitPayload.unit != unit) {
            if (taken != null) {
                build.handlePayload(build, taken);
                syncPayloadHolder(build);
            }
            player.sendMessage("[scarlet]Failed to exit. Try again.");
            return;
        }

        if (tryDump(build, unitPayload)) {
            syncPayloadHolder(build);
            holders.remove(uuid);
            lastExitOffer.remove(uuid);
            player.sendMessage("[accent]You climbed out!");
        } else {
            build.handlePayload(build, unitPayload);
            syncPayloadHolder(build);
            player.sendMessage("[scarlet]No space to exit here![] Try again later.");
        }
    }

    private boolean tryDump(Building build, UnitPayload payload) {
        float edge = build.block.size * tilesize / 2f + payload.unit.hitSize / 2f + 2f;
        float[] offsets = {
            0f, 0f,
            edge, 0f,
            -edge, 0f,
            0f, edge,
            0f, -edge
        };

        for (int i = 0; i < offsets.length; i += 2) {
            payload.set(build.x + offsets[i], build.y + offsets[i + 1], payload.rotation());
            if (payload.dump()) {
                return true;
            }
        }
        return false;
    }

    private void syncPayloadHolder(Building build) {
        if (build == null || !build.isValid() || net == null || !net.server()) return;
        syncOne(build);
        for (int i = 0; i < 4; i++) {
            Building near = build.nearby(i);
            if (near instanceof PayloadConveyorBuild) {
                syncOne(near);
            }
        }
    }

    private void syncOne(Building building) {
        if (building == null || !building.isValid() || net == null || !net.server()) return;
        NetServer.syncBuilding(building);
        Time.run(2f, () -> {
            if (building.isValid() && net != null && net.server()) {
                NetServer.syncBuilding(building);
            }
        });
    }

    public static @Nullable Vec2 effectivePosition(Player player, Vec2 out) {
        if (player == null) return null;
        if (!player.dead() && player.unit() != null && player.unit().isValid()) {
            return out.set(player.unit().x, player.unit().y);
        }
        if (instance != null) {
            String uuid = player.uuid();
            UnitAssemblerBuild asm = instance.assemblerHolders.get(uuid);
            if (asm != null && asm.isValid()) {
                return out.set(asm.x, asm.y);
            }
            Posc holder = instance.holders.get(uuid);
            if (holder instanceof Building b && b.isValid()) {
                return out.set(b.x, b.y);
            }
            if (holder instanceof Unit u && u.isValid()) {
                return out.set(u.x, u.y);
            }
        }
        return null;
    }

    private boolean needsUnit(Player player) {
        Unit unit = owned.get(player.uuid());
        return unit == null || unit.dead();
    }

    private boolean valid(@Nullable Player player) {
        return player != null
            && player.con != null
            && player.con.isConnected()
            && state != null
            && state.isGame();
    }

    private @Nullable String ownerOf(@Nullable Unit unit) {
        if (unit == null) return null;
        for (ObjectMap.Entry<String, Unit> entry : owned) {
            if (entry.value == unit) return entry.key;
        }
        return null;
    }

    private @Nullable Player playerByUuid(String uuid) {
        return Groups.player.find(p -> uuid.equals(p.uuid()));
    }

    public void snapCamera(Player player, Unit unit) {
        int sends = Math.max(1, (int) (cameraSnapSeconds * 60f / 3f));
        for (int i = 0; i <= sends; i++) {
            Time.run(i * 3f, () -> {
                if (player.con != null && player.con.isConnected() && unit.isValid()) {
                    Call.setCameraPosition(player.con, unit.x, unit.y);
                }
            });
        }
    }
}
