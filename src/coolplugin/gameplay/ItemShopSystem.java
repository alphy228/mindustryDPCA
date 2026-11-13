package coolplugin.gameplay;

import arc.Events;
import arc.graphics.Color;
import arc.struct.IntIntMap;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Time;
import coolplugin.database.AccountRecord;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerSessionManager;
import coolplugin.gameplay.ShopItemDef.StationTier;
import coolplugin.techtree.TechTreeService;
import mindustry.game.EventType.ConfigEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.MapObjectives.ShapeMarker;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.logic.LMarkerControl;
import mindustry.world.Block;
import mindustry.world.blocks.logic.SwitchBlock.SwitchBuild;

import java.util.Locale;

import static mindustry.Vars.*;

/**
 * World interaction controller for Item Shop buildings ({@code dp-workbench}, {@code dp-bazaar}, {@code dp-cursed-altar}):
 * <ul>
 *   <li>Renders a steady, non-pulsing 5-tile ({@code 40f} world units) circular area around every placed shop building.</li>
 *   <li>When a player flips a shop's {@code SwitchBlock} while within its 5-tile circle, opens that station's menu
 *       and resets the switch cleanly; outside the circle, silently resets the switch without warning spam.</li>
 *   <li>Registers server console command {@code openmenu <player> [workbench|bazaar|altar|faction|tech]}
 *       to force-open any menu for an online player.</li>
 * </ul>
 */
public class ItemShopSystem {

    public static final float SHOP_RADIUS_TILES = 5f;
    public static final float SHOP_RADIUS_WORLD = SHOP_RADIUS_TILES * tilesize;

    private static final float CIRCLE_SYNC_INTERVAL_TICKS = 30f;
    private static final int CIRCLE_MARKER_BASE_ID = 4000;
    private static final int CIRCLE_MARKER_MAX_COUNT = 512;
    private static final int CIRCLE_SIDES = 56;

    private static final Color COL_WORKBENCH = Color.valueOf("ffd37f99");
    private static final Color COL_BAZAAR = Color.valueOf("7fe6ff99");
    private static final Color COL_CURSED = Color.valueOf("ff4b6e99");

    private final PlayerItemSystem itemSystem;
    private final BuildSystem buildSystem;
    private final PlayerUnitSystem playerUnitSystem;
    private final TechTreeService techTree;
    private final @Nullable LaunchPadSystem launchPadSystem;
    private final PlayerDatabase database;
    private final PlayerSessionManager sessionManager;
    private final ItemShopMenu shopMenu;
    private final Interval timer = new Interval(1);

    /** Building tile.pos() -> assigned MapMarkers ID for its steady 5-tile circle. */
    private final IntIntMap shopCircleMarkers = new IntIntMap();
    private final IntSet usedMarkerIds = new IntSet();
    private final IntSet seenShopPositions = new IntSet();
    private final Seq<Integer> stalePositions = new Seq<>();

    private boolean started = false;

    public ItemShopSystem(
        PlayerItemSystem itemSystem,
        BuildSystem buildSystem,
        PlayerUnitSystem playerUnitSystem,
        TechTreeService techTree,
        PlayerDatabase database,
        PlayerSessionManager sessionManager
    ) {
        this(itemSystem, buildSystem, playerUnitSystem, techTree, null, database, sessionManager);
    }

    public ItemShopSystem(
        PlayerItemSystem itemSystem,
        BuildSystem buildSystem,
        PlayerUnitSystem playerUnitSystem,
        TechTreeService techTree,
        @Nullable LaunchPadSystem launchPadSystem,
        PlayerDatabase database,
        PlayerSessionManager sessionManager
    ) {
        this.itemSystem = itemSystem;
        this.buildSystem = buildSystem;
        this.playerUnitSystem = playerUnitSystem;
        this.techTree = techTree;
        this.launchPadSystem = launchPadSystem;
        this.database = database;
        this.sessionManager = sessionManager;
        this.shopMenu = new ItemShopMenu(itemSystem, buildSystem, database, sessionManager);
    }

    public ItemShopMenu getShopMenu() {
        return shopMenu;
    }

    public void start() {
        if (started) return;
        started = true;
        shopMenu.register();

        Events.on(WorldLoadEvent.class, e -> {
            shopCircleMarkers.clear();
            usedMarkerIds.clear();
        });
        Events.on(ConfigEvent.class, this::handleSwitchFlip);
        Events.run(Trigger.update, this::updateCircleMarkers);
    }

    public void registerServerCommands(CommandHandler handler) {
        handler.register(
            "openmenu",
            "<player> [workbench|bazaar|altar|faction|tech]",
            "Force-open an Item Shop, Faction, or Tech Tree menu for an online player.",
            args -> {
                String query = args[0];
                String menuKey = args.length > 1 ? args[1].trim().toLowerCase(Locale.ROOT) : "workbench";

                Player target = findOnlinePlayer(query);
                if (target == null) {
                    Log.warn("[CoolPlugin] openmenu: No connected player matched '@'.", query);
                    return;
                }

                if (forceOpenMenuForPlayer(target, menuKey)) {
                    Log.info("[CoolPlugin] Force-opened menu '@' for player '@'.", menuKey, target.plainName());
                } else {
                    Log.warn(
                        "[CoolPlugin] Unknown menu '@'. Valid options: workbench, bazaar, altar, faction, tech.",
                        menuKey
                    );
                }
            }
        );

        handler.register(
            "wipedata",
            "<player>",
            "Wipes all persistent & live gameplay data for a player or account.",
            args -> wipePlayerData(args[0])
        );

        handler.register(
            "infitems",
            "<player>",
            "Places an overfilled 'infinite items' container (2147483647 of every item) near a player.",
            args -> placeInfiniteItemsNear(args[0])
        );

        handler.register(
            "infiniteitems",
            "<player>",
            "Alias for infitems: places an overfilled container (2147483647 of every item) near a player.",
            args -> placeInfiniteItemsNear(args[0])
        );
    }

    public boolean wipePlayerData(String query) {
        if (query == null || query.isBlank()) {
            Log.warn("[CoolPlugin] Usage: wipedata <playerOrLoginOrUuid>");
            return false;
        }

        Player online = findOnlinePlayer(query);
        AccountRecord targetAccount;

        if (online != null) {
            targetAccount = sessionManager.getOrInitSession(online);
        } else {
            targetAccount = database.findByLoginOrUuid(query.trim());
            if (targetAccount != null && Groups.player != null) {
                long targetId = targetAccount.id;
                for (Player p : Groups.player) {
                    if (p == null || p.con == null || !p.con.isConnected()) continue;
                    AccountRecord active = sessionManager.getActiveSession(p.uuid());
                    if (active != null && active.id == targetId) {
                        online = p;
                        break;
                    }
                }
            }
        }

        if (targetAccount == null) {
            Log.warn("[CoolPlugin] wipedata: No online player or saved account matched '@'.", query);
            return false;
        }

        if (!database.wipeAccountData(targetAccount.id)) {
            Log.warn("[CoolPlugin] wipedata: Database wipe failed for account #@.", targetAccount.id);
            return false;
        }

        if (launchPadSystem != null) {
            launchPadSystem.onAccountWiped(targetAccount.id);
        }
        itemSystem.onPlayerDataWiped(online);
        playerUnitSystem.onPlayerDataWiped(online);
        techTree.onAccountWiped(targetAccount.id, online);
        shopMenu.refreshOpenSession(online);

        if (online != null) {
            online.sendMessage("[scarlet]Your player data has been wiped by the server.[]");
        }

        String label = targetAccount.login != null
            ? targetAccount.login
            : (targetAccount.lastName != null ? targetAccount.lastName : ("#" + targetAccount.id));
        Log.info(
            "[CoolPlugin] Wiped all data for account #@ ('@', online=@).",
            targetAccount.id, label, online != null
        );
        return true;
    }

    public @Nullable Building placeInfiniteItemsNear(String query) {
        Player target = findOnlinePlayer(query);
        if (target == null) {
            Log.warn("[CoolPlugin] infitems: No connected player matched '@'.", query);
            return null;
        }
        Building placed = buildSystem.placeInfiniteItemsContainerNear(target);
        if (placed == null) {
            Log.warn("[CoolPlugin] infitems: Could not find a valid tile near '@'.", target.plainName());
            return null;
        }
        target.sendMessage(
            "[accent]An overfilled Infinite Items container (" + Integer.MAX_VALUE
                + " of every item) was placed near you at (" + placed.tileX() + ", " + placed.tileY() + ").[]"
        );
        Log.info(
            "[CoolPlugin] Placed Infinite Items container (@ of every item) near '@' at (@, @).",
            Integer.MAX_VALUE, target.plainName(), placed.tileX(), placed.tileY()
        );
        return placed;
    }

    public boolean forceOpenMenuForPlayer(Player player, String menuKey) {
        if (player == null) return false;
        String norm = (menuKey == null || menuKey.isBlank()) ? "workbench" : menuKey.trim().toLowerCase(Locale.ROOT);
        StationTier station = StationTier.byIdOrBlock(norm);
        if (station != null) {
            shopMenu.open(player, station);
            return true;
        }
        return switch (norm) {
            case "faction", "menu", "start" -> {
                playerUnitSystem.requestOpenFactionMenu(player);
                yield true;
            }
            case "tech", "tree", "t" -> {
                techTree.getMenu().open(player);
                yield true;
            }
            default -> false;
        };
    }

    public static @Nullable StationTier resolveShopTier(@Nullable Block block) {
        if (block == null || block.name == null) return null;
        return switch (block.name) {
            case "dp-workbench", "workbench" -> StationTier.WORKBENCH;
            case "dp-bazaar", "bazaar" -> StationTier.BAZAAR;
            case "dp-cursed-altar", "cursed-altar" -> StationTier.CURSED_ALTAR;
            default -> null;
        };
    }

    public static boolean isWithinShopArea(Player player, Building shopBuilding) {
        if (player == null || shopBuilding == null) return false;
        Unit u = PlayerUnitSystem.ownedUnit(player);
        if (u == null) u = player.unit();
        if (u == null || u.dead() || !u.isValid()) return false;
        return u.within(shopBuilding.x, shopBuilding.y, SHOP_RADIUS_WORLD);
    }

    /** Public test & runtime hook processing a player's switch flip on a shop building. */
    public void handleShopBuildingToggle(Player player, Building shopBuild) {
        if (player == null || shopBuild == null) return;
        StationTier tier = resolveShopTier(shopBuild.block);
        if (tier == null) return;

        if (!isWithinShopArea(player, shopBuild)) {
            // Silently reset switch without annoying player chat warning!
            resetSwitchState(shopBuild);
            return;
        }

        // Open the station's menu and spring the switch back to OFF after a brief glow flash
        shopMenu.open(player, tier);
        Time.run(10f, () -> {
            if (shopBuild.isValid()) {
                resetSwitchState(shopBuild);
            }
        });
    }

    private void handleSwitchFlip(ConfigEvent event) {
        if (event == null || event.player == null || event.tile == null) return;
        handleShopBuildingToggle(event.player, event.tile);
    }

    private static void resetSwitchState(Building shopBuild) {
        if (shopBuild instanceof SwitchBuild sw && sw.enabled) {
            sw.enabled = false;
            Call.tileConfig(null, sw, Boolean.FALSE);
        }
    }

    /** Reconciles steady, non-pulsing 5-tile {@link ShapeMarker} rings around placed shop stations. */
    public void syncShopCircleMarkersNow() {
        if (state == null || state.markers == null || Groups.build == null) return;

        seenShopPositions.clear();
        for (Building b : Groups.build) {
            StationTier tier = resolveShopTier(b.block);
            if (tier == null || !b.isValid() || b.tile == null) continue;

            int pos = b.tile.pos();
            seenShopPositions.add(pos);
            int existingId = shopCircleMarkers.get(pos, -1);
            if (existingId != -1 && state.markers.has(existingId)) {
                continue;
            }

            int markerId = existingId != -1 ? existingId : allocateCircleMarkerId();
            if (markerId == -1) continue;

            ShapeMarker ring = buildSteadyRingMarker(b.x, b.y, tier);
            shopCircleMarkers.put(pos, markerId);
            Call.createMarker(markerId, ring);
        }

        stalePositions.clear();
        for (IntIntMap.Entry entry : shopCircleMarkers.entries()) {
            if (!seenShopPositions.contains(entry.key)) {
                stalePositions.add(entry.key);
            }
        }
        for (int i = 0; i < stalePositions.size; i++) {
            int pos = stalePositions.get(i);
            int markerId = shopCircleMarkers.remove(pos, -1);
            if (markerId != -1) {
                usedMarkerIds.remove(markerId);
                Call.removeMarker(markerId);
            }
        }
    }

    private void updateCircleMarkers() {
        if (state == null || !state.isGame() || state.isPaused() || Groups.build == null) {
            return;
        }
        if (!timer.get(0, CIRCLE_SYNC_INTERVAL_TICKS)) return;
        syncShopCircleMarkersNow();
    }

    private int allocateCircleMarkerId() {
        for (int i = 0; i < CIRCLE_MARKER_MAX_COUNT; i++) {
            int candidate = CIRCLE_MARKER_BASE_ID + i;
            if (!usedMarkerIds.contains(candidate)) {
                usedMarkerIds.add(candidate);
                return candidate;
            }
        }
        return -1;
    }

    private static ShapeMarker buildSteadyRingMarker(float wx, float wy, StationTier tier) {
        ShapeMarker m = new ShapeMarker(wx, wy, SHOP_RADIUS_WORLD, 0f);
        m.sides = CIRCLE_SIDES;
        m.fill = false;
        m.outline = false;
        m.stroke = 1.15f;
        m.control(LMarkerControl.drawLayer, Layer.blockOver, 0, 0);
        m.color = switch (tier) {
            case WORKBENCH -> COL_WORKBENCH.cpy();
            case BAZAAR -> COL_BAZAAR.cpy();
            case CURSED_ALTAR -> COL_CURSED.cpy();
        };
        return m;
    }

    private static @Nullable Player findOnlinePlayer(String query) {
        if (query == null || query.isBlank() || Groups.player == null) return null;
        String trimmed = query.trim();
        for (Player p : Groups.player) {
            if (p == null || p.con == null || !p.con.isConnected()) continue;
            if (trimmed.equals(p.uuid())
                || (p.plainName() != null && p.plainName().equalsIgnoreCase(trimmed))
                || (p.name != null && p.name.equalsIgnoreCase(trimmed))) {
                return p;
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (Player p : Groups.player) {
            if (p == null || p.con == null || !p.con.isConnected()) continue;
            if (p.plainName() != null && p.plainName().toLowerCase(Locale.ROOT).contains(lower)) {
                return p;
            }
        }
        return null;
    }
}
