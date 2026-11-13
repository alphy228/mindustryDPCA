package coolplugin.gameplay;

import arc.Events;
import arc.graphics.Color;
import arc.math.Angles;
import arc.math.geom.Vec2;
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
import mindustry.content.Fx;
import mindustry.game.EventType.ConfigEvent;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.blocks.logic.SwitchBlock.SwitchBuild;

import java.util.Locale;

import static mindustry.Vars.*;

/**
 * World interaction controller for Item Shop buildings ({@code dp-workbench}, {@code dp-bazaar}, {@code dp-cursed-altar}):
 * <ul>
 *   <li>Renders a visible 10-tile ({@code 80f} world units) dashed circular area around every placed shop building.</li>
 *   <li>When a player flips a shop's {@code SwitchBlock} while within its 10-tile circle, opens that station's menu
 *       and resets the switch cleanly.</li>
 *   <li>Registers server console command {@code openmenu <player> [workbench|bazaar|altar|faction|tech]}
 *       to force-open any menu for an online player.</li>
 * </ul>
 */
public class ItemShopSystem {

    public static final float SHOP_RADIUS_TILES = 10f;
    public static final float SHOP_RADIUS_WORLD = SHOP_RADIUS_TILES * tilesize;
    private static final float CIRCLE_RENDER_INTERVAL_TICKS = 18f;
    private static final int CIRCLE_SEGMENTS = 16;

    private static final Color COL_WORKBENCH = Color.valueOf("ffd37f");
    private static final Color COL_BAZAAR = Color.valueOf("7fe6ff");
    private static final Color COL_CURSED = Color.valueOf("ff4b6e");

    private final PlayerItemSystem itemSystem;
    private final BuildSystem buildSystem;
    private final PlayerUnitSystem playerUnitSystem;
    private final TechTreeService techTree;
    private final @Nullable LaunchPadSystem launchPadSystem;
    private final PlayerDatabase database;
    private final PlayerSessionManager sessionManager;
    private final ItemShopMenu shopMenu;
    private final Interval timer = new Interval(1);
    private float phaseDeg = 0f;
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

        Events.on(ConfigEvent.class, this::handleSwitchFlip);
        Events.run(Trigger.update, this::updateCircleVisuals);
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
        AccountRecord targetAccount = null;

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

    private void handleSwitchFlip(ConfigEvent event) {
        if (event == null || event.player == null || event.tile == null) return;
        StationTier tier = resolveShopTier(event.tile.block);
        if (tier == null) return;

        Building shopBuild = event.tile;
        Player player = event.player;

        if (!isWithinShopArea(player, shopBuild)) {
            resetSwitchState(shopBuild);
            player.sendMessage(
                "[scarlet]Step inside the 10-tile circle around [white]" + tier.title + "[scarlet] to open its menu.[]"
            );
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

    private static void resetSwitchState(Building shopBuild) {
        if (shopBuild instanceof SwitchBuild sw && sw.enabled) {
            sw.enabled = false;
            Call.tileConfig(null, sw, Boolean.FALSE);
        }
    }

    private void updateCircleVisuals() {
        if (state == null || !state.isGame() || state.isPaused() || Groups.build == null || Groups.player == null) {
            return;
        }
        if (!timer.get(0, CIRCLE_RENDER_INTERVAL_TICKS)) return;
        if (Groups.player.isEmpty()) return;

        phaseDeg = (phaseDeg + 4f) % 360f;
        float stepDeg = 360f / CIRCLE_SEGMENTS;
        float dashDeg = stepDeg * 0.58f;

        for (Building b : Groups.build) {
            StationTier tier = resolveShopTier(b.block);
            if (tier == null || !b.isValid()) continue;

            Color ringColor = switch (tier) {
                case WORKBENCH -> COL_WORKBENCH;
                case BAZAAR -> COL_BAZAAR;
                case CURSED_ALTAR -> COL_CURSED;
            };

            for (Player p : Groups.player) {
                if (p == null || p.con == null || !p.con.isConnected()) continue;
                // Only stream circle FX to players whose camera/unit is reasonably close
                Unit u = PlayerUnitSystem.ownedUnit(p);
                if (u != null && u.isValid() && !u.within(b.x, b.y, 48f * tilesize)) continue;

                for (int s = 0; s < CIRCLE_SEGMENTS; s++) {
                    float a1 = phaseDeg + s * stepDeg;
                    float a2 = a1 + dashDeg;
                    float x1 = b.x + Angles.trnsx(a1, SHOP_RADIUS_WORLD);
                    float y1 = b.y + Angles.trnsy(a1, SHOP_RADIUS_WORLD);
                    float x2 = b.x + Angles.trnsx(a2, SHOP_RADIUS_WORLD);
                    float y2 = b.y + Angles.trnsy(a2, SHOP_RADIUS_WORLD);
                    Call.effect(p.con, Fx.pointBeam, x1, y1, 0f, ringColor, new Vec2(x2, y2));
                }
            }
        }
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
