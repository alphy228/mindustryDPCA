package coolplugin.gameplay;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.struct.IntMap;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Structs;
import arc.util.Time;
import arc.util.Timer;
import coolplugin.techtree.TechTreeService;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.BuildSelectEvent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Payloadc;
import mindustry.gen.Player;
import mindustry.gen.SetTileCallPacket;
import mindustry.gen.SetTileItemsCallPacket;
import mindustry.gen.Unit;
import mindustry.net.Administration.ActionType;
import mindustry.net.NetConnection;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.modules.ItemModule;

import static mindustry.Vars.*;

/**
 * Coreless building & personal reach-based inventory driver adapted from {@code zombiemode}:
 * <ul>
 *   <li>Destroys all server-side cores on map load (recording their coordinates & teams as fallback spawn sites).</li>
 *   <li>Maintains a per-player client-only {@code core-shard} at {@code (2, 2)} synced to {@code player.team()}.</li>
 *   <li>Drives construction/deconstruction and {@code /t} Tech Tree spending from the unit's carried stack plus
 *       carried/nearby ({@code <= 15} tiles) containers and vaults.</li>
 * </ul>
 */
public class BuildSystem {

    public static final int REACH_TILES = 15;
    public static final Block CLIENT_CORE_BLOCK = Blocks.coreShard;
    public static final int CORE_X = 2, CORE_Y = 2;
    public static final int RESERVED_RAD = 4;

    private static final float ITEM_SYNC_INTERVAL = 30f;
    private static final float CORE_REFRESH_INTERVAL = 300f;
    private static final Color USED_COLOR = Color.valueOf("8cf2ff");
    private static final String FREEBUILD_SETTING_KEY = "coolplugin-freebuild";

    public static final class DestroyedCoreSite {
        public final int x;
        public final int y;
        public final Team team;

        public DestroyedCoreSite(int x, int y, Team team) {
            this.x = x;
            this.y = y;
            this.team = team;
        }
    }

    static final class PlayerState {
        float buildCounter;
        float itemTimer = Mathf.random(ITEM_SYNC_INTERVAL);
        float refreshTimer;
        boolean corePlaced;
        @Nullable Team placedTeam;
    }

    private final @Nullable TechTreeService techTree;
    private final ObjectMap<Player, PlayerState> states = new ObjectMap<>();
    private final IntMap<Long> lastUsedFx = new IntMap<>();
    private final ObjectSet<Building> seen = new ObjectSet<>();
    private final Seq<DestroyedCoreSite> destroyedCoreSites = new Seq<>();

    private @Nullable CoreBuild proxy;
    private boolean started = false;

    public BuildSystem(@Nullable TechTreeService techTree) {
        this.techTree = techTree;
    }

    public void start() {
        if (started) return;
        started = true;

        Events.on(WorldLoadEvent.class, e -> onWorldLoaded());
        Events.on(PlayEvent.class, e -> setupWorld(false));

        Events.on(PlayerJoin.class, e -> {
            if (e.player != null) {
                states.put(e.player, new PlayerState());
            }
        });

        Events.on(PlayerLeave.class, e -> {
            if (e.player != null) {
                states.remove(e.player);
            }
        });

        if (netServer != null && netServer.admins != null) {
            netServer.admins.addActionFilter(action ->
                !(action.type == ActionType.placeBlock
                    && action.tile != null
                    && inReserved(action.tile.x, action.tile.y))
            );
        }

        Events.run(Trigger.update, this::update);

        if (state != null && state.isGame()) {
            onWorldLoaded();
        }

        Log.info("[CoolPlugin] BuildSystem active (reach=@ tiles, client core=@ at @,@)",
            REACH_TILES, CLIENT_CORE_BLOCK.name, CORE_X, CORE_Y);
    }

    private void onWorldLoaded() {
        states.clear();
        lastUsedFx.clear();
        destroyedCoreSites.clear();
        setupWorld(true);
        Timer.schedule(() -> setupWorld(false), 1f);
    }

    /** Marks a player's client-only core dirty after {@code /sync}, world stream, or faction change. */
    public void onPlayerWorldSynced(@Nullable Player player) {
        if (player == null) return;
        PlayerState st = states.get(player);
        if (st != null) {
            st.corePlaced = false;
            st.placedTeam = null;
        }
    }

    /** Forces an immediate client-only core item push (used after TechTree research spends items). */
    public void syncClientCoreNow(@Nullable Player player) {
        if (player == null || player.con == null || !player.con.isConnected()) return;
        pushItems(player, gather(player));
    }

    public Seq<DestroyedCoreSite> getDestroyedCoreSites() {
        return destroyedCoreSites;
    }

    public @Nullable Tile pickFallbackCoreTile(@Nullable Team preferredTeam) {
        if (world == null || world.tiles == null || destroyedCoreSites.isEmpty()) return null;

        if (preferredTeam != null) {
            Seq<DestroyedCoreSite> matching = destroyedCoreSites.select(s -> s.team == preferredTeam);
            if (!matching.isEmpty()) {
                DestroyedCoreSite site = matching.random();
                return world.tile(site.x, site.y);
            }
        }

        DestroyedCoreSite any = destroyedCoreSites.random();
        return world.tile(any.x, any.y);
    }

    public static boolean isStore(@Nullable Block b) {
        if (b == null) return false;
        return b == Blocks.container
            || b == Blocks.vault
            || b == Blocks.reinforcedContainer
            || b == Blocks.reinforcedVault
            || "dp-copper-container".equals(b.name)
            || "copper-container".equals(b.name);
    }

    // ------------------------------------------------------------------ world setup

    public void setupWorld(boolean clearPreviousCoreSites) {
        if (state == null || !state.isGame() || world == null || world.tiles == null) return;

        if (clearPreviousCoreSites) {
            destroyedCoreSites.clear();
        }

        // Apply global per-faction stat bonuses once per loaded map Rules instance
        FactionDef.applyGlobalTeamRules(state.rules);

        // Prevent core removal from triggering instant Game Over, PvP team->derelict wipe, or 0 unit cap!
        state.rules.canGameOver = false;
        state.rules.cleanupDeadTeams = false;
        state.rules.unitCapVariable = false;
        if (state.rules.unitCap <= 0) {
            state.rules.unitCap = 24;
        }

        ObjectSet<Tile> coreTiles = new ObjectSet<>();
        if (Groups.build != null) {
            for (Building b : Groups.build) {
                if (b instanceof CoreBuild && b.tile != null) {
                    coreTiles.add(b.tile);
                }
            }
        }
        if (state.teams != null) {
            for (var teamData : state.teams.getActive()) {
                for (CoreBuild core : teamData.cores) {
                    if (core != null && core.tile != null) {
                        coreTiles.add(core.tile);
                    }
                }
            }
        }

        for (Tile t : coreTiles) {
            Team coreTeam = t.team();
            if (!containsCoreSite(t.x, t.y)) {
                destroyedCoreSites.add(new DestroyedCoreSite(t.x, t.y, coreTeam));
            }
        }
        for (Tile t : coreTiles) {
            t.setNet(Blocks.air);
        }

        // Also ensure the reserved client-only core corner is air on the server
        Tile centre = world.tile(CORE_X, CORE_Y);
        if (centre != null && centre.block() != Blocks.air) {
            centre.setNet(Blocks.air);
        }

        boolean freebuild = Core.settings != null && Core.settings.getBool(FREEBUILD_SETTING_KEY, false);
        state.rules.infiniteResources = freebuild;
        state.rules.buildSpeedMultiplier = 1f;

        if (techTree != null) {
            techTree.syncAllOnlinePlayers(null);
        } else {
            Call.setRules(state.rules);
        }
    }

    private boolean containsCoreSite(int x, int y) {
        for (DestroyedCoreSite s : destroyedCoreSites) {
            if (s.x == x && s.y == y) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ update

    private void update() {
        if (state == null || !state.isGame() || state.isPaused() || world == null || world.tiles == null) return;

        for (Player player : Groups.player) {
            if (player.con == null || !player.con.isConnected()) continue;

            PlayerState st = states.get(player);
            if (st == null) {
                st = new PlayerState();
                states.put(player, st);
            }

            Unit unit = player.unit();
            if (unit != null && !player.dead()) {
                updateBuilder(player, st, unit);
            }

            if (!st.corePlaced
                || st.placedTeam != player.team()
                || (st.refreshTimer += Time.delta) >= CORE_REFRESH_INTERVAL) {
                st.refreshTimer = 0f;
                placeClientCore(player, st);
            } else if ((st.itemTimer += Time.delta) >= ITEM_SYNC_INTERVAL) {
                st.itemTimer = 0f;
                pushItems(player, gather(player));
            }
        }
    }

    // ------------------------------------------------------------------ build driver

    private void updateBuilder(Player player, PlayerState st, Unit unit) {
        if (state.rules.infiniteResources) return;
        if (unit.type.buildSpeed <= 0f || !unit.canBuild() || !unit.updateBuilding) return;

        unit.validatePlans();
        if (unit.plans.isEmpty()) return;

        st.buildCounter += Time.delta;
        if (Float.isNaN(st.buildCounter) || Float.isInfinite(st.buildCounter)) st.buildCounter = 0f;
        st.buildCounter = Math.min(st.buildCounter, 10f);

        int count = 0;
        while (st.buildCounter >= 1f && count++ < 10 && unit.plans.size > 0) {
            st.buildCounter -= 1f;
            step(player, unit);
        }
    }

    private void step(Player player, Unit unit) {
        BuildPlan plan = unit.buildPlan();
        if (plan == null) return;

        Tile tile = world.tile(plan.x, plan.y);
        if (tile == null) {
            unit.plans.removeFirst();
            return;
        }

        if (!unit.within(tile.worldx(), tile.worldy(), unit.type.buildRange)) return;

        if (!plan.breaking && overlapsReserved(plan)) {
            unit.plans.removeFirst();
            return;
        }

        if (!plan.breaking && techTree != null && !techTree.isBlockAllowedForPlayer(player, plan.block)) {
            unit.plans.removeFirst();
            return;
        }

        Res res = gather(player);

        if (!(tile.build instanceof ConstructBuild)) {
            if (!plan.initialized && !plan.breaking
                && Build.validPlaceIgnoreUnits(plan.block, unit.team, plan.x, plan.y, plan.rotation, true, true)) {

                if (!Build.checkNoUnitOverlap(plan.block, plan.x, plan.y)) {
                    unit.plans.addLast(unit.plans.removeFirst());
                    return;
                }

                boolean hasAll = !Structs.contains(plan.block.requirements,
                    s -> res.available.get(s.item, 0) < Math.min(Mathf.round(s.amount * state.rules.buildCostMultiplier), 1));

                if (hasAll) {
                    Call.beginPlace(unit, plan.block, unit.team, plan.x, plan.y, plan.rotation,
                        plan.block.instantBuild ? plan.config : null);

                    if (plan.block.instantBuild) {
                        if (tile.block() == plan.block) {
                            for (var s : plan.block.requirements) {
                                res.take(s.item, Math.max(0, Mathf.round(s.amount * state.rules.buildCostMultiplier)));
                            }
                        }
                        unit.plans.removeFirst();
                        return;
                    }
                } else {
                    plan.stuck = true;
                    unit.plans.addLast(unit.plans.removeFirst());
                    return;
                }
            } else if (!plan.initialized && plan.breaking && Build.validBreak(unit.team, plan.x, plan.y)) {
                Call.beginBreak(unit, unit.team, plan.x, plan.y);
            } else {
                unit.plans.removeFirst();
                return;
            }
        } else if ((tile.team() != unit.team && tile.team() != Team.derelict)
            || (!plan.breaking && (((ConstructBuild) tile.build).current != plan.block || tile.build.tile != tile))) {
            unit.plans.removeFirst();
            return;
        }

        if (!(tile.build instanceof ConstructBuild entity)) return;

        if (!plan.initialized) {
            Events.fire(new BuildSelectEvent(tile, unit.team, unit, plan.breaking));
            plan.initialized = true;
        }

        float bs = 1f / entity.buildCost * unit.type.buildSpeed * unit.buildSpeedMultiplier * state.rules.buildSpeed(unit.team);

        CoreBuild core = proxy(unit.team, res);

        if (plan.breaking) {
            entity.deconstruct(unit, core, bs);
        } else {
            entity.construct(unit, core, bs, plan.config);
        }

        settle(res, core);

        plan.stuck = Mathf.equal(plan.progress, entity.progress);
        plan.progress = entity.progress;
    }

    private CoreBuild proxy(Team team, Res res) {
        if (proxy == null) {
            proxy = (CoreBuild) CLIENT_CORE_BLOCK.newBuilding();
            proxy.items = new ItemModule();
            proxy.storageCapacity = 1_000_000;
        }
        proxy.team = team;
        proxy.items.clear();
        for (ObjectIntMap.Entry<Item> e : res.available) {
            proxy.items.set(e.key, e.value);
        }
        return proxy;
    }

    private void settle(Res res, CoreBuild core) {
        for (int i = 0; i < content.items().size; i++) {
            Item item = content.items().get(i);
            int before = res.available.get(item, 0);
            int after = core.items.get(item);
            if (after < before) {
                res.take(item, before - after);
            } else if (after > before) {
                res.give(item, after - before);
            }
        }
    }

    // ------------------------------------------------------------------ inventory

    public int getAvailableAmount(@Nullable Player player, @Nullable Item item) {
        if (player == null || item == null) return 0;
        return gather(player).available.get(item, 0);
    }

    /** Removes up to {@code maxAmount} of {@code item} from {@code player}'s pooled reach inventory, returning the amount removed. */
    public int takeItems(@Nullable Player player, @Nullable Item item, int maxAmount) {
        if (player == null || item == null || maxAmount <= 0) return 0;
        Res res = gather(player);
        int have = res.available.get(item, 0);
        int toTake = Math.min(have, maxAmount);
        if (toTake <= 0) return 0;
        res.take(item, toTake);
        syncClientCoreNow(player);
        return toTake;
    }

    /**
     * Deposits up to {@code amount} of {@code item} into {@code player}'s nearby reach storage
     * (containers, vaults, copper containers, and unit stack). Any excess that cannot fit vanishes.
     *
     * @return the exact amount successfully deposited into map storage.
     */
    public int giveItems(@Nullable Player player, @Nullable Item item, int amount) {
        if (player == null || item == null || amount <= 0) return 0;
        Res res = gather(player);
        int before = res.available.get(item, 0);
        res.give(item, amount);
        Res afterRes = gather(player);
        int after = afterRes.available.get(item, 0);
        syncClientCoreNow(player);
        return Math.max(0, after - before);
    }

    /** Returns an immutable snapshot of all non-zero map reach resources available to {@code player}. */
    public ObjectIntMap<Item> getAvailableSnapshot(@Nullable Player player) {
        ObjectIntMap<Item> copy = new ObjectIntMap<>();
        if (player == null) return copy;
        Res res = gather(player);
        for (ObjectIntMap.Entry<Item> e : res.available) {
            if (e.value > 0) {
                copy.put(e.key, e.value);
            }
        }
        return copy;
    }

    final class Res {
        final Unit unit;
        final ObjectIntMap<Item> available = new ObjectIntMap<>();
        final Seq<Building> stores = new Seq<>();

        Res(Unit unit) {
            this.unit = unit;
        }

        void take(Item item, int amount) {
            if (amount <= 0) return;

            if (unit != null && unit.stack != null && unit.stack.item == item && unit.stack.amount > 0) {
                int t = Math.min(amount, unit.stack.amount);
                unit.stack.amount -= t;
                amount -= t;
            }

            for (int i = 0; i < stores.size && amount > 0; i++) {
                Building b = stores.get(i);
                if (b.items == null) continue;
                int t = Math.min(amount, b.items.get(item));
                if (t > 0) {
                    b.items.remove(item, t);
                    amount -= t;
                    markUsed(b);
                    Call.setItem(b, item, b.items.get(item));
                }
            }
        }

        void give(Item item, int amount) {
            if (amount <= 0) return;

            for (int i = 0; i < stores.size && amount > 0; i++) {
                Building b = stores.get(i);
                if (b.items == null) continue;
                int space = b.block.itemCapacity - b.items.get(item);
                if (space <= 0) continue;
                int t = Math.min(amount, space);
                b.items.add(item, t);
                amount -= t;
                markUsed(b);
                Call.setItem(b, item, b.items.get(item));
            }

            if (amount > 0 && unit != null && unit.stack != null
                && (unit.stack.amount == 0 || unit.stack.item == item)) {
                int t = Math.min(amount, unit.itemCapacity() - unit.stack.amount);
                if (t > 0) {
                    unit.stack.item = item;
                    unit.stack.amount += t;
                }
            }
        }
    }

    Res gather(Player player) {
        Unit unit = player.unit();
        Res res = new Res(unit);
        if (unit == null || player.dead() || world == null || world.tiles == null) return res;

        if (unit.stack != null && unit.stack.amount > 0 && unit.stack.item != null) {
            add(res, unit.stack.item, unit.stack.amount);
        }

        if (unit instanceof Payloadc pay) {
            for (Payload p : pay.payloads()) {
                if (p instanceof BuildPayload bp && bp.build != null
                    && isStore(bp.build.block) && bp.build.items != null) {
                    addStore(res, bp.build);
                }
            }
        }

        float range = REACH_TILES * tilesize;
        int tx = unit.tileX(), ty = unit.tileY();
        seen.clear();
        for (int dy = -REACH_TILES; dy <= REACH_TILES; dy++) {
            for (int dx = -REACH_TILES; dx <= REACH_TILES; dx++) {
                Tile t = world.tile(tx + dx, ty + dy);
                if (t == null || t.build == null) continue;
                Building b = t.build;
                if (b.team != unit.team || !isStore(b.block) || b.items == null) continue;
                if (!seen.add(b)) continue;
                if (unit.dst(b) > range) continue;
                addStore(res, b);
            }
        }

        return res;
    }

    private void addStore(Res res, Building b) {
        res.stores.add(b);
        for (int i = 0; i < content.items().size; i++) {
            Item it = content.items().get(i);
            int amt = b.items.get(it);
            if (amt > 0) add(res, it, amt);
        }
    }

    private static void add(Res res, Item item, int amount) {
        if (amount <= 0) return;
        long cur = res.available.get(item, 0);
        long sum = Math.min((long) Integer.MAX_VALUE, cur + (long) amount);
        res.available.put(item, (int) sum);
    }

    /**
     * Places an overfilled {@code container} near {@code player} holding {@link Integer#MAX_VALUE}
     * ({@code 2147483647}) of every non-hidden item, keeping {@link ItemModule#any()} positive.
     */
    public @Nullable Building placeInfiniteItemsContainerNear(@Nullable Player player) {
        if (player == null || world == null || world.tiles == null || Blocks.container == null) {
            return null;
        }

        Unit unit = PlayerUnitSystem.ownedUnit(player);
        if (unit == null) unit = player.unit();

        int baseX;
        int baseY;
        Team team;
        if (unit != null && !unit.dead()) {
            baseX = unit.tileX();
            baseY = unit.tileY();
            team = unit.team;
        } else {
            baseX = mindustry.core.World.toTile(player.x);
            baseY = mindustry.core.World.toTile(player.y);
            if (baseX <= 0 && baseY <= 0) {
                baseX = world.width() / 2;
                baseY = world.height() / 2;
            }
            team = player.team();
        }
        if (team == null || team == Team.derelict) {
            team = Team.sharded;
        }

        Tile chosen = findClear2x2Near(baseX, baseY, true);
        if (chosen == null) {
            chosen = findClear2x2Near(baseX, baseY, false);
        }
        if (chosen == null) return null;

        Call.setTile(chosen, Blocks.container, team, 0);
        Building build = chosen.build;
        if (build == null || build.items == null) return null;

        for (int i = 0; i < content.items().size; i++) {
            Item item = content.items().get(i);
            if (item.isHidden()) continue;
            build.items.set(item, Integer.MAX_VALUE);
            Call.setItem(build, item, Integer.MAX_VALUE);
        }

        // Prevent 32-bit signed int wraparound in ItemModule.total when >1 item is at Integer.MAX_VALUE
        clampItemModuleTotal(build.items, Integer.MAX_VALUE);

        syncClientCoreNow(player);
        return build;
    }

    private @Nullable Tile findClear2x2Near(int cx, int cy, boolean requireEmpty) {
        Tile best = null;
        int bestDist2 = Integer.MAX_VALUE;
        int w = world.width();
        int h = world.height();

        for (int dy = -12; dy <= 12; dy++) {
            for (int dx = -12; dx <= 12; dx++) {
                int d2 = dx * dx + dy * dy;
                if (d2 < 4 || d2 >= bestDist2) continue;

                int x = cx + dx;
                int y = cy + dy;
                if (x < 1 || y < 1 || x + 1 >= w - 1 || y + 1 >= h - 1) continue;
                if (inReserved(x, y) || inReserved(x + 1, y + 1) || inReserved(x + 1, y) || inReserved(x, y + 1)) {
                    continue;
                }

                if (requireEmpty) {
                    boolean clear = true;
                    for (int oy = 0; oy <= 1 && clear; oy++) {
                        for (int ox = 0; ox <= 1; ox++) {
                            Tile t = world.tile(x + ox, y + oy);
                            if (t == null || t.build != null || t.solid()) {
                                clear = false;
                                break;
                            }
                        }
                    }
                    if (!clear) continue;
                }

                Tile center = world.tile(x, y);
                if (center != null) {
                    best = center;
                    bestDist2 = d2;
                }
            }
        }
        return best;
    }

    private static void clampItemModuleTotal(ItemModule items, int clampedTotal) {
        try {
            java.lang.reflect.Field f = ItemModule.class.getDeclaredField("total");
            f.setAccessible(true);
            f.setInt(items, clampedTotal);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ client core

    private void placeClientCore(Player player, PlayerState st) {
        Tile tile = world.tile(CORE_X, CORE_Y);
        if (tile == null || player.con == null || !player.con.isConnected()) return;

        sendClientSetTile(player.con, tile, CLIENT_CORE_BLOCK, player.team(), 0);
        st.corePlaced = true;
        st.placedTeam = player.team();

        pushItems(player, gather(player));
    }

    private void pushItems(Player player, Res res) {
        Tile tile = world.tile(CORE_X, CORE_Y);
        if (tile == null || player.con == null || !player.con.isConnected()) return;

        int[] pos = {tile.pos()};
        for (int i = 0; i < content.items().size; i++) {
            Item item = content.items().get(i);
            sendSetTileItems(player.con, item, res.available.get(item, 0), pos);
        }
    }

    private static void sendClientSetTile(NetConnection con, Tile tile, Block block, Team team, int rotation) {
        if (con == null || !con.isConnected()) return;
        SetTileCallPacket packet = new SetTileCallPacket();
        packet.tile = tile;
        packet.block = block;
        packet.team = team;
        packet.rotation = rotation;
        con.send(packet, true);
    }

    private static void sendSetTileItems(NetConnection con, Item item, int amount, int[] positions) {
        if (con == null || !con.isConnected()) return;
        SetTileItemsCallPacket packet = new SetTileItemsCallPacket();
        packet.item = item;
        packet.amount = amount;
        packet.positions = positions;
        con.send(packet, true);
    }

    // ------------------------------------------------------------------ misc

    public static boolean inReserved(int x, int y) {
        return x >= CORE_X - RESERVED_RAD && x <= CORE_X + RESERVED_RAD
            && y >= CORE_Y - RESERVED_RAD && y <= CORE_Y + RESERVED_RAD;
    }

    private static boolean overlapsReserved(BuildPlan plan) {
        int s = plan.block.size;
        int ox = plan.x - (s - 1) / 2, oy = plan.y - (s - 1) / 2;
        return ox <= CORE_X + RESERVED_RAD && ox + s - 1 >= CORE_X - RESERVED_RAD
            && oy <= CORE_Y + RESERVED_RAD && oy + s - 1 >= CORE_Y - RESERVED_RAD;
    }

    private void markUsed(Building b) {
        long now = Time.millis();
        Long last = lastUsedFx.get(b.id);
        if (last != null && now - last < 250) return;
        lastUsedFx.put(b.id, now);
        Call.effect(Fx.mineBig, b.x, b.y, 0f, USED_COLOR);
    }
}
